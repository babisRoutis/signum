package at.asitplus.crypto.datatypes

import at.asitplus.crypto.datatypes.asn1.Asn1Encodable
import at.asitplus.crypto.datatypes.asn1.Asn1Primitive
import at.asitplus.crypto.datatypes.asn1.Asn1Sequence
import at.asitplus.crypto.datatypes.asn1.Asn1Set
import at.asitplus.crypto.datatypes.asn1.Asn1Tagged
import at.asitplus.crypto.datatypes.asn1.Asn1TreeBuilder
import at.asitplus.crypto.datatypes.asn1.BERTags
import at.asitplus.crypto.datatypes.asn1.DERTags.toExplicitTag
import at.asitplus.crypto.datatypes.asn1.DERTags.toImplicitTag
import at.asitplus.crypto.datatypes.asn1.KnownOIDs
import at.asitplus.crypto.datatypes.asn1.ObjectIdentifier
import at.asitplus.crypto.datatypes.asn1.asn1Sequence
import at.asitplus.crypto.datatypes.asn1.asn1Set
import at.asitplus.crypto.datatypes.asn1.decode
import at.asitplus.crypto.datatypes.asn1.decodeBitString
import at.asitplus.crypto.datatypes.asn1.decodeFromTlv
import at.asitplus.crypto.datatypes.asn1.encodeToBitString
import at.asitplus.crypto.datatypes.asn1.encodeToTlv
import at.asitplus.crypto.datatypes.asn1.readBitString
import at.asitplus.crypto.datatypes.asn1.readInt
import at.asitplus.crypto.datatypes.asn1.readOid
import at.asitplus.crypto.datatypes.asn1.readString
import at.asitplus.crypto.datatypes.asn1.readInstant
import at.asitplus.crypto.datatypes.asn1.verify
import at.asitplus.crypto.datatypes.io.ByteArrayBase64Serializer
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Very simple implementation of the meat of an X.509 Certificate:
 * The structure that gets signed
 */
