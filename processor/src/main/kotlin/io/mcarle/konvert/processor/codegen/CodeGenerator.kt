package io.mcarle.konvert.processor.codegen

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.CodeBlock
import io.mcarle.konvert.api.Mapping
import io.mcarle.konvert.converter.api.TypeConverterRegistry
import io.mcarle.konvert.converter.api.config.Configuration
import io.mcarle.konvert.converter.api.config.InvalidMappingStrategy
import io.mcarle.konvert.converter.api.config.NON_CONSTRUCTOR_PROPERTIES_MAPPING_OPTION
import io.mcarle.konvert.converter.api.config.NonConstructorPropertiesMapping
import io.mcarle.konvert.converter.api.config.invalidMappingStrategy
import io.mcarle.konvert.converter.api.config.nonConstructorPropertiesMapping
import io.mcarle.konvert.converter.api.isNullable
import io.mcarle.konvert.processor.AnnotatedConverter
import io.mcarle.konvert.processor.exceptions.InvalidMappingException
import io.mcarle.konvert.processor.exceptions.NotAssignableTargetException
import io.mcarle.konvert.processor.exceptions.PropertyMappingNotExistingException
import io.mcarle.konvert.processor.sourcedata.DefaultSourceDataExtractionStrategy
import io.mcarle.konvert.processor.targetdata.DefaultTargetDataExtractionStrategy
import io.mcarle.konvert.processor.targetdata.TargetDataExtractionStrategy

