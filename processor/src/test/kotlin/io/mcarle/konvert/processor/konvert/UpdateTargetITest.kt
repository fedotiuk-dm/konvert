package io.mcarle.konvert.processor.konvert

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.mcarle.konvert.converter.EnumToEnumConverter
import io.mcarle.konvert.converter.IterableToListConverter
import io.mcarle.konvert.converter.IterableToMutableListConverter
import io.mcarle.konvert.converter.MapToMutableMapConverter
import io.mcarle.konvert.converter.SameTypeConverter
import io.mcarle.konvert.converter.api.TypeConverterRegistry
import io.mcarle.konvert.converter.api.config.NON_CONSTRUCTOR_PROPERTIES_MAPPING_OPTION
import io.mcarle.konvert.converter.api.config.NonConstructorPropertiesMapping
import io.mcarle.konvert.converter.api.config.UPDATE_NULL_STRATEGY_OPTION
import io.mcarle.konvert.converter.api.config.UpdateNullStrategy
import io.mcarle.konvert.processor.KonverterITest
import io.mcarle.konvert.processor.exceptions.NotAssignableTargetException
import io.mcarle.konvert.processor.exceptions.NotNullOperatorNotEnabledException
import io.mcarle.konvert.processor.generatedSourceFor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCompilerApi::class)
class UpdateTargetITest : KonverterITest() {

    @Test
    fun `set is the default update null strategy`() {
        assertEquals(UpdateNullStrategy.SET, UPDATE_NULL_STRATEGY_OPTION.defaultValue)
    }

    @Test
    fun `unit returning update assigns the var properties of the given instance`() {
        val (compilation, result) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class UpdateTierRequest(val name: String, val level: Int)
class TierEntity(val id: Long, var name: String) {
    var level: Int = 0
    var createdBy: String = "system"
}

@Konverter
interface TierMapper {
    fun update(@Konverter.Source request: UpdateTierRequest, @Konverter.Target entity: TierEntity)
}

fun runCheck(): String {
    val entity = TierEntity(id = 7, name = "old")
    TierMapperImpl.update(UpdateTierRequest(name = "gold", level = 3), entity)
    return "${'$'}{entity.id} ${'$'}{entity.name} ${'$'}{entity.level} ${'$'}{entity.createdBy}"
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("TierMapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object TierMapperImpl : TierMapper {
              override fun update(request: UpdateTierRequest, entity: TierEntity) {
                entity.name = request.name
                entity.level = request.level
              }
            }
            """.trimIndent(),
            mapperCode
        )
        assertEquals("7 gold 3 system", result.runCheck())
    }

    @Test
    fun `the source parameter needs no annotation when it is the only other parameter`() {
        val (compilation) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class Source(val name: String)
class Target {
    var name: String = ""
}

@Konverter
interface Mapper {
    fun update(source: Source, @Konverter.Target target: Target)
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun update(source: Source, target: Target) {
                target.name = source.name
              }
            }
            """.trimIndent(),
            mapperCode
        )
    }

    @Test
    fun `self returning update returns the given instance`() {
        val (compilation, result) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class Source(val name: String)
class Target {
    var name: String = ""
}

@Konverter
interface Mapper {
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target): Target
}

fun runCheck(): String {
    val target = Target()
    val returned = MapperImpl.update(Source("new"), target)
    return "${'$'}{returned === target} ${'$'}{target.name}"
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun update(source: Source, target: Target): Target {
                target.name = source.name
                return target
              }
            }
            """.trimIndent(),
            mapperCode
        )
        assertEquals("true new", result.runCheck())
    }

    @Test
    fun `an update function is not offered as a type converter`() {
        compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class Source(val name: String)
class Target {
    var name: String = ""
}

@Konverter
interface Mapper {
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target)
}
                """.trimIndent()
            )
        )

        assertTrue(TypeConverterRegistry.filterIsInstance<KonvertTypeConverter>().none { it.mapFunctionName == "update" })
    }

    @Test
    fun `set strategy assigns null into a nullable property`() {
        val (compilation, result) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class Source(val description: String?)
class Target {
    var description: String? = "old"
}

@Konverter
interface Mapper {
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target)
}

