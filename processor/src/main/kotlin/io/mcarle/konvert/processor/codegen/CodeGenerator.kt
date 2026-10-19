package io.mcarle.konvert.processor.codegen

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin
import com.squareup.kotlinpoet.CodeBlock
import io.mcarle.konvert.api.Mapping
import io.mcarle.konvert.converter.api.TypeConverterRegistry
import io.mcarle.konvert.converter.api.config.Configuration
import io.mcarle.konvert.converter.api.config.IgnoredTargetPolicy
import io.mcarle.konvert.converter.api.config.InvalidMappingStrategy
import io.mcarle.konvert.converter.api.config.NON_CONSTRUCTOR_PROPERTIES_MAPPING_OPTION
import io.mcarle.konvert.converter.api.config.NonConstructorPropertiesMapping
import io.mcarle.konvert.converter.api.config.UnmappedTargetPolicy
import io.mcarle.konvert.converter.api.config.enableConverters
import io.mcarle.konvert.converter.api.config.ignoredTargetPolicy
import io.mcarle.konvert.converter.api.config.invalidMappingStrategy
import io.mcarle.konvert.converter.api.config.nonConstructorPropertiesMapping
import io.mcarle.konvert.converter.api.config.unmappedTargetPolicy
import io.mcarle.konvert.converter.api.isNullable
import io.mcarle.konvert.processor.AnnotatedConverter
import io.mcarle.konvert.processor.exceptions.AmbiguousSourceException
import io.mcarle.konvert.processor.exceptions.IgnoredTargetException
import io.mcarle.konvert.processor.exceptions.InvalidMappingException
import io.mcarle.konvert.processor.exceptions.NotAssignableTargetException
import io.mcarle.konvert.processor.exceptions.PropertyMappingNotExistingException
import io.mcarle.konvert.processor.exceptions.UnmappedTargetException
import io.mcarle.konvert.processor.sourcedata.DefaultSourceDataExtractionStrategy
import io.mcarle.konvert.processor.sourcedata.SourceDataExtractionStrategy
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
        val singleSource = context.sources.singleOrNull()
        if (singleSource?.paramName != null && mappings.isEmpty()) {
            val existingTypeConverter = TypeConverterRegistry
                .firstOrNull {
                    it.matches(singleSource.type, context.target) && it !is AnnotatedConverter
                }

            if (existingTypeConverter != null) {
                return CodeBlock.of(
                    "return·%L",
                    existingTypeConverter.convert(singleSource.paramName, singleSource.type, context.target)
                )
            }
        }

        val targetData = targetDataExtractionStrategy.extract(resolver, context.targetClassDeclaration, visibilityContext)

        val (sourceDataList, sourceProperties) = resolveSources(mappings, context, visibilityContext, additionalSourceParameters)

        val constructor = ConstructorResolver.determineConstructor(
            targetData = targetData,
            sourceProperties = sourceProperties,
            constructorTypes = enforcedConstructorTypes
        )

        val constructorParameters = constructor.parameters

        verifyAvailableMappingsForConstructorParameters(sourceProperties, constructorParameters)

        verifyTargetExists(mappings, constructorParameters, targetData.varProperties, targetData.setter)

        reportIgnoredTargetsWithSource(
            context.functionName,
            mappings,
            sourceDataList,
            additionalSourceParameters,
            constructorParameters,
            targetData
        )

        verifyMappedTargetsAssignable(mappings, constructorParameters, targetData)

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

        verifyNoAmbiguousSource(
            sourceProperties,
            constructorParameters.mapNotNull { it.name?.asString() }
                + variablesWithoutConstructorParameters.map { it.name }
                + remainingSetters.map { it.name }
        )

        reportUnmappedTargets(
            constructor,
            sourceProperties,
            targetData,
            variablesWithoutConstructorParameters.map { it.name } + remainingSetters.map { it.name }
        )

        return MappingCodeGenerator(logger).generateMappingCode(
            context,
            sourceProperties.sortedByDescending { it.isBasedOnAnnotation },
            constructor,
            variablesWithoutConstructorParameters.map { it.property },
            remainingSetters.toList()
        )
    }

    private data class ResolvedSources(
        val sourceDataList: List<SourceDataExtractionStrategy.SourceData>,
        val sourceProperties: List<PropertyMappingInfo>,
    )

    private fun resolveSources(
        mappings: List<Mapping>,
        context: MappingContext,
        visibilityContext: MappingVisibilityContext,
        additionalSourceParameters: List<KSValueParameter>
    ): ResolvedSources {
        val sourceDataLists = context.sources.map {
            sourceDataExtractionStrategy.extract(resolver, it.classDeclaration, visibilityContext)
        }
        val sourceDataList = sourceDataLists.flatten()

        val singleSource = context.sources.singleOrNull()
        val sourceProperties = if (singleSource != null) {
            PropertyMappingResolver(logger).determinePropertyMappings(
                mappingParamName = singleSource.paramName,
                mappings = mappings,
                additionalSourceParameters = additionalSourceParameters,
                sourceDataList = sourceDataList
            )
        } else {
            PropertyMappingResolver(logger).determinePropertyMappings(
                sources = context.sources.zip(sourceDataLists) { source, sourceData ->
                    PropertyMappingResolver.SourceWithData(
                        paramName = requireNotNull(source.paramName) { "Every one of several sources must be a parameter" },
                        type = source.type,
                        sourceDataList = sourceData
                    )
                },
                mappings = mappings,
                additionalSourceParameters = additionalSourceParameters,
                extractSourceData = { sourceDataExtractionStrategy.extract(resolver, it, visibilityContext) }
            )
        }
        return ResolvedSources(sourceDataList, sourceProperties)
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

        val (sourceDataList, sourceProperties) = resolveSources(mappings, context, visibilityContext, additionalSourceParameters)

        verifyTargetExists(mappings, emptyList(), targetData.varProperties, targetData.setter)

        verifyMappedTargetsAssignable(mappings, emptyList(), targetData)

        reportIgnoredTargetsWithSource(
            context.functionName,
            mappings,
            sourceDataList,
            additionalSourceParameters,
            emptyList(),
            targetData
        )

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

        verifyNoAmbiguousSource(sourceProperties, targetProperties.map { it.name } + targetSetters.map { it.name })

        reportUnmappedUpdateTargets(
            context,
            targetData,
            sourceProperties,
            targetProperties.map { it.name } + targetSetters.map { it.name }
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
     * A constructor parameter that no source property and no mapping provides silently takes its default value, or
     * `null`. An assignable non-constructor property that is neither written nor excluded with `ignore = true` silently
     * keeps the value the constructor gives it, whichever [NonConstructorPropertiesMapping] left it unwritten.
     */
    private fun reportUnmappedTargets(
        constructor: KSFunctionDeclaration,
        sourceProperties: List<PropertyMappingInfo>,
        targetData: TargetDataExtractionStrategy.TargetData,
        writtenNonConstructorNames: List<String>
    ) {
        val policy = Configuration.unmappedTargetPolicy
        if (policy == UnmappedTargetPolicy.IGNORE) return

        val parameterNames = constructor.parameters.mapNotNull { it.name?.asString() }
        val unmappedParameters = parameterNames.filter { name -> sourceProperties.none { it.targetName == name } }
        val unmappedProperties = unmappedAssignableProperties(targetData, sourceProperties, parameterNames + writtenNonConstructorNames)

        if (unmappedParameters.isEmpty() && unmappedProperties.isEmpty()) return

        val targetClass = targetData.classDeclaration
        if (policy == UnmappedTargetPolicy.ERROR) {
            throw UnmappedTargetException(targetClass, unmappedParameters, unmappedProperties)
        }
        unmappedParameters.forEach {
            logger.warn(
                "No source for the target parameter '$it' of ${targetClass.qualifiedName?.asString()}, its default value is used",
                constructor
            )
        }
        unmappedProperties.forEach {
            logger.warn(
                "No source for the target property '$it' of ${targetClass.qualifiedName?.asString()}, its initial value is kept",
                targetClass
            )
        }
    }

    /**
     * The assignable properties of [targetData] that are neither in [writtenNames] nor excluded with `ignore = true`
     */
    private fun unmappedAssignableProperties(
        targetData: TargetDataExtractionStrategy.TargetData,
        sourceProperties: List<PropertyMappingInfo>,
        writtenNames: List<String>
    ): List<String> {
        val ignoredNames = sourceProperties.filter { it.ignore }.map { it.targetName }.toSet()
        return (targetData.varProperties.filter { it.hasAccessibleSetter() }.map { it.name } + targetData.setter.map { it.name })
            .distinct()
            .filter { it !in writtenNames && it !in ignoredNames }
    }

    /**
     * An explicit mapping onto a `var` whose setter the generated code cannot call would never be written.
     */
    private fun verifyMappedTargetsAssignable(
        mappings: List<Mapping>,
        constructorParameters: List<KSValueParameter>,
        targetData: TargetDataExtractionStrategy.TargetData
    ) {
        val parameterNames = constructorParameters.mapNotNull { it.name?.asString() }
        val setterNames = targetData.setter.map { it.name }
        val notAssignableNames = targetData.varProperties
            .filterNot { it.hasAccessibleSetter() }
            .map { it.name }
            .filter { it !in parameterNames && it !in setterNames }
        val notAssignableMappings = mappings.filter { !it.ignore && it.target in notAssignableNames }
        if (notAssignableMappings.isNotEmpty()) {
            throw NotAssignableTargetException(notAssignableMappings.map { it.target })
        }
    }

    /**
     * A `var` whose setter is private or protected, or internal to another module, cannot be written by the generated code.
     */
    private fun TargetDataExtractionStrategy.TargetVarProperty.hasAccessibleSetter(): Boolean {
        val modifiers = property.setter?.modifiers ?: return true
        return when {
            Modifier.PRIVATE in modifiers || Modifier.PROTECTED in modifiers -> false
            Modifier.INTERNAL in modifiers -> property.origin == Origin.KOTLIN
            else -> true
        }
    }

    /**
     * An assignable property of the existing instance that is neither written nor excluded with `ignore = true` silently
     * keeps its value, which [UnmappedTargetPolicy] reports as for a created target.
     */
    private fun reportUnmappedUpdateTargets(
        context: MappingContext,
        targetData: TargetDataExtractionStrategy.TargetData,
        sourceProperties: List<PropertyMappingInfo>,
        writtenTargetNames: List<String>
    ) {
        val policy = Configuration.unmappedTargetPolicy
        if (policy == UnmappedTargetPolicy.IGNORE) return

        val unmapped = unmappedAssignableProperties(targetData, sourceProperties, writtenTargetNames)
        if (unmapped.isEmpty()) return

        if (policy == UnmappedTargetPolicy.ERROR) {
            throw UnmappedTargetException(context.targetClassDeclaration, parameters = emptyList(), properties = unmapped)
        }
        unmapped.forEach {
            logger.warn(
                "No source for the target property '$it' of ${context.targetClassDeclaration.qualifiedName?.asString()}, " +
                    "${context.functionName} leaves it unchanged",
                context.targetClassDeclaration
            )
        }
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
     * An ignored target that a source property or an additional parameter would map to by name silently drops its value.
     * A source property that another mapping uses as its `source` is not considered, as it is mapped elsewhere.
     */
    private fun reportIgnoredTargetsWithSource(
        functionName: String,
        mappings: List<Mapping>,
        sourceDataList: List<SourceDataExtractionStrategy.SourceData>,
        additionalSourceParameters: List<KSValueParameter>,
        constructorParameters: List<KSValueParameter>,
        targetData: TargetDataExtractionStrategy.TargetData
    ) {
        val policy = Configuration.ignoredTargetPolicy
        if (policy == IgnoredTargetPolicy.IGNORE) return

        val sourcesOfOtherMappings = mappings.filter { !it.ignore && it.source.isNotEmpty() }.map { it.source }.toSet()
        val sourcesByTarget = mappings
            .filter { it.ignore && it.source.isEmpty() }
            .mapNotNull { mapping ->
                val targetType = targetTypeOf(mapping.target, constructorParameters, targetData)?.resolve()
                    ?: return@mapNotNull null
                val parameter = additionalSourceParameters.firstOrNull {
                    it.name?.asString() == mapping.target && targetType.isAssignableFrom(it.type.resolve())
                }
                val sourceData = sourceDataList.firstOrNull {
                    it.name == mapping.target && it.name !in sourcesOfOtherMappings && isConvertible(it.typeRef.resolve(), targetType)
                }
                when {
                    parameter != null -> mapping.target to "parameter '${mapping.target}'"
                    sourceData != null -> mapping.target to "source property '${sourceData.name}'"
                    else -> null
                }
            }
            .toMap()
        if (sourcesByTarget.isEmpty()) return

        if (policy == IgnoredTargetPolicy.ERROR) {
            throw IgnoredTargetException(functionName, sourcesByTarget)
        }
        sourcesByTarget.forEach { (target, source) ->
            logger.warn(
                "$functionName ignores the target '$target', but the $source would map to it: " +
                    "remove @Mapping(target = \"$target\", ignore = true), or map it"
            )
        }
    }

    private fun targetTypeOf(
        target: String,
        constructorParameters: List<KSValueParameter>,
        targetData: TargetDataExtractionStrategy.TargetData
    ): KSTypeReference? {
        return constructorParameters.firstOrNull { it.name?.asString() == target }?.type
            ?: targetData.varProperties.firstOrNull { it.name == target && it.hasAccessibleSetter() }?.property?.type
            ?: targetData.setter.firstOrNull { it.name == target }?.typeRef
    }

    private fun isConvertible(sourceType: KSType, targetType: KSType): Boolean {
        return TypeConverterRegistry.withAdditionallyEnabledConverters(Configuration.enableConverters) {
            any { it.matches(sourceType, targetType) }
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
            .filter { it.hasAccessibleSetter() }
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

    /**
     * With several sources, a target that properties of different sources match by name must be mapped explicitly.
     * A mapping or an additional parameter for that target takes precedence, so it is not ambiguous then.
     */
    private fun verifyNoAmbiguousSource(sourceProperties: List<PropertyMappingInfo>, targetNames: List<String>) {
        targetNames.distinct().forEach { targetName ->
            val matching = sourceProperties.filter { it.targetName == targetName }
            if (matching.any { it.isBasedOnAnnotation || it.sourceData == null }) return@forEach

            val sourcesByParam = matching.filter { it.sourcePath != null }.distinctBy { it.mappingParamName }
            if (sourcesByParam.size > 1) {
                throw AmbiguousSourceException(targetName, sourcesByParam.map { "${it.mappingParamName}.${it.sourceName}" })
            }
        }
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
