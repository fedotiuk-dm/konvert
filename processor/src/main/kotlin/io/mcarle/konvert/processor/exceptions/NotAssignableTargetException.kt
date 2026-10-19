package io.mcarle.konvert.processor.exceptions

class NotAssignableTargetException(targets: List<String>) : RuntimeException(
    "The target properties ${targets.joinToString { "`$it`" }} are not assignable: " +
        "a var whose setter is private, protected or internal to another module is never written, " +
        "and into an existing @Konverter.Target instance only a var, a setter or a val holding a non-null mutable collection or map can be mapped"
)
