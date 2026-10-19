package io.mcarle.konvert.processor.codegen

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

data class MappingContext constructor(
    val sourceClassDeclaration: KSClassDeclaration,
    val targetClassDeclaration: KSClassDeclaration,
    val source: KSType,
    val target: KSType,
    val paramName: String?,
    val targetClassImportName: String?,
    /**
     * The generated mapping function, qualified by its interface or receiver, to name it in messages
     */
    val functionName: String,
    /**
     * Generate the mapping as an expression instead of a `return` statement, e.g. for a `when` branch
     */
    val asExpression: Boolean = false,
)
