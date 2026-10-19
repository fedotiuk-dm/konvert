package io.mcarle.konvert.converter.api.config

/**
 * Defines how a function mapping into an existing instance (a parameter annotated with `@Konverter.Target`)
 * handles a `null` source value.
 *
 * @see SET
 * @see IGNORE
 */
enum class UpdateNullStrategy {

    /**
     * The target property is assigned whatever the source provides, including `null` into a nullable property.
     * A nullable source for a non-null property needs `konvert.enforce-not-null`, as in every other mapping.
     */
    SET,

    /**
     * A `null` source value leaves the target property untouched (PATCH semantics).
     * Applies to values read from source properties and to further function parameters, not to `constant` or
     * `expression` mappings, which are assigned as written.
     */
    IGNORE
}
