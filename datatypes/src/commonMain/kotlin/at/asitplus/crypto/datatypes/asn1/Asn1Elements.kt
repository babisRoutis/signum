package at.asitplus.crypto.datatypes.asn1

import io.matthewnelson.encoding.base16.Base16
import io.matthewnelson.encoding.core.Decoder.Companion.decodeToByteArray
import io.matthewnelson.encoding.core.Encoder.Companion.encodeToString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = Asn1EncodableSerializer::class)
sealed class Asn1Element(
    private val tlv: TLV,
    protected open val children: List<Asn1Element>?
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other ==null) return false
        if(other !is Asn1Element) return false
        if(tag!= other.tag) return false
        if(!content.contentEquals(other.content)) return false
        if(this is Asn1Structure && other !is Asn1Structure) return false
        if(this is Asn1Primitive && other !is Asn1Primitive) return false
        if (this is Asn1Primitive){
            return (this.content contentEquals other.content)
        }else{
            this as Asn1Structure
            other as Asn1Structure
            return children == other.children
        }
    }
    companion object {
        fun decodeFromDerHexString(derEncoded: String) = Asn1Element.parse(derEncoded.decodeToByteArray(Base16))
    }

    val encodedLength by lazy { length.encodeLength() }
    val length: Int by lazy {
        children?.fold(0) { acc, extendedTlv -> acc + extendedTlv.overallLength } ?: tlv.length
    }

    val overallLength by lazy { length + 1 + encodedLength.size }

    val content by lazy { tlv.content }

    val tag by lazy { tlv.tag }

    val derEncoded: ByteArray by lazy {
        children?.fold(byteArrayOf()) { acc, extendedTlv -> acc + extendedTlv.derEncoded }
            ?.let { byteArrayOf(tlv.tag.toByte(), *it.size.encodeLength(), *it) }
            ?: byteArrayOf(tlv.tag.toByte(), *encodedLength, *tlv.content)
    }

    override fun toString(): String {
        return "(tag=0x${byteArrayOf(tag.toByte()).encodeToString(Base16)}" +
                ", length=${length}" +
                ", overallLength=${overallLength}" +
                if (children != null) ", children=${children}" else ", content=${content.encodeToString(Base16)}" +
                        ")"
    }

    fun toDerHexString() = derEncoded.encodeToString(Base16{strict()})
    override fun hashCode(): Int {
        var result = tlv.hashCode()
        result = 31 * result + (children?.hashCode() ?: 0)
        return result
    }
}

object Asn1EncodableSerializer : KSerializer<Asn1Element> {
    override val descriptor = PrimitiveSerialDescriptor("Asn1Encodable", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Asn1Element {
        return Asn1Element.parse(decoder.decodeString().decodeToByteArray(Base16))
    }

    override fun serialize(encoder: Encoder, value: Asn1Element) {
        encoder.encodeString(value.derEncoded.encodeToString(Base16))
    }

}


sealed class Asn1Structure(tag: UByte, children: List<Asn1Element>?) :
    Asn1Element(TLV(tag, byteArrayOf()), children) {
    public override val children: List<Asn1Element>
        get() = super.children!!

    private var index = 0
    fun nextChild() = children[index++]

    fun hasMoreChildren() = children.size > index

    fun peek() = if (!hasMoreChildren()) null else children[index]
}

class Asn1Tagged(tag: UByte, val contained: List<Asn1Element>) :
    Asn1Element(TLV(tag, byteArrayOf()), contained.toList()) {
    constructor(tag: UByte, vararg contained: Asn1Element) : this(tag, contained.toList())

    override fun toString() = "Tagged" + super.toString()
}

class Asn1Sequence(children: List<Asn1Element>) : Asn1Structure(DERTags.DER_SEQUENCE, children) {
    override fun toString() = "Sequence" + super.toString()
}

class Asn1Set(children: List<Asn1Element>?) : Asn1Structure(DERTags.DER_SET, children) {
    override fun toString() = "Set" + super.toString()
}

class Asn1Primitive(tag: UByte, content: ByteArray) : Asn1Element(TLV(tag, content), null) {
    override fun toString() = "Primitive" + super.toString()
}

data class TLV(val tag: UByte, val content: ByteArray) {

    val encodedLength by lazy { length.encodeLength() }
    val length by lazy { content.size }
    val overallLength by lazy { length + 1 + encodedLength.size }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (this::class != other::class) return false

        other as TLV

        if (tag != other.tag) return false
        if (!content.contentEquals(other.content)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = tag.toInt()
        result = 31 * result + content.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "TLV(tag=0x${byteArrayOf(tag.toByte()).encodeToString(Base16)}" +
                ", length=$length" +
                ", overallLength=$overallLength" +
                ", content=${content.encodeToString(Base16)})"
    }
}