@Serializable
data class TbsCertificate(
    val version: Int = 2,
    val serialNumber: ByteArray,
    val signatureAlgorithm: JwsAlgorithm,
    val issuerName: List<DistingushedName>,
    val validFrom: Instant,
    val validUntil: Instant,
    val subjectName: List<DistingushedName>,
    val publicKey: CryptoPublicKey,
    val issuerUniqueID: ByteArray? = null,
    val subjectUniqueID: ByteArray? = null,
    val extensions: List<X509CertificateExtension>? = null
) {


    private fun Asn1TreeBuilder.version(block: () -> Int) =
        apply { elements += Asn1Tagged(0u.toExplicitTag(), block().encodeToTlv()) }

    fun encodeToTlv() = asn1Sequence {
        version { version }
        append { Asn1Primitive(BERTags.INTEGER, serialNumber) }
        sigAlg { signatureAlgorithm }
        sequence { issuerName.forEach { append { it.enCodeToTlv() } } }

        sequence {
            rfc5820Time(validFrom)
            rfc5820Time(validUntil)
        }
        sequence { subjectName.forEach { append { it.enCodeToTlv() } } }

        subjectPublicKey { publicKey }

        issuerUniqueID?.let { append { Asn1Primitive(1u.toImplicitTag(), it.encodeToBitString()) } }
        subjectUniqueID?.let { append { Asn1Primitive(2u.toImplicitTag(), it.encodeToBitString()) } }

        extensions?.let {
            if (it.isNotEmpty()) {
                append {
                    Asn1Tagged(3u.toExplicitTag(),
                        asn1Sequence {
                            it.forEach { ext ->
                                append { ext.encodeToTlv() }
                            }
                        }
                    )
                }
            }
        }
    }

    private fun Asn1TreeBuilder.rfc5820Time(instant: Instant) {
        if (instant > Instant.parse("2050-01-01T00:00:00Z")) { // per RFC 5280 4.1.2.5
            generalizedTime { instant }
        } else {
            utcTime { instant }
        }
    }

    companion object {
        fun decodeFromTlv(input: Asn1Sequence) = runCatching {
            //TODO make sure to always check for superfluous data
            val version = input.nextChild().let {
                ((it as Asn1Tagged).verify(0u) as Asn1Primitive).readInt()
            }
            val serialNumber = (input.nextChild() as Asn1Primitive).decode(BERTags.INTEGER) { it }
            val sigAlg = JwsAlgorithm.decodeFromTlv(input.nextChild() as Asn1Sequence)
            val issuerNames = (input.nextChild() as Asn1Sequence).children.map {
                DistingushedName.decodeFromTlv(it as Asn1Set)
            }

            val timestamps = decodeTimestamps(input.nextChild() as Asn1Sequence)
                ?: throw IllegalArgumentException("error parsing Timestamps")
            val subject = (input.nextChild() as Asn1Sequence).children.map {
                DistingushedName.decodeFromTlv(it as Asn1Set)
            }

            val cryptoPublicKey = CryptoPublicKey.decodeFromTlv(input.nextChild() as Asn1Sequence)

            val issuerUniqueID = input.peek()?.let { next ->
                if (next.tag == 1u.toImplicitTag()) {
                    (input.nextChild() as Asn1Primitive).decode(1u.toImplicitTag()) { decodeBitString(it) }
                } else null
            }

            val subjectUniqueID = input.peek()?.let { next ->
                if (next.tag == 2u.toImplicitTag()) {
                    (input.nextChild() as Asn1Primitive).decode(2u.toImplicitTag()) { decodeBitString(it) }
                } else null
            }
            val extensions = if (input.hasMoreChildren()) {
                ((input.nextChild() as Asn1Tagged).verify(3u) as Asn1Sequence).children.map {
                    X509CertificateExtension.decodeFromTlv(it as Asn1Sequence)
                }
            } else null

            if (input.hasMoreChildren()) throw IllegalArgumentException("Superfluous Data in Certificate Structure")

            TbsCertificate(
                version = version,
                serialNumber = serialNumber,
                signatureAlgorithm = sigAlg,
                issuerName = issuerNames,
                validFrom = timestamps.first,
                validUntil = timestamps.second,
                subjectName = subject,
                publicKey = cryptoPublicKey,
                issuerUniqueID = issuerUniqueID,
                subjectUniqueID = subjectUniqueID,
                extensions = extensions,
            )
        }.getOrElse { throw if (it is IllegalArgumentException) it else IllegalArgumentException(it) }

        private fun decodeTimestamps(input: Asn1Sequence): Pair<Instant, Instant>? = runCatching {
            val firstInstant = (input.nextChild() as Asn1Primitive).readInstant()
            val secondInstant = (input.nextChild() as Asn1Primitive).readInstant()
            if (input.hasMoreChildren()) throw IllegalArgumentException("Superfluous content in Validity")
            return Pair(firstInstant, secondInstant)
        }.getOrNull()
    }
}

//TODO auto-sanitize and/or reduce
@Serializable
sealed class Asn1String() {
    abstract val tag: UByte
    abstract val value: String

    @Serializable
    @SerialName("UTF8String")
    class UTF8(override val value: String) : Asn1String() {
        override val tag = BERTags.UTF8_STRING
    }

    @Serializable
    @SerialName("PrintableString")
    class Printable(override val value: String) : Asn1String() {
        override val tag = BERTags.PRINTABLE_STRING
    }

    fun encodeToTlv() = Asn1Primitive(tag, value.encodeToByteArray())
}

@Serializable
sealed class DistingushedName() {
    abstract val oid: ObjectIdentifier
    abstract val value: Asn1Encodable

    @Serializable
    @SerialName("CN")
    class CommonName(override val value: Asn1Encodable) : DistingushedName() {
        override val oid = OID

        constructor(str: Asn1String) : this(Asn1Primitive(str.tag, str.value.encodeToByteArray()))

        companion object {
            val OID = KnownOIDs.commonName
        }
    }

    @Serializable
    @SerialName("C")
    class Country(override val value: Asn1Encodable) : DistingushedName() {
        override val oid = OID

        constructor(str: Asn1String) : this(Asn1Primitive(str.tag, str.value.encodeToByteArray()))

        companion object {
            val OID = KnownOIDs.countryName
        }
    }

    @Serializable
    @SerialName("O")
    class Organization(override val value: Asn1Encodable) : DistingushedName() {
        override val oid = OID

        constructor(str: Asn1String) : this(Asn1Primitive(str.tag, str.value.encodeToByteArray()))

        companion object {
            val OID = KnownOIDs.organizationName
        }
    }

