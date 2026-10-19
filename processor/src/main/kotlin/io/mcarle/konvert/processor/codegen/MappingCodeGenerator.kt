package io.mcarle.konvert.processor.codegen

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Origin
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.toClassName
import io.mcarle.konvert.converter.api.TypeConverterRegistry
import io.mcarle.konvert.converter.api.config.Configuration
import io.mcarle.konvert.converter.api.config.EnforceNotNullStrategy
import io.mcarle.konvert.converter.api.config.UpdateNullStrategy
import io.mcarle.konvert.converter.api.config.enableConverters
import io.mcarle.konvert.converter.api.config.enforceNotNull
import io.mcarle.konvert.converter.api.config.enforceNotNullStrategy
import io.mcarle.konvert.converter.api.isNullable
import io.mcarle.konvert.processor.exceptions.IgnoredTargetNotIgnorableException
import io.mcarle.konvert.processor.exceptions.NoMatchingTypeConverterException
import io.mcarle.konvert.processor.exceptions.NotNullOperatorNotEnabledException
import io.mcarle.konvert.processor.exceptions.PropertyMappingNotExistingException
import io.mcarle.konvert.processor.targetdata.TargetDataExtractionStrategy.TargetSetter
import java.util.Locale

class MappingCodeGenerator constructor(
    private val logger: KSPLogger
) {

    fun generateMappingCode(
        context: MappingContext,
        sourceProperties: List<PropertyMappingInfo>,
        constructor: KSFunctionDeclaration,
        targetProperties: List<KSPropertyDeclaration>,
        targetSetters: List<TargetSetter>
    ): CodeBlock {
        val className = constructor.parentDeclaration!!.simpleName.asString()
        val constructorCode = constructorCode(
            className = context.targetClassImportName,
            classDeclaration = context.targetClassDeclaration,
            constructor = constructor,
            sourceProperties = sourceProperties
        )
        val propertyCode = propertyCode(
            className = className,
            functionParamName = context.paramName,
            sourceProperties = sourceProperties,
            targetProperties = targetProperties,
            targetSetters = targetSetters
        )
        return if (context.source.isNullable()) {
            // source can only be nullable in case of @Konverter/@Konvert which require a functionParamName
            val code = "return·${context.paramName!!}?.let·{\n⇥%L%L⇤\n}"
            if (context.target.isNullable()) {
                CodeBlock.of(code, constructorCode, propertyCode)
            } else {
                if (Configuration.enforceNotNull) {
                    when (Configuration.enforceNotNullStrategy) {
                        EnforceNotNullStrategy.ASSERTION_OPERATOR ->
                            CodeBlock.of("$code!!", constructorCode, propertyCode)

                        EnforceNotNullStrategy.REQUIRE_NOT_NULL -> {
                            val requireCode =
                                "return·requireNotNull(${context.paramName})·{·\"${context.paramName}·must·not·be·null\"·}·.let·{\n⇥%L%L⇤\n}"
                            CodeBlock.of(requireCode, constructorCode, propertyCode)
                        }
                    }
                } else {
                    throw NotNullOperatorNotEnabledException(context.paramName, context.source, context.target)
                }
            }
        } else {
            CodeBlock.of("return·%L%L", constructorCode, propertyCode)
        }
    }

    /**
     * Writes into the existing instance of [updateTarget] property by property, see [CodeGenerator.generateUpdateCode].
     */
    fun generateUpdateCode(
        context: MappingContext,
        updateTarget: UpdateTarget,
        sourceProperties: List<PropertyMappingInfo>,
        targetProperties: List<KSPropertyDeclaration>,
        targetSetters: List<TargetSetter>,
        additionalSourceParameters: List<KSValueParameter>,
        replaceableContent: (KSPropertyDeclaration) -> ReplaceableContent?
    ): CodeBlock {
        val target = updateTarget.paramName
        val propertyCodeBlocks = targetProperties.mapNotNull { targetProperty ->
            val sourceProperty = determinePropertyMappingInfo(sourceProperties, targetProperty)
            val content = replaceableContent(targetProperty)
            updateCode(sourceProperty, targetProperty.type, additionalSourceParameters, updateTarget.nullStrategy) { value ->
                if (content == null) {
                    CodeBlock.of("$target.${sourceProperty.targetName}·=·%L", value)
                } else {
                    replaceContentCode("$target.${sourceProperty.targetName}", content, value, target)
                }
            }
        }
        val setterCodeBlocks = targetSetters.mapNotNull { targetSetter ->
            val sourceProperty = determinePropertyMappingInfo(sourceProperties, targetSetter)
            updateCode(sourceProperty, targetSetter.typeRef, additionalSourceParameters, updateTarget.nullStrategy) { value ->
                CodeBlock.of("$target.%L", targetSetter.generateAssignmentCode(value))
            }
        }
        val assignments = propertyCodeBlocks + setterCodeBlocks

        val body = when {
            assignments.isEmpty() -> null
            // a null source leaves the target untouched
            context.source.isNullable() -> CodeBlock.of("if·(${context.paramName!!}·!=·null)·{\n⇥%L⇤\n}", assignments.joinToCode("\n"))
            else -> assignments.joinToCode("\n")
        }
        val returnCode = CodeBlock.of("return·$target").takeIf { updateTarget.returnsTarget }
        return listOfNotNull(body, returnCode).joinToCode("\n")
    }

    /**
     * With [UpdateNullStrategy.IGNORE], a nullable value read from a source property or a parameter is only assigned
     * when it is not null. Constants and expressions are assigned as written.
     */
    private fun updateCode(
        source: PropertyMappingInfo,
        targetTypeRef: KSTypeReference,
        additionalSourceParameters: List<KSValueParameter>,
        nullStrategy: UpdateNullStrategy,
        assign: (CodeBlock) -> CodeBlock
    ): CodeBlock? {
        if (source.ignore) return null

        if (nullStrategy == UpdateNullStrategy.IGNORE && source.constant == null && source.expression == null) {
            val sourceAccess = sourceAccess(source)
            if (sourceAccess != null && sourceAccess.type.isNullable()) {
                val value = convertFrom(source, "it", sourceAccess.type.makeNotNullable(), targetTypeRef.resolve())
                return CodeBlock.of("${sourceAccess.code}?.let·{\n⇥%L⇤\n}", assign(value))
            }
        }
        if (nullStrategy == UpdateNullStrategy.IGNORE && source.sourceData == null && source.constant != null) {
            val parameter = additionalSourceParameters.firstOrNull { it.name?.asString() == source.constant }
            if (parameter != null && parameter.type.resolve().isNullable()) {
                return CodeBlock.of("${source.constant}?.let·{\n⇥%L⇤\n}", assign(CodeBlock.of("it")))
            }
        }

        return convertValue(
            source = source,
            targetTypeRef = targetTypeRef,
            valueParamIsNullable = false,
            valueParamHasDefault = true
        )?.let(assign)
    }

    /**
     * Replaces the content of the collection or map a `val` holds. A value that is that very instance is left alone,
     * as clearing it first would lose its content.
     */
    private fun replaceContentCode(access: String, content: ReplaceableContent, value: CodeBlock, targetParamName: String): CodeBlock {
        val propertyName = access.substringAfterLast('.')
        var valueName = "new" + propertyName.replaceFirstChar { it.uppercase(Locale.getDefault()) }
        while (valueName == targetParamName) {
            valueName += "0"
        }
        return CodeBlock.of(
            "(%L).let·{·$valueName·->\n⇥if·($valueName·!==·$access)·{\n⇥$access.clear()\n$access.${content.addAllFunction}($valueName)⇤\n}⇤\n}",
            value
        )
    }

    private fun constructorCode(
        className: String?,
        classDeclaration: KSClassDeclaration,
        constructor: KSFunctionDeclaration,
        sourceProperties: List<PropertyMappingInfo>
    ): CodeBlock {
        if (className == null) {
            return if (constructor.parameters.isEmpty()) {
                CodeBlock.of("%T()", classDeclaration.toClassName())
            } else {
                CodeBlock.of(
                    """
%T(${"⇥\n%L"}
⇤)
                    """.trimIndent(),
                    classDeclaration.toClassName(),
                    constructorParamsCode(constructor = constructor, sourceProperties = sourceProperties)
                )
            }
        }
        return if (constructor.parameters.isEmpty()) {
            CodeBlock.of("$className()")
        } else {
            CodeBlock.of(
                """
$className(${"⇥\n%L"}
⇤)
                """.trimIndent(),
                constructorParamsCode(constructor = constructor, sourceProperties = sourceProperties)
            )
        }
    }

    private fun constructorParamsCode(
        constructor: KSFunctionDeclaration,
        sourceProperties: List<PropertyMappingInfo>
    ): CodeBlock {
        return constructor.parameters.mapNotNull { ksValueParameter ->
            val constructorHasParamNames = constructor.origin !in listOf(
                Origin.JAVA,
                Origin.JAVA_LIB
            )
            val valueParamHasDefault = ksValueParameter.hasDefault
            val valueParamIsNullable = ksValueParameter.type.resolve().isNullable()

            val propertyMappingInfo = determinePropertyMappingInfo(sourceProperties, ksValueParameter)
            val convertedValue = convertValue(
                source = propertyMappingInfo,
                targetTypeRef = ksValueParameter.type,
                valueParamHasDefault = valueParamHasDefault,
                valueParamIsNullable = valueParamIsNullable
            )

            if (convertedValue != null) {
                if (constructorHasParamNames) {
                    CodeBlock.of("${propertyMappingInfo?.targetName ?: ksValueParameter.name?.asString()}·=·%L", convertedValue)
                } else {
                    convertedValue
                }
            } else {
                null
            }
        }.joinToCode(separator = ",\n")
    }

    private fun propertyCode(
        className: String,
        functionParamName: String?,
        sourceProperties: List<PropertyMappingInfo>,
        targetProperties: List<KSPropertyDeclaration>,
        targetSetters: List<TargetSetter>
    ): CodeBlock {
        if (noTargetOrAllIgnored(sourceProperties, targetProperties, targetSetters)) return CodeBlock.of("")

        var varName = className.replaceFirstChar { it.lowercase(Locale.getDefault()) }
        if (varName == functionParamName) {
            varName += "0"
        }

        return CodeBlock.of(
            """
.also·{·$varName·->${"⇥\n%L"}
⇤}
        """.trimIndent(), propertySettingCode(targetProperties, targetSetters, sourceProperties, varName)
        )
    }

    private fun noTargetOrAllIgnored(
        sourceProperties: List<PropertyMappingInfo>,
        targetProperties: List<KSPropertyDeclaration>,
        targetSetters: List<TargetSetter>
    ): Boolean {
        return targetProperties.all { targetProperty ->
            sourceProperties.any { sourceProperty ->
                sourceProperty.ignore
                    && sourceProperty.targetName == targetProperty.simpleName.asString()
            }
        } && targetSetters.all { targetSetter ->
            sourceProperties.any { sourceProperty ->
                sourceProperty.ignore
                    && sourceProperty.targetName == targetSetter.name
            }
        }
    }

    private fun propertySettingCode(
        targetProperties: List<KSPropertyDeclaration>,
        targetSetters: List<TargetSetter>,
        sourceProperties: List<PropertyMappingInfo>,
        targetVarName: String
    ): CodeBlock {
        val propertyCodeBlocks = targetProperties.mapNotNull { targetProperty ->
            val sourceProperty = determinePropertyMappingInfo(sourceProperties, targetProperty)
            val convertedValue = convertValue(
                source = sourceProperty,
                targetTypeRef = targetProperty.type,
                valueParamIsNullable = false,
                valueParamHasDefault = true
            )
            if (convertedValue != null) {
                CodeBlock.of("$targetVarName.${sourceProperty.targetName}·=·%L", convertedValue)
            } else {
                null
            }
        }

        val setterCodeBlocks = targetSetters.mapNotNull { targetSetter ->
            val sourceProperty = determinePropertyMappingInfo(sourceProperties, targetSetter)
            val convertedValue = convertValue(
                source = sourceProperty,
                targetTypeRef = targetSetter.typeRef,
                valueParamIsNullable = false,
                valueParamHasDefault = true
            )
            if (convertedValue != null) {
                CodeBlock.of("$targetVarName.%L", targetSetter.generateAssignmentCode(convertedValue))
            } else {
                null
            }
        }


        return (propertyCodeBlocks + setterCodeBlocks).joinToCode("\n")
    }

    private fun determinePropertyMappingInfo(
        propertyMappings: List<PropertyMappingInfo>,
        ksValueParameter: KSValueParameter
    ): PropertyMappingInfo? {
        return propertyMappings.firstOrNull {
            it.targetName == ksValueParameter.name?.asString()
        }
    }

    private fun determinePropertyMappingInfo(
        propertyMappings: List<PropertyMappingInfo>,
        ksPropertyDeclaration: KSPropertyDeclaration
    ): PropertyMappingInfo {
        return propertyMappings.firstOrNull {
            it.targetName == ksPropertyDeclaration.simpleName.asString()
        } ?: throw PropertyMappingNotExistingException(ksPropertyDeclaration, propertyMappings)
    }

    private fun determinePropertyMappingInfo(
        propertyMappings: List<PropertyMappingInfo>,
        setter: TargetSetter
    ): PropertyMappingInfo {
        return propertyMappings.firstOrNull {
            it.targetName == setter.name
        } ?: throw PropertyMappingNotExistingException(setter.name, propertyMappings)
    }

    private fun convertValue(
        source: PropertyMappingInfo?,
        targetTypeRef: KSTypeReference,
        valueParamHasDefault: Boolean,
        valueParamIsNullable: Boolean
    ): CodeBlock? {
        val targetType = targetTypeRef.resolve()

        return when {
            source == null -> handleNullSource(valueParamHasDefault, valueParamIsNullable, targetTypeRef)
            source.sourceData == null -> handleNullSourceData(source, valueParamHasDefault, valueParamIsNullable, targetTypeRef)
            else -> handleNonNullSourceData(source, targetType)
        }
    }

    private fun handleNullSource(
        valueParamHasDefault: Boolean,
        valueParamIsNullable: Boolean,
        targetTypeRef: KSTypeReference
    ): CodeBlock? {
        return when {
            valueParamHasDefault -> null
            valueParamIsNullable -> CodeBlock.of("null")
            else -> throw PropertyMappingNotExistingException(targetTypeRef.toString(), emptyList())
        }
    }

    private fun handleNullSourceData(
        source: PropertyMappingInfo,
        valueParamHasDefault: Boolean,
        valueParamIsNullable: Boolean,
        targetTypeRef: KSTypeReference
    ): CodeBlock? {
        return when {
            source.ignore -> handleIgnoredSource(valueParamHasDefault, valueParamIsNullable, targetTypeRef)
            source.constant != null -> CodeBlock.of(source.constant)
            source.expression != null -> {
                val expression = "let·{ ${source.expression} }"
                CodeBlock.of(
                    source.mappingParamName
                        ?.let { "$it.$expression" }
                        ?: expression
                )
            }
            else -> error("Could not convert value $source")
        }
    }

    private fun handleIgnoredSource(
        valueParamHasDefault: Boolean,
        valueParamIsNullable: Boolean,
        targetTypeRef: KSTypeReference
    ): CodeBlock? {
        return when {
            valueParamHasDefault -> null
            valueParamIsNullable -> CodeBlock.of("null")
            else -> throw IgnoredTargetNotIgnorableException(targetTypeRef.toString())
        }
    }

    private fun handleNonNullSourceData(
        source: PropertyMappingInfo,
        targetType: KSType
    ): CodeBlock {
        val sourceAccess = sourceAccess(source)!!
        return convertFrom(source, sourceAccess.code, sourceAccess.type, targetType)
    }

    /**
     * The code reading the value of [source] from its source object, and the type of that value
     */
    private fun sourceAccess(source: PropertyMappingInfo): SourceAccess? {
        val sourceData = source.sourceData ?: return null
        val paramName = source.mappingParamName?.let { "$it." } ?: ""
        return SourceAccess(code = paramName + sourceData.accessCode, type = sourceData.typeRef.resolve())
    }

    private data class SourceAccess(val code: String, val type: KSType)

    private fun convertFrom(
        source: PropertyMappingInfo,
        sourceAccessCode: String,
        sourceType: KSType,
        targetType: KSType
    ): CodeBlock {
        val paramName = source.mappingParamName?.let { "$it." } ?: ""
        return TypeConverterRegistry.withAdditionallyEnabledConverters(source.enableConverters + Configuration.enableConverters) {
            firstOrNull { it.matches(sourceType, targetType) }
                ?.convert(sourceAccessCode, sourceType, targetType)
                ?: throwException(paramName + source.sourceName, sourceType, source.targetName, targetType)
        }
    }

    private fun throwException(
        sourceName: String,
        sourceType: KSType,
        targetName: String,
        targetType: KSType
    ): Nothing {
        val notNullOperatorNeeded = sourceType.isNullable() && !targetType.isNullable()
        val typeConverterExisting = { TypeConverterRegistry.any { it.matches(sourceType, targetType.makeNullable()) } }

        if (notNullOperatorNeeded && !Configuration.enforceNotNull && typeConverterExisting()) {
            throw NotNullOperatorNotEnabledException(sourceName, sourceType, targetName, targetType)
        }
        throw NoMatchingTypeConverterException(sourceName, sourceType, targetName, targetType)
    }
}
