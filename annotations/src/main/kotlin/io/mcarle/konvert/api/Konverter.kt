package io.mcarle.konvert.api

import kotlin.reflect.KClass

/**
 * Annotate an interface with Konvert annotated functions to generate an implementation of it.
 *
 * Example:
 * ```kotlin
 * class Source(val source: Int)
 * class Target(val target: String)
 *
 * @Konverter
 * interface Mapper {
 *   @Konvert(mappings = [Mapping(source="source", target="target")])
 *   fun toTarget(source: Source): Target
 * }
 * ```
 *
 * This will generate an implementation object of the interface in the same package:
 * ```kotlin
 * object MapperImpl : Mapper {
 *      override fun toTarget(source: Source): Target = Target(target = source.source.toString())
 * }
 * ```
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class Konverter(
    val options: Array<Konfig> = []
) {

    @Retention(AnnotationRetention.RUNTIME)
    @kotlin.annotation.Target(AnnotationTarget.VALUE_PARAMETER)
    annotation class Source

    /**
     * Marks the parameter holding an existing instance to map into, instead of constructing a new one.
     *
     * The function returns `Unit` or the type of this parameter, in which case the very same instance is returned.
     * Every mapped target property must be assignable: a `var`, a Java setter, or a `val` holding a non-null mutable
     * collection or map, whose content is replaced. Target properties without a source keep their value.
     *
     * ```kotlin
     * @Konverter
     * interface PersonMapper {
     *     fun update(@Konverter.Source dto: PersonDto, @Konverter.Target entity: PersonEntity)
     * }
     * ```
     *
     * Whether a `null` source value overwrites the target property is decided by [nulls], which defaults to the option
     * `konvert.update-null-strategy`.
     */
    @Retention(AnnotationRetention.RUNTIME)
    @kotlin.annotation.Target(AnnotationTarget.VALUE_PARAMETER)
    annotation class Target(
        /**
         * What a `null` source value does in this function: [NullStrategy.SET] assigns it, [NullStrategy.IGNORE] leaves
         * the target property untouched. [NullStrategy.DEFAULT] uses the option `konvert.update-null-strategy`.
         */
        val nulls: NullStrategy = NullStrategy.DEFAULT
    )

    /**
     * This object can be used to load the generated class of an interface, which is annotated with `@Konverter`.
     */
    companion object {
        const val KONVERTER_GENERATED_CLASS_SUFFIX = "Impl"

        private val mappers: MutableMap<KClass<*>, Any> = mutableMapOf()
        private val CLASS_LOADER_LIST = mutableListOf(
            ClassLoader.getSystemClassLoader()
        )

        fun addClassLoader(classLoader: ClassLoader) {
            this.CLASS_LOADER_LIST += classLoader
        }

        fun removeClassLoader(classLoader: ClassLoader) {
            this.CLASS_LOADER_LIST -= classLoader
        }

        inline fun <reified T : Any> get(): T = get(T::class)

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> get(clazz: KClass<T>): T {
            return withCurrentClassLoaders(clazz) { classLoaders ->
                if (!mappers.containsKey(clazz)) {
                    val implFQN = "${clazz.qualifiedName}$KONVERTER_GENERATED_CLASS_SUFFIX"
                    val implClass = classLoaders.firstNotNullOfOrNull {
                        try {
                            it.loadClass(implFQN)
                        } catch (e: Exception) {
                            null
                        }
                    } ?: throw RuntimeException("Could not load the class $implFQN from provided class loaders")

                    var implInstance = implClass.declaredFields.firstOrNull {
                        it.name == "INSTANCE"
                    }?.get(null)

                    if (implInstance == null) {
                        implInstance = implClass.constructors.firstOrNull { it.parameterTypes.isEmpty() }?.newInstance()
                            ?: throw RuntimeException("Could not determine INSTANCE or empty constructor for $implClass")
                    }
                    mappers[clazz] = implInstance
                }
                return mappers[clazz] as T
            }
        }

        private inline fun <T: Any> withCurrentClassLoaders(clazz: KClass<T>, block: (List<ClassLoader>) -> T): T {
            return block(
                listOfNotNull(clazz.java.classLoader, Thread.currentThread().contextClassLoader, *CLASS_LOADER_LIST.toTypedArray())
            )
        }
    }
}