    @Serializable
    @SerialName("OU")
    class OrganizationalUnit(override val value: Asn1Encodable) : DistingushedName() {
        override val oid = OID

        constructor(str: Asn1String) : this(Asn1Primitive(str.tag, str.value.encodeToByteArray()))

        companion object {
            val OID = KnownOIDs.organizationalUnitName
        }
    }

    @Serializable
    @SerialName("?")
    class Other(override val oid: ObjectIdentifier, override val value: Asn1Encodable) : DistingushedName() {
        constructor(oid: ObjectIdentifier, str: Asn1String) : this(
            oid,
            Asn1Primitive(str.tag, str.value.encodeToByteArray())
        )
    }

    fun enCodeToTlv() = asn1Set {
        sequence {
            oid { oid }
            append { value }
        }
    }

    companion object {
        fun decodeFromTlv(input: Asn1Set): DistingushedName {
            if (input.children.size != 1) throw IllegalArgumentException("Invalid Subject Structure")
            val sequence = input.nextChild() as Asn1Sequence
            val oid = (sequence.nextChild() as Asn1Primitive).readOid()
            if (oid.nodes.size >= 3 && oid.nodes[0] == 2u && oid.nodes[1] == 5u && oid.nodes[2] == 4u) {
                val asn1String = sequence.nextChild() as Asn1Primitive
                val str = (asn1String).readString()
                if (sequence.hasMoreChildren()) throw IllegalArgumentException("Superfluous elements in RDN")
                return when (oid) {
                    CommonName.OID -> CommonName(str)
                    Country.OID -> Country(str)
                    Organization.OID -> Organization(str)
                    OrganizationalUnit.OID -> OrganizationalUnit(str)
                    else -> Other(oid, asn1String)
                }

            }
            return Other(oid, sequence.nextChild())
                .also { if (sequence.hasMoreChildren()) throw IllegalArgumentException("Superfluous elements in RDN") }
        }
    }
}

@Serializable
data class X509CertificateExtension(
    val id: ObjectIdentifier, val critical: Boolean = false,
    @Serializable(with = ByteArrayBase64Serializer::class) val value: ByteArray
) {

    fun encodeToTlv() = asn1Sequence {
        oid { id }
        if (critical) bool { true }
        octetString { value }
    }

    companion object {

        fun decodeFromTlv(src: Asn1Sequence): X509CertificateExtension {

            val id = (src.children[0] as Asn1Primitive).readOid()
            val critical =
                if (src.children[1].tag == BERTags.BOOLEAN) (src.children[1] as Asn1Primitive).content[0] == 0xff.toByte() else false

            val value = (src.children.last() as Asn1Primitive).decode(BERTags.OCTET_STRING) { it }
            return X509CertificateExtension(id, critical, value)
        }

    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as X509CertificateExtension

        if (id != other.id) return false
        if (critical != other.critical) return false
        if (!value.contentEquals(other.value)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + critical.hashCode()
        result = 31 * result + value.contentHashCode()
        return result
    }
}

/**
 * Very simple implementation of an X.509 Certificate
 */
@Serializable
data class X509Certificate(
    val tbsCertificate: TbsCertificate,
    val signatureAlgorithm: JwsAlgorithm,
    @Serializable(with = ByteArrayBase64Serializer::class)
    val signature: ByteArray
) {

    fun encodeToTlv() = asn1Sequence {
        tbsCertificate { tbsCertificate }
        sigAlg { signatureAlgorithm }
        bitString { signature }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as X509Certificate

        if (tbsCertificate != other.tbsCertificate) return false
        if (signatureAlgorithm != other.signatureAlgorithm) return false
        if (!signature.contentEquals(other.signature)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = tbsCertificate.hashCode()
        result = 31 * result + signatureAlgorithm.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }

    companion object {

        fun decodeFromTlv(src: Asn1Sequence): X509Certificate {
            val tbs = TbsCertificate.decodeFromTlv(src.nextChild() as Asn1Sequence)
            val sigAlg = JwsAlgorithm.decodeFromTlv(src.nextChild() as Asn1Sequence)
            val signature = (src.nextChild() as Asn1Primitive).readBitString()
            if (src.hasMoreChildren()) throw IllegalArgumentException("Superfluous structure in Certificate Structure")
            return X509Certificate(tbs, sigAlg, signature)
        }
    }
}
