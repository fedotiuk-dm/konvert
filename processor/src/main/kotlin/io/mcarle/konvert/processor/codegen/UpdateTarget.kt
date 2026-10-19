package io.mcarle.konvert.processor.codegen

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.mcarle.konvert.converter.api.config.UpdateNullStrategy

/**
 * The existing instance a `@Konverter` function maps into, given by its parameter annotated with `@Konverter.Target`.
 */
data class UpdateTarget constructor(
    val paramName: String,
    /**
     * Whether the function returns the target instance instead of `Unit`
     */
    val returnsTarget: Boolean,
    /**
     * What a `null` source value does, resolved from `@Konverter.Target(nulls = ...)`, else `konvert.update-null-strategy`
     */
    val nullStrategy: UpdateNullStrategy,
)

/**
 * How a `val` target property can be written in place: by replacing the content of the mutable collection or map it holds.
 */
enum class ReplaceableContent(private val qualifiedName: String, val addAllFunction: String) {
    COLLECTION("kotlin.collections.MutableCollection", "addAll"),
    MAP("kotlin.collections.MutableMap", "putAll");

    companion object {
        /**
         * A nullable `val` holds no instance to write into, so it is never replaceable.
         */
        fun of(property: KSPropertyDeclaration, resolver: Resolver): ReplaceableContent? {
            if (property.isMutable) return null
            val type = property.type.resolve()
            if (type.isMarkedNullable) return null
            return entries.firstOrNull { it.starProjectedType(resolver)?.isAssignableFrom(type) == true }
        }
    }

    private fun starProjectedType(resolver: Resolver): KSType? {
        return resolver.getClassDeclarationByName(qualifiedName)?.asStarProjectedType()
    }
}
