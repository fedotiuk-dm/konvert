package io.mcarle.konvert.api

/**
 * What a `null` source value does in a function mapping into an existing instance, chosen per function with
 * [Konverter.Target.nulls].
 */
enum class NullStrategy {

    /**
     * Use the option `konvert.update-null-strategy`, as configured for the function, its `@Konverter` interface or
     * globally (default: [SET]).
     */
    DEFAULT,

    /**
     * The target property is assigned whatever the source provides, including `null` into a nullable property,
     * e.g. when writing a validated full value.
     */
    SET,

    /**
     * A `null` source value leaves the target property untouched, e.g. for a PATCH request. Applies to values read from
     * source properties and to further function parameters, not to `constant` or `expression` mappings.
     */
    IGNORE
}