fun runCheck(): String {
    val target = Target()
    MapperImpl.update(Source(null), target)
    return "${'$'}{target.description}"
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun update(source: Source, target: Target) {
                target.description = source.description
              }
            }
            """.trimIndent(),
            mapperCode
        )
        assertEquals("null", result.runCheck())
    }

    @Test
    fun `set strategy needs enforce-not-null to map a nullable source into a non-null property`() {
        val (_, result) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            expectResultCode = KotlinCompilation.ExitCode.INTERNAL_ERROR,
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class Source(val name: String?)
class Target {
    var name: String = ""
}

@Konverter
interface Mapper {
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target)
}
                """.trimIndent()
            )
        )

        assertContains(result.messages, NotNullOperatorNotEnabledException::class.qualifiedName!!)
    }

    @Test
    fun `set strategy with enforce-not-null asserts a nullable source for a non-null property`() {
        enforceNotNull = true
        val (compilation) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class Source(val name: String?)
class Target {
    var name: String = ""
}

@Konverter
interface Mapper {
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target)
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun update(source: Source, target: Target) {
                target.name = source.name!!
              }
            }
            """.trimIndent(),
            mapperCode
        )
    }

    @Test
    fun `ignore strategy leaves a property untouched when the source value is null`() {
        val (compilation, result) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            options = mapOf(UPDATE_NULL_STRATEGY_OPTION.key to UpdateNullStrategy.IGNORE.name),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class PatchRequest(val name: String?, val description: String?, val level: Int)
class Target {
    var name: String = "old name"
    var description: String? = "old description"
    var level: Int = 0
}

@Konverter
interface Mapper {
    fun patch(@Konverter.Source request: PatchRequest, @Konverter.Target target: Target)
}

fun runCheck(): String {
    val target = Target()
    MapperImpl.patch(PatchRequest(name = null, description = null, level = 2), target)
    val first = "${'$'}{target.name}|${'$'}{target.description}|${'$'}{target.level}"
    MapperImpl.patch(PatchRequest(name = "new name", description = "new description", level = 3), target)
    return first + " " + "${'$'}{target.name}|${'$'}{target.description}|${'$'}{target.level}"
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun patch(request: PatchRequest, target: Target) {
                request.name?.let {
                  target.name = it
                }
                request.description?.let {
                  target.description = it
                }
                target.level = request.level
              }
            }
            """.trimIndent(),
            mapperCode
        )
        assertEquals("old name|old description|2 new name|new description|3", result.runCheck())
    }

    @Test
    fun `konfig on the function overrides the update null strategy of the interface`() {
        val (compilation) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konfig
import io.mcarle.konvert.api.Konvert
import io.mcarle.konvert.api.Konverter

class Source(val description: String?)
class Target {
    var description: String? = null
}

@Konverter(options = [Konfig(key = "${UPDATE_NULL_STRATEGY_OPTION.key}", value = "IGNORE")])
interface Mapper {
    fun patch(@Konverter.Source source: Source, @Konverter.Target target: Target)

    @Konvert(options = [Konfig(key = "${UPDATE_NULL_STRATEGY_OPTION.key}", value = "SET")])
    fun replace(@Konverter.Source source: Source, @Konverter.Target target: Target)
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun patch(source: Source, target: Target) {
                source.description?.let {
                  target.description = it
                }
              }

              override fun replace(source: Source, target: Target) {
                target.description = source.description
              }
            }
            """.trimIndent(),
            mapperCode
        )
    }

    @Test
    fun `the nulls attribute of the target annotation overrides konfig and the global option`() {
        val (compilation, result) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            options = mapOf(UPDATE_NULL_STRATEGY_OPTION.key to UpdateNullStrategy.IGNORE.name),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konfig
import io.mcarle.konvert.api.Konvert
import io.mcarle.konvert.api.Konverter
import io.mcarle.konvert.api.NullStrategy

class Source(val description: String?)
class Target {
    var description: String? = "old"
}

@Konverter(options = [Konfig(key = "${UPDATE_NULL_STRATEGY_OPTION.key}", value = "IGNORE")])
interface Mapper {
    fun replace(@Konverter.Source source: Source, @Konverter.Target(nulls = NullStrategy.SET) target: Target)

    @Konvert(options = [Konfig(key = "${UPDATE_NULL_STRATEGY_OPTION.key}", value = "SET")])
    fun patch(@Konverter.Source source: Source, @Konverter.Target(nulls = NullStrategy.IGNORE) target: Target)
}

fun runCheck(): String {
    val patched = Target()
    MapperImpl.patch(Source(description = null), patched)
    val replaced = Target()
    MapperImpl.replace(Source(description = null), replaced)
    return "${'$'}{patched.description}|${'$'}{replaced.description}"
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun replace(source: Source, target: Target) {
                target.description = source.description
              }

              override fun patch(source: Source, target: Target) {
                source.description?.let {
                  target.description = it
                }
              }
            }
            """.trimIndent(),
            mapperCode
        )
        assertEquals("old|null", result.runCheck())
    }

    @Test
    fun `the default nulls attribute uses konfig over the global option`() {
        val (compilation) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            options = mapOf(UPDATE_NULL_STRATEGY_OPTION.key to UpdateNullStrategy.IGNORE.name),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konfig
import io.mcarle.konvert.api.Konvert
import io.mcarle.konvert.api.Konverter
import io.mcarle.konvert.api.NullStrategy

class Source(val description: String?)
class Target {
    var description: String? = null
}

@Konverter(options = [Konfig(key = "${UPDATE_NULL_STRATEGY_OPTION.key}", value = "SET")])
interface Mapper {
    fun replace(@Konverter.Source source: Source, @Konverter.Target(nulls = NullStrategy.DEFAULT) target: Target)

    @Konvert(options = [Konfig(key = "${UPDATE_NULL_STRATEGY_OPTION.key}", value = "IGNORE")])
    fun patch(@Konverter.Source source: Source, @Konverter.Target target: Target)
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun replace(source: Source, target: Target) {
                target.description = source.description
              }

              override fun patch(source: Source, target: Target) {
                source.description?.let {
                  target.description = it
                }
              }
            }
            """.trimIndent(),
            mapperCode
        )
    }

    @Test
    fun `the default nulls attribute uses the global option without konfig`() {
        val (compilation) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            options = mapOf(UPDATE_NULL_STRATEGY_OPTION.key to UpdateNullStrategy.IGNORE.name),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter
import io.mcarle.konvert.api.NullStrategy

class Source(val description: String?)
class Target {
    var description: String? = null
}

@Konverter
interface Mapper {
    fun patch(@Konverter.Source source: Source, @Konverter.Target(nulls = NullStrategy.DEFAULT) target: Target)
}
                """.trimIndent()
            )
        )

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun patch(source: Source, target: Target) {
                source.description?.let {
                  target.description = it
                }
              }
            }
            """.trimIndent(),
            compilation.generatedSourceFor("MapperKonverter.kt")
        )
    }

    @Test
    fun `without the nulls attribute, konfig and the global option a null is set`() {
        val (compilation) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class Source(val description: String?)
class Target {
    var description: String? = null
}

@Konverter
interface Mapper {
    fun replace(@Konverter.Source source: Source, @Konverter.Target target: Target)
}
                """.trimIndent()
            )
        )

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun replace(source: Source, target: Target) {
                target.description = source.description
              }
            }
            """.trimIndent(),
            compilation.generatedSourceFor("MapperKonverter.kt")
        )
    }

    @Test
    fun `a nullable source leaves the target untouched when it is null`() {
        val (compilation, result) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class Source(val name: String)
class Target {
    var name: String = "old"
}

@Konverter
interface Mapper {
    fun update(@Konverter.Source source: Source?, @Konverter.Target target: Target): Target
}

fun runCheck(): String = MapperImpl.update(null, Target()).name
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun update(source: Source?, target: Target): Target {
                if (source != null) {
                  target.name = source.name
                }
                return target
              }
            }
            """.trimIndent(),
            mapperCode
        )
        assertEquals("old", result.runCheck())
    }

    @Test
    fun `a renamed source and an ignored target work as in a constructing mapping`() {
        val (compilation) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konvert
import io.mcarle.konvert.api.Konverter
import io.mcarle.konvert.api.Mapping

class Source(val fullName: String, val level: Int, val note: String)
class Target {
    var name: String = ""
    var level: Int = 0
    var note: String = ""
}

@Konverter
interface Mapper {
    @Konvert(mappings = [
        Mapping(source = "fullName", target = "name"),
        Mapping(target = "note", ignore = true),
    ])
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target)
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun update(source: Source, target: Target) {
                target.name = source.fullName
                target.level = source.level
              }
            }
            """.trimIndent(),
            mapperCode
        )
    }

    @Test
    fun `explicit non-constructor mapping mode only writes mapped targets`() {
        val (compilation) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            options = mapOf(NON_CONSTRUCTOR_PROPERTIES_MAPPING_OPTION.key to NonConstructorPropertiesMapping.EXPLICIT.name),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konvert
import io.mcarle.konvert.api.Konverter
import io.mcarle.konvert.api.Mapping

class Source(val fullName: String, val level: Int)
class Target {
    var name: String = ""
    var level: Int = 0
}

@Konverter
interface Mapper {
    @Konvert(mappings = [Mapping(source = "fullName", target = "name")])
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target)
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun update(source: Source, target: Target) {
                target.name = source.fullName
              }
            }
            """.trimIndent(),
            mapperCode
        )
    }

    @Test
    fun `an enum property is converted`() {
        val (compilation) = compileWith(
            enabledConverters = listOf(SameTypeConverter(), EnumToEnumConverter()),
            options = mapOf(UPDATE_NULL_STRATEGY_OPTION.key to UpdateNullStrategy.IGNORE.name),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

enum class Status { ACTIVE, PAUSED }
enum class StatusEntity { ACTIVE, PAUSED }

class Source(val status: Status, val previousStatus: Status?)
class Target {
    var status: StatusEntity = StatusEntity.PAUSED
    var previousStatus: StatusEntity = StatusEntity.PAUSED
}

@Konverter
interface Mapper {
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target)
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun update(source: Source, target: Target) {
                target.status = when (source.status) {
                  Status.ACTIVE -> StatusEntity.ACTIVE
                  Status.PAUSED -> StatusEntity.PAUSED
                }
                source.previousStatus?.let {
                  target.previousStatus = when (it) {
                    Status.ACTIVE -> StatusEntity.ACTIVE
                    Status.PAUSED -> StatusEntity.PAUSED
                  }
                }
              }
            }
            """.trimIndent(),
            mapperCode
        )
    }

    @Test
    fun `a nested object property is assigned a newly mapped instance`() {
        val (compilation) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class AddressDto(val street: String)
class Address(val street: String)
class Source(val address: AddressDto)
class Target {
    var address: Address? = null
}

@Konverter
interface Mapper {
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target)
    fun toAddress(dto: AddressDto): Address
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun update(source: Source, target: Target) {
                target.address = this.toAddress(dto = source.address)
              }

              override fun toAddress(dto: AddressDto): Address = Address(
                street = dto.street
              )
            }
            """.trimIndent(),
            mapperCode
        )
    }

    @Test
    fun `a mapped val property is not assignable`() {
        val (_, result) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            expectResultCode = KotlinCompilation.ExitCode.INTERNAL_ERROR,
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konvert
import io.mcarle.konvert.api.Konverter
import io.mcarle.konvert.api.Mapping

class Source(val code: String)
class Target(val id: String)

@Konverter
interface Mapper {
    @Konvert(mappings = [Mapping(source = "code", target = "id")])
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target)
}
                """.trimIndent()
            )
        )

        assertContains(result.messages, NotAssignableTargetException::class.qualifiedName!!)
        assertContains(result.messages, "`id`")
    }

    @Test
    fun `a var with a non-public setter is skipped, and mapping it explicitly is not assignable`() {
        val code = { mapping: String ->
            SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konvert
import io.mcarle.konvert.api.Konverter
import io.mcarle.konvert.api.Mapping

class Source(val id: Long, val name: String)
abstract class BaseEntity {
    var id: Long? = null
        protected set
}
class Target : BaseEntity() {
    var name: String = ""
}

@Konverter
interface Mapper {
    $mapping
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target)
}
                    """.trimIndent()
            )
        }

        val (compilation) = compileWith(enabledConverters = listOf(SameTypeConverter()), code = code(""))
        val generated = compilation.generatedSourceFor("MapperKonverter.kt")
        assertContains(generated, "target.name = source.name")
        assertFalse(generated.contains("target.id"))

        val (_, result) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            expectResultCode = KotlinCompilation.ExitCode.INTERNAL_ERROR,
            code = code("@Konvert(mappings = [Mapping(source = \"id\", target = \"id\")])")
        )
        assertContains(result.messages, NotAssignableTargetException::class.qualifiedName!!)
        assertContains(result.messages, "`id`")
    }

    @Test
    fun `an unmapped val property and a constructor parameter are skipped`() {
        val (compilation) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class Source(val id: String, val secret: String, val name: String)
class Target(val id: String, secret: String) {
    var name: String = secret
}

@Konverter
interface Mapper {
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target)
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun update(source: Source, target: Target) {
                target.name = source.name
              }
            }
            """.trimIndent(),
            mapperCode
        )
    }

    @Test
    fun `a var collection is assigned a new collection, a val mutable collection or map gets its content replaced`() {
        val (compilation, result) = compileWith(
            enabledConverters = listOf(
                SameTypeConverter(),
                IterableToListConverter(),
                IterableToMutableListConverter(),
                MapToMutableMapConverter()
            ),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class Source(val tags: List<String>, val aliases: MutableList<String>, val labels: Map<String, String>, val roles: List<String>)
class Target {
    var tags: List<String> = emptyList()
    val aliases: MutableList<String> = mutableListOf("old")
    val labels: MutableMap<String, String> = mutableMapOf("old" to "old")
    val roles: List<String> = listOf("old")
}

@Konverter
interface Mapper {
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target)
}

fun runCheck(): String {
    val target = Target()
    val aliasesBefore = target.aliases
    MapperImpl.update(Source(listOf("a"), mutableListOf("b", "c"), mapOf("k" to "v"), listOf("admin")), target)
    val first = "${'$'}{target.tags} ${'$'}{target.aliases} ${'$'}{target.aliases === aliasesBefore} ${'$'}{target.labels} ${'$'}{target.roles}"
    MapperImpl.update(Source(listOf("a"), target.aliases, mapOf("k" to "v"), listOf("admin")), target)
    return first + " " + "${'$'}{target.aliases}"
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun update(source: Source, target: Target) {
                target.tags = source.tags
                (source.aliases).let { newAliases ->
                  if (newAliases !== target.aliases) {
                    target.aliases.clear()
                    target.aliases.addAll(newAliases)
                  }
                }
                (source.labels.toMutableMap()).let { newLabels ->
                  if (newLabels !== target.labels) {
                    target.labels.clear()
                    target.labels.putAll(newLabels)
                  }
                }
              }
            }
            """.trimIndent(),
            mapperCode
        )
        assertEquals("[a] [b, c] true {k=v} [old] [b, c]", result.runCheck())
    }

    @Test
    fun `an additional parameter is assigned to the same named target property`() {
        val (compilation) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            options = mapOf(UPDATE_NULL_STRATEGY_OPTION.key to UpdateNullStrategy.IGNORE.name),
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class Source(val name: String)
class Target {
    var name: String = ""
    var updatedBy: String = ""
    var note: String? = null
}

@Konverter
interface Mapper {
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target, updatedBy: String, note: String?)
}
                """.trimIndent()
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            import kotlin.String

            public object MapperImpl : Mapper {
              override fun update(
                source: Source,
                target: Target,
                updatedBy: String,
                note: String?,
              ) {
                target.name = source.name
                target.updatedBy = updatedBy
                note?.let {
                  target.note = it
                }
              }
            }
            """.trimIndent(),
            mapperCode
        )
    }

    @Test
    fun `a java setter is written`() {
        val (compilation) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            code = arrayOf(
                SourceFile.java(
                    name = "Target.java",
                    contents =
                        """
public class Target {
    private String name;
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
                    """.trimIndent()
                ),
                SourceFile.kotlin(
                    name = "TestCode.kt",
                    contents =
                        """
import io.mcarle.konvert.api.Konverter

class Source(val name: String)

@Konverter
interface Mapper {
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target)
}
                    """.trimIndent()
                )
            )
        )
        val mapperCode = compilation.generatedSourceFor("MapperKonverter.kt")
        println(mapperCode)

        assertSourceEquals(
            """
            public object MapperImpl : Mapper {
              override fun update(source: Source, target: Target) {
                target.name = source.name
              }
            }
            """.trimIndent(),
            mapperCode
        )
    }

    @Test
    fun `an update function must return unit or the target type`() {
        val (_, result) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            expectResultCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class Source(val name: String)
class Target {
    var name: String = ""
}

@Konverter
interface Mapper {
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target): Source
}
                """.trimIndent()
            )
        )

        assertContains(result.messages, "must return Unit or the type of that parameter")
    }

    @Test
    fun `the target parameter must not be nullable`() {
        val (_, result) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            expectResultCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class Source(val name: String)
class Target {
    var name: String = ""
}

@Konverter
interface Mapper {
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target?)
}
                """.trimIndent()
            )
        )

        assertContains(result.messages, "The @Konverter.Target parameter 'target' must not be nullable")
    }

    @Test
    fun `only one parameter can be the target`() {
        val (_, result) = compileWith(
            enabledConverters = listOf(SameTypeConverter()),
            expectResultCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
            code = SourceFile.kotlin(
                name = "TestCode.kt",
                contents =
                    """
import io.mcarle.konvert.api.Konverter

class Source(val name: String)
class Target {
    var name: String = ""
}

@Konverter
interface Mapper {
    fun update(@Konverter.Source source: Source, @Konverter.Target target: Target, @Konverter.Target other: Target)
}
                """.trimIndent()
            )
        )

        assertContains(result.messages, "multiple parameters were annotated with @Konverter.Target")
    }

    private fun JvmCompilationResult.runCheck(): String {
        return classLoader.loadClass("TestCodeKt").getMethod("runCheck").invoke(null) as String
    }
}
