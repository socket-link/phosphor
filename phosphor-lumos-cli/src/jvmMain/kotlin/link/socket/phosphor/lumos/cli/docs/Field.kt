package link.socket.phosphor.lumos.cli.docs

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * One property of a serializable value, as its serializer writes it.
 *
 * @property name The property's name.
 * @property type Its type's simple name, `?` included: `Float`, `String?`, `SurfaceStyle`.
 * @property value What [value] held: a primitive, a string, null, or the nested value itself.
 */
internal data class Field(
    val name: String,
    val type: String,
    val value: Any?,
)

/**
 * The top-level properties of [value] in declaration order, defaults included: how the agent
 * docs read `LumosConfig()` and each material's fields without a reflection library.
 */
internal fun <T> fieldsOf(
    serializer: SerializationStrategy<T>,
    value: T,
): List<Field> = FieldRecorder().also { it.encodeSerializableValue(serializer, value) }.fields

// Records each element its serializer writes, without descending into nested values, which it
// keeps whole. Every primitive AbstractEncoder writes arrives through encodeValue.
@OptIn(ExperimentalSerializationApi::class)
private class FieldRecorder : AbstractEncoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    val fields = ArrayList<Field>()

    private var descriptor: SerialDescriptor? = null
    private var index: Int = -1

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        this.descriptor = descriptor
        this.index = index
        return true
    }

    override fun encodeValue(value: Any) = record(value)

    override fun encodeNull() = record(null)

    override fun encodeEnum(
        enumDescriptor: SerialDescriptor,
        index: Int,
    ) = record(enumDescriptor.getElementName(index))

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        encodeElement(descriptor, index)
        record(value)
    }

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?,
    ) {
        encodeElement(descriptor, index)
        record(value)
    }

    private fun record(value: Any?) {
        val owner = descriptor ?: return
        val type = owner.getElementDescriptor(index).serialName.substringAfterLast('.')
        fields += Field(owner.getElementName(index), type, value)
    }
}
