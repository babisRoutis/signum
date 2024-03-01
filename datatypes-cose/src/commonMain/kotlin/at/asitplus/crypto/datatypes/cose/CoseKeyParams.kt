package at.asitplus.crypto.datatypes.cose

import at.asitplus.KmmResult
import at.asitplus.KmmResult.Companion.failure
import at.asitplus.KmmResult.Companion.wrap
import at.asitplus.crypto.datatypes.CryptoPublicKey
import at.asitplus.crypto.datatypes.Signum
import at.asitplus.crypto.datatypes.asn1.decodeFromDer

/**
 * Wrapper to handle parameters for different COSE public key types.
 */
sealed class CoseKeyParams {

    abstract fun toCryptoPublicKey(): KmmResult<CryptoPublicKey>

    /**
     * COSE EC public key parameters
     * Since this is used as part of a COSE-specific DTO, every property is nullable
     */
    sealed class EcKeyParams<T> : CoseKeyParams() {
        abstract val curve: CoseEllipticCurve?
        abstract val x: ByteArray?
        abstract val y: T?
        abstract val d: ByteArray?
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || other !is EcKeyParams<*>) return false

            other as EcKeyParams<*>

            if (curve != other.curve) return false
            if (x != null) {
                if (other.x == null) return false
                if (!x.contentEquals(other.x)) return false
            } else if (other.x != null) return false
            if (y != null) {
                if (other.y == null) return false
                if (!yEquals(other)) return false
            } else if (other.y != null) return false
            if (d != null) {
                if (other.d == null) return false
                if (!d.contentEquals(other.d)) return false
            } else if (other.d != null) return false

            return true
        }

        protected abstract fun yEquals(other: EcKeyParams<*>): Boolean

        protected abstract fun yHashCode(): Int

        override fun hashCode(): Int {
            var result = curve?.hashCode() ?: 0
            result = 31 * result + (x?.contentHashCode() ?: 0)
            result = 31 * result + yHashCode()
            result = 31 * result + (d?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * COSE EC public key parameters **without point compression**, i.e. the y coordinate being a ByteArray.
     * Since this is used as part of a COSE-specific DTO, every property is nullable
     */
    data class EcYByteArrayParams(
        override val curve: CoseEllipticCurve? = null,
        override val x: ByteArray? = null,
        override val y: ByteArray? = null,
        override val d: ByteArray? = null,
    ) : EcKeyParams<ByteArray>() {


        //do not remove or IT WILL break
        override fun equals(other: Any?): Boolean {
            return super.equals(other)
        }

        //do not remove or IT WILL break
        override fun hashCode(): Int {
            return super.hashCode()
        }

        override fun yEquals(other: EcKeyParams<*>): Boolean {
            if (other.y !is ByteArray) return false
            else if (!y.contentEquals(other.y as ByteArray)) return false
            return true
        }

        override fun yHashCode(): Int = y?.contentHashCode() ?: 0

        override fun toCryptoPublicKey(): KmmResult<CryptoPublicKey> {
            return runCatching {
                CryptoPublicKey.Ec(
                    curve = curve?.toEcCurve() ?: throw IllegalArgumentException("Missing or invalid curve"),
                    x = x ?: throw IllegalArgumentException("Missing x-coordinate"),
                    y = y ?: throw IllegalArgumentException("Missing y-coordinate")
                )
            }.wrap()
        }
    }

    data class EcYBoolParams(
        override val curve: CoseEllipticCurve? = null,
        override val x: ByteArray? = null,
        override val y: Signum? = null,
        override val d: ByteArray? = null,
    ) : EcKeyParams<Boolean>() {

        //do not remove or IT WILL break
        override fun equals(other: Any?): Boolean {
            return super.equals(other)
        }

        //do not remove or IT WILL break
        override fun hashCode(): Int {
            return super.hashCode()
        }

        override fun yEquals(other: EcKeyParams<*>): Boolean = y == other.y

        override fun yHashCode(): Int = y?.hashCode() ?: 0

        override fun toCryptoPublicKey(): KmmResult<CryptoPublicKey> = runCatching {
            val yFlag = y ?: throw Exception("Cannot determine key - Missing Indicator y")
            x?.let { CryptoPublicKey.Ec(curve?.toEcCurve() ?: throw Exception("Cannot determine Curve - Missing Curve"), x, yFlag) }
                ?: throw Exception("Cannot determine key - Missing x coordinate")
        }.wrap()
    }

    /**
     * COSE RSA public key params. Since this is used as part of a COSE-specific DTO, every property is nullable
     */
    data class RsaParams(
        val n: ByteArray? = null,
        val e: ByteArray? = null,
        val d: ByteArray? = null,
    ) : CoseKeyParams() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as RsaParams

            if (n != null) {
                if (other.n == null) return false
                if (!n.contentEquals(other.n)) return false
            } else if (other.n != null) return false
            if (e != null) {
                if (other.e == null) return false
                if (!e.contentEquals(other.e)) return false
            } else if (other.e != null) return false
            if (d != null) {
                if (other.d == null) return false
                if (!d.contentEquals(other.d)) return false
            } else if (other.d != null) return false

            return true
        }

        override fun hashCode(): Int {
            var result = n?.contentHashCode() ?: 0
            result = 31 * result + (e?.contentHashCode() ?: 0)
            result = 31 * result + (d?.contentHashCode() ?: 0)
            return result
        }

        override fun toCryptoPublicKey(): KmmResult<CryptoPublicKey> {
            return runCatching {
                CryptoPublicKey.Rsa(
                    n = n ?: throw IllegalArgumentException("Missing modulus n"),
                    e = e?.let { bytes -> Int.decodeFromDer(bytes) }
                        ?: throw IllegalArgumentException("Missing or invalid exponent e")
                )
            }.wrap()
        }
    }

    data class SymmKeyParams(
        val k: ByteArray,
    ) : CoseKeyParams() {
        override fun toCryptoPublicKey(): KmmResult<CryptoPublicKey> =
            failure(IllegalArgumentException("Symmetric keys do not have public component"))
    }
}