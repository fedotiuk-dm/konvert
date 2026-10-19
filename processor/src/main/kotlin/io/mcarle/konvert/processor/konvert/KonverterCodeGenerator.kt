package io.mcarle.konvert.processor.konvert

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toTypeName
import io.mcarle.konvert.api.Konverter
import io.mcarle.konvert.api.NullStrategy
import io.mcarle.konvert.converter.api.classDeclaration
import io.mcarle.konvert.converter.api.config.Configuration
import io.mcarle.konvert.converter.api.config.UpdateNullStrategy
import io.mcarle.konvert.converter.api.config.konverterGenerateClass
import io.mcarle.konvert.converter.api.config.updateNullStrategy
import io.mcarle.konvert.converter.api.config.withIsolatedConfiguration
import io.mcarle.konvert.plugin.api.KonverterInjector
import io.mcarle.konvert.processor.codegen.CodeBuilder
import io.mcarle.konvert.processor.codegen.CodeGenerator
import io.mcarle.konvert.processor.codegen.MappingContext
import io.mcarle.konvert.processor.codegen.MappingSource
import io.mcarle.konvert.processor.codegen.MappingVisibilityContext
import io.mcarle.konvert.processor.codegen.SealedClassMappingCodeGenerator
import io.mcarle.konvert.processor.codegen.UpdateTarget
import io.mcarle.konvert.processor.exceptions.KonvertException
import io.mcarle.konvert.processor.validated
import java.util.ServiceLoader

object KonverterCodeGenerator {

    private val injectors by lazy {
        ServiceLoader.load(KonverterInjector::class.java, this::class.java.classLoader).toList()
    }

    fun generate(
        data: KonverterData,
        resolver: Resolver,
        environment: SymbolProcessorEnvironment
    ) = withIsolatedConfiguration(data.annotationData.options) {
        withCurrentKonverterInterface(data.konverterInterface) {
            val mapper = CodeGenerator(
                logger = environment.logger,
                resolver = resolver
            )

            val codeBuilder = retrieveCodeBuilder(
                data.konverterInterface
            )

            data.konvertData.forEach { konvertData ->
                withIsolatedConfiguration(konvertData.annotationData.options) {
                    try {
                        konvertData.sourceParameters.forEach { sourceParameter ->
                            val sourceType = sourceParameter.type.resolve()
                            if (isAlias(sourceParameter.type, sourceType)) {
                                // @Konverter annotated interface used alias for source, so the implementation should also use the same alias
                                codeBuilder.addImport(sourceType, sourceParameter.type.toString())
                            }
                        }

                        val targetClassImportName =
                            if (isAlias(konvertData.targetTypeReference, konvertData.targetType)) {
                                // @Konverter annotated interface used alias for target, so the implementation should also use the same alias
                                val alias = konvertData.targetTypeReference.toString()
                                codeBuilder.addImport(konvertData.targetType, alias)
                                alias
                            } else {
                                null
                            }

                        codeBuilder.addFunction(
                            funBuilder = FunSpec.builder(konvertData.mapFunctionName)
                                .addModifiers(KModifier.OVERRIDE)
                                .apply {
                                    if (!konvertData.isUpdate) {
                                        returns(konvertData.targetTypeReference.toTypeName())
                                    } else if (konvertData.returnsTarget) {
                                        returns(konvertData.mapKSFunctionDeclaration.returnType!!.toTypeName())
                                    }
                                }
                                .addParameters(konvertData.mapKSFunctionDeclaration.parameters.map {
                                    val builder = ParameterSpec.builder(
                                        name = it.name!!.asString(),
                                        type = it.type.toTypeName(),
                                        modifiers = emptyArray()
                                    )
                                    if (it.isVararg) {
                                        builder.addModifiers(KModifier.VARARG)
                                    }
                                    builder.build()
                                })
                                .apply {
                                    if (konvertData.isSuspend) {
                                        addModifiers(KModifier.SUSPEND)
                                    }
                                    if (!konvertData.isAbstract) {
                                        generateSuperCall(konvertData)
                                    } else {
                                        generateMappingCode(mapper, konvertData, targetClassImportName, environment.logger)
                                    }
                                },
                            priority = konvertData.priority,
                            toType = true,
                            originating = data.konverterInterface.kSClassDeclaration.containingFile
                        )
                    } catch (e: Exception) {
                        throw KonvertException(konvertData.sourceType, konvertData.targetType, e)
                    }
                }
            }
        }
    }

    private fun FunSpec.Builder.generateSuperCall(konvertData: KonvertData): FunSpec.Builder {
        return if (konvertData.hasMultipleSources) {
            addCode(
                "return·super.${konvertData.mapFunctionName}(«\n"
                    + konvertData.mapKSFunctionDeclaration.parameters.joinToString(separator = ",\n") {
                    val paramName = it.name?.asString()!!
                    "$paramName·=·$paramName"
                } + "»\n)"
            )
        } else if (konvertData.additionalParameters.isEmpty()) {
            addCode(
                "return·super.${konvertData.mapFunctionName}(${konvertData.paramName})"
            )
        } else {
            addCode(
                "return·super.${konvertData.mapFunctionName}(«\n${konvertData.paramName}·=·${konvertData.paramName},\n"
                    + konvertData.additionalParameters.joinToString(separator = ",\n") {
                    val paramName = it.name?.asString()!!
                    "$paramName·=·$paramName"
                } + "»\n)"
            )
        }
    }

    private fun FunSpec.Builder.generateMappingCode(
        mapper: CodeGenerator,
        konvertData: KonvertData,
        targetClassImportName: String?,
        logger: KSPLogger
    ): FunSpec.Builder {
        val mappings = konvertData.annotationData.mappings.asIterable()
            .validated(konvertData.mapKSFunctionDeclaration, logger)
        val context = MappingContext(
            sources = konvertData.sourceParameters.map {
                val sourceType = it.type.resolve()
                MappingSource(
                    classDeclaration = sourceType.classDeclaration()!!,
                    type = sourceType,
                    paramName = it.name!!.asString(),
                )
            },
            targetClassDeclaration = konvertData.targetClassDeclaration,
            target = konvertData.targetType,
            targetClassImportName = targetClassImportName,
            functionName = konvertData.mapKSFunctionDeclaration.qualifiedName?.asString() ?: konvertData.mapFunctionName,
        )
        val visibilityContext = MappingVisibilityContext.Declaration(konvertData.mapKSFunctionDeclaration)
        val sealedMapper = SealedClassMappingCodeGenerator(mapper)
        if (konvertData.targetParameter != null) {
            return addCode(
                mapper.generateUpdateCode(
                    mappings = mappings,
                    context = context,
                    updateTarget = UpdateTarget(
                        paramName = konvertData.targetParameter.name!!.asString(),
                        returnsTarget = konvertData.returnsTarget,
                        nullStrategy = when (konvertData.targetNulls) {
                            NullStrategy.DEFAULT -> Configuration.updateNullStrategy
                            NullStrategy.SET -> UpdateNullStrategy.SET
                            NullStrategy.IGNORE -> UpdateNullStrategy.IGNORE
                        },
                    ),
                    visibilityContext = visibilityContext,
                    additionalSourceParameters = konvertData.additionalParameters
                )
            )
        }
        return addCode(
            if (sealedMapper.isApplicable(context)) {
                sealedMapper.generateCode(
                    mappings = mappings,
                    enforcedConstructorTypes = konvertData.annotationData.constructor,
                    context = context,
                    visibilityContext = visibilityContext,
                    additionalSourceParameters = konvertData.additionalParameters
                )
            } else {
                mapper.generateCode(
                    mappings = mappings,
                    enforcedConstructorTypes = konvertData.annotationData.constructor,
                    context = context,
                    visibilityContext = visibilityContext,
                    additionalSourceParameters = konvertData.additionalParameters
                )
            }
        )
    }

    private fun isAlias(typeReference: KSTypeReference, type: KSType): Boolean {
        // Waiting for solution of https://github.com/google/ksp/issues/2391
        // to be able to identify import alias
        return typeReference.toString().takeWhile { it != '<' }.removeSuffix("?") != type.makeNotNullable().toString().takeWhile { it != '<' }
    }

    private fun retrieveCodeBuilder(
        konverterInterface: KonverterInterface
    ): CodeBuilder {
        return CodeBuilder.getOrCreate(konverterInterface.packageName, konverterInterface.simpleName) {
            if (Configuration.konverterGenerateClass) {
                TypeSpec.classBuilder("${konverterInterface.simpleName}${Konverter.KONVERTER_GENERATED_CLASS_SUFFIX}")
            } else {
                TypeSpec.objectBuilder("${konverterInterface.simpleName}${Konverter.KONVERTER_GENERATED_CLASS_SUFFIX}")
            }
                .addSuperinterface(konverterInterface.typeName)
                .also { typeBuilder ->
                    injectors.forEach {
                        it.processType(typeBuilder, konverterInterface.kSClassDeclaration)
                    }
                    if (konverterInterface.isInternal) {
                        typeBuilder.addModifiers(KModifier.INTERNAL)
                    }
                }

        }
    }

    fun toFunctionFullyQualifiedNames(data: KonverterData): List<String> {
        return data.konvertData
            .filter { it.additionalParameters.isEmpty() && !it.hasMultipleSources && !it.isUpdate } // filter out mappings with more than one parameter
            .map {
                val packageName = data.konverterInterface.packageName
                val simpleName = data.konverterInterface.simpleName + Konverter.KONVERTER_GENERATED_CLASS_SUFFIX
                val functionName = it.mapFunctionName

                if (packageName.isEmpty()) {
                    "$simpleName.$functionName"
                } else {
                    "$packageName.$simpleName.$functionName"
                }
            }
    }

}
