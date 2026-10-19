package io.mcarle.konvert.processor.exceptions

class NotAssignableTargetException(targets: List<String>) : RuntimeException(
    "The target properties ${targets.joinToString { "`$it`" }} of the @Konverter.Target instance are not assignable: " +
        "only a var, a setter or a val holding a non-null mutable collection or map can be mapped into an existing instance"
)