class CodeGenerator constructor(
    private val logger: KSPLogger,
    private val resolver: Resolver
) {

    private val sourceDataExtractionStrategy = DefaultSourceDataExtractionStrategy()
    private val targetDataExtractionStrategy = DefaultTargetDataExtractionStrategy()

    fun generateCode(
        mappings: List<Mapping>,
        enforcedConstructorTypes: List<KSClassDeclaration>,
        context: MappingContext,
        visibilityContext: MappingVisibilityContext,
        additionalSourceParameters: List<KSValueParameter>
    ): CodeBlock {
        if (context.paramName != null && mappings.isEmpty()) {
            val existingTypeConverter = TypeConverterRegistry
                .firstOrNull {
                    it.matches(context.source, context.target) && it !is AnnotatedConverter
                }

            if (existingTypeConverter != null) {
                return CodeBlock.of(
                    "return·%L",
                    existingTypeConverter.convert(context.paramName, context.source, context.target)
                )
            }
        }

        val sourceDataList = sourceDataExtractionStrategy.extract(resolver, context.sourceClassDeclaration, visibilityContext)
        val targetData = targetDataExtractionStrategy.extract(resolver, context.targetClassDeclaration, visibilityContext)

        val sourceProperties = PropertyMappingResolver(logger).determinePropertyMappings(
            mappingParamName = context.paramName,
            mappings = mappings,
            additionalSourceParameters = additionalSourceParameters,
            sourceDataList = sourceDataList
        )

        val constructor = ConstructorResolver.determineConstructor(
            targetData = targetData,
            sourceProperties = sourceProperties,
            constructorTypes = enforcedConstructorTypes
        )

        val constructorParameters = constructor.parameters

        verifyAvailableMappingsForConstructorParameters(sourceProperties, constructorParameters)

        verifyTargetExists(mappings, constructorParameters, targetData.varProperties, targetData.setter)

        val effectiveNonConstructorPropertiesMappingMode = determineNonConstructorPropertiesMappingMode(
            targetData,
            sourceProperties
        )

        val variablesWithoutConstructorParameters = obtainTargetNonConstructorProperties(
            sourceProperties,
            constructorParameters,
            targetData,
            effectiveNonConstructorPropertiesMappingMode
        )

        val remainingSetters = obtainTargetNonConstructorSetters(
            variablesWithoutConstructorParameters,
            sourceProperties,
            constructorParameters,
            targetData,
            effectiveNonConstructorPropertiesMappingMode
        )

        return MappingCodeGenerator(logger).generateMappingCode(
            context,
            sourceProperties.sortedByDescending { it.isBasedOnAnnotation },
            constructor,
            variablesWithoutConstructorParameters.map { it.property },
            remainingSetters.toList()
        )
    }

    /**
     * Generates the statements writing into the existing instance of [updateTarget] instead of constructing a new one.
     * Only assignable target properties are written: a `var`, a setter, or a `val` whose mutable collection or map
     * gets its content replaced. A target without a source keeps its value.
     */
    fun generateUpdateCode(
        mappings: List<Mapping>,
        context: MappingContext,
        updateTarget: UpdateTarget,
        visibilityContext: MappingVisibilityContext,
        additionalSourceParameters: List<KSValueParameter>
    ): CodeBlock {
        val sourceDataList = sourceDataExtractionStrategy.extract(resolver, context.sourceClassDeclaration, visibilityContext)
        val extractedTargetData = targetDataExtractionStrategy.extract(resolver, context.targetClassDeclaration, visibilityContext)

        val readOnlyProperties = context.targetClassDeclaration.getAllProperties()
            .filter { it.extensionReceiver == null }
            .filter { it.isVisibleFrom(visibilityContext) }
            .filterNot { it.isMutable }
            .toList()
        val replaceableProperties = readOnlyProperties.filter { ReplaceableContent.of(it, resolver) != null }
        val notAssignableNames = (readOnlyProperties - replaceableProperties.toSet()).map { it.simpleName.asString() }

        val notAssignableMappings = mappings.filter { !it.ignore && it.target in notAssignableNames }
        if (notAssignableMappings.isNotEmpty()) {
            throw NotAssignableTargetException(notAssignableMappings.map { it.target })
        }

        val targetData = extractedTargetData.copy(
            varProperties = extractedTargetData.varProperties
                + replaceableProperties.map { TargetDataExtractionStrategy.TargetVarProperty(it) }
        )

        val sourceProperties = PropertyMappingResolver(logger).determinePropertyMappings(
            mappingParamName = context.paramName,
            mappings = mappings,
            additionalSourceParameters = additionalSourceParameters,
            sourceDataList = sourceDataList
        )

        verifyTargetExists(mappings, emptyList(), targetData.varProperties, targetData.setter)

        val effectiveNonConstructorPropertiesMappingMode = determineUpdateMappingMode(targetData)

        val targetProperties = obtainTargetNonConstructorProperties(
            sourceProperties,
            emptyList(),
            targetData,
            effectiveNonConstructorPropertiesMappingMode
        )

        val targetSetters = obtainTargetNonConstructorSetters(
            targetProperties,
            sourceProperties,
            emptyList(),
            targetData,
            effectiveNonConstructorPropertiesMappingMode
        )

        return MappingCodeGenerator(logger).generateUpdateCode(
            context = context,
            updateTarget = updateTarget,
            sourceProperties = sourceProperties.sortedByDescending { it.isBasedOnAnnotation },
            targetProperties = targetProperties.map { it.property },
            targetSetters = targetSetters.toList(),
            additionalSourceParameters = additionalSourceParameters,
            replaceableContent = { ReplaceableContent.of(it, resolver) }
        )
    }

    /**
     * Every property of an existing instance is a non-constructor property, so [NonConstructorPropertiesMapping.AUTO]
     * resolves to [NonConstructorPropertiesMapping.IMPLICIT]: a single [Mapping] must not drop all implicit matches.
     */
    private fun determineUpdateMappingMode(targetData: TargetDataExtractionStrategy.TargetData): NonConstructorPropertiesMapping {
        return when (val value = Configuration.nonConstructorPropertiesMapping) {
            NonConstructorPropertiesMapping.AUTO -> NonConstructorPropertiesMapping.IMPLICIT.also {
                logger.logging("${NON_CONSTRUCTOR_PROPERTIES_MAPPING_OPTION.key} resolved to: $it", targetData.classDeclaration)
            }

            NonConstructorPropertiesMapping.IMPLICIT,
            NonConstructorPropertiesMapping.EXPLICIT,
            NonConstructorPropertiesMapping.ALL -> value
        }
    }

    private fun verifyTargetExists(
        mappings: List<Mapping>,
        constructorParameters: List<KSValueParameter>,
        varProperties: List<TargetDataExtractionStrategy.TargetVarProperty>,
        setter: List<TargetDataExtractionStrategy.TargetSetter>
    ) {
        val mappingsWithMissingTargets = mappings.filter {
            it.target !in varProperties.map { it.name }
                && it.target !in setter.map { it.name }
                && it.target !in constructorParameters.mapNotNull { it.name?.asString() }
        }
        if (mappingsWithMissingTargets.isNotEmpty()) {
            when (Configuration.invalidMappingStrategy) {
                InvalidMappingStrategy.WARN -> mappingsWithMissingTargets.forEach {
                    logger.warn("Ignoring the mapping $it as the target field '${it.target}' does not exist.")
                }
                InvalidMappingStrategy.FAIL -> throw InvalidMappingException.missingTarget(mappingsWithMissingTargets)
            }
        }
    }

    /**
     * Determines which non-constructor properties and setters mapping mode
     */
    private fun determineNonConstructorPropertiesMappingMode(
        targetData: TargetDataExtractionStrategy.TargetData,
        sourceProperties: List<PropertyMappingInfo>,
    ): NonConstructorPropertiesMapping {
        return when (val value = Configuration.nonConstructorPropertiesMapping) {
            NonConstructorPropertiesMapping.AUTO -> {
                val userDefinedProperties = sourceProperties.filter { it.isBasedOnAnnotation }

                return if (userDefinedProperties.none { !it.ignore }) {
                    NonConstructorPropertiesMapping.IMPLICIT
                } else {
                    NonConstructorPropertiesMapping.EXPLICIT
                }.also {
                    logger.logging(
                        "${NON_CONSTRUCTOR_PROPERTIES_MAPPING_OPTION.key} resolved to: $it",
                        targetData.classDeclaration
                    )
                }
            }
            NonConstructorPropertiesMapping.IMPLICIT,
            NonConstructorPropertiesMapping.EXPLICIT,
            NonConstructorPropertiesMapping.ALL -> value
        }
    }

    /**
     * Determines which non-constructor properties should be included in the mapping.
     */
    private fun obtainTargetNonConstructorProperties(
        sourceProperties: List<PropertyMappingInfo>,
        constructorParameters: List<KSValueParameter>,
        targetData: TargetDataExtractionStrategy.TargetData,
        effectiveMappingMode: NonConstructorPropertiesMapping
    ): Set<TargetDataExtractionStrategy.TargetVarProperty> {

        val remainingProperties = targetData.varProperties
            .filter { variable ->
                variable.name !in constructorParameters.mapNotNull { it.name?.asString() }
            }

        val propertiesToSourceProperty = remainingProperties
            .associateWith { variable ->
                when (effectiveMappingMode) {
                    NonConstructorPropertiesMapping.AUTO -> throw IllegalStateException("Did not resolve mapping mode ${NonConstructorPropertiesMapping.AUTO}")
                    NonConstructorPropertiesMapping.ALL,
                    NonConstructorPropertiesMapping.IMPLICIT -> sourceProperties.firstOrNull { it.targetName == variable.name }
                    NonConstructorPropertiesMapping.EXPLICIT -> sourceProperties.firstOrNull { it.targetName == variable.name && it.isBasedOnAnnotation }
                }
            }

        val missingMappings = propertiesToSourceProperty.filterValues { it == null }
        if (effectiveMappingMode == NonConstructorPropertiesMapping.ALL && missingMappings.isNotEmpty()) {
            val missingMappingsForProperties = missingMappings.map { it.key.name }.joinToString()
            throw RuntimeException(
                "With `konvert.non-constructor-properties-mapping` being ${NonConstructorPropertiesMapping.ALL}, all target properties must have a matching source property. " +
                    "Missing mappings for properties: ${missingMappingsForProperties}."
            )
        }

        return propertiesToSourceProperty.filterValues { it != null && !it.ignore }.keys
    }

    /**
     * Determines which setters should be included in the mapping.
     */
    private fun obtainTargetNonConstructorSetters(
        mappedNonConstructorProperties: Set<TargetDataExtractionStrategy.TargetVarProperty>,
        sourceProperties: List<PropertyMappingInfo>,
        constructorParameters: List<KSValueParameter>,
        targetData: TargetDataExtractionStrategy.TargetData,
        effectiveMappingMode: NonConstructorPropertiesMapping
    ): Set<TargetDataExtractionStrategy.TargetSetter> {

        val remainingSetters = targetData.setter
            .filter { setter ->
                setter.name !in constructorParameters.mapNotNull { it.name?.asString() }
                    && setter.name !in mappedNonConstructorProperties.map { it.name }
            }

        val settersToSourceProperty = remainingSetters
            .associateWith { setter ->
                when (effectiveMappingMode) {
                    NonConstructorPropertiesMapping.AUTO -> throw IllegalStateException("Did not resolve mapping mode ${NonConstructorPropertiesMapping.AUTO}")
                    NonConstructorPropertiesMapping.ALL,
                    NonConstructorPropertiesMapping.IMPLICIT -> sourceProperties.firstOrNull { it.targetName == setter.name }
                    NonConstructorPropertiesMapping.EXPLICIT -> sourceProperties.firstOrNull { it.targetName == setter.name && it.isBasedOnAnnotation }
                }
            }

        val missingMappings = settersToSourceProperty.filterValues { it == null }
        if (effectiveMappingMode == NonConstructorPropertiesMapping.ALL && missingMappings.isNotEmpty()) {
            val missingMappingsForSetters = missingMappings.map { it.key.name }.joinToString()
            throw RuntimeException(
                "With `konvert.non-constructor-properties-mapping` being ${NonConstructorPropertiesMapping.ALL}, all target setters must have a matching source property. " +
                    "Missing mappings for setters: ${missingMappingsForSetters}."
            )
        }
        return settersToSourceProperty.filterValues { it != null && !it.ignore }.keys
    }

    private fun verifyAvailableMappingsForConstructorParameters(
        sourceProperties: List<PropertyMappingInfo>,
        constructorParameters: List<KSValueParameter>
    ) {
        val availableNotIgnoredTargetNames = sourceProperties
            .filterNot { it.ignore }
            .map { it.targetName }

        val missingSourceForRequiredParameter = constructorParameters.firstOrNull {
            if (it.hasDefault) {
                false
            } else if (it.type.resolve().isNullable()) {
                false
            } else {
                it.name?.asString() !in availableNotIgnoredTargetNames
            }
        }
        if (missingSourceForRequiredParameter != null) {
            throw PropertyMappingNotExistingException(missingSourceForRequiredParameter, sourceProperties)
        }
    }

}
