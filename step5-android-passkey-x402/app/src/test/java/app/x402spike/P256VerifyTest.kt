package app.x402spike

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * JVM unit tests for the off-chain WebAuthn assertion verifier. These run
 * via `gradlew :app:testDebugUnitTest` with no Android dependencies, no
 * connected device, and no biometric. They're the autonomous-verification
 * gate — they pass before the phone is ever plugged in.
 */
class P256VerifyTest {

    /**
     * Self-generated round-trip: build a real P-256 keypair, construct a
     * synthetic WebAuthn assertion (authenticatorData + clientDataJSON),
     * sign with JDK ECDSA, and verify via P256Verify.
     *
     * If this fails, either the verifier's message construction is wrong
     * (it should hash authenticatorData || sha256(clientDataJSON)) or the
     * public-key reconstruction from (x, y) is broken.
     */
    @Test
    fun `round-trip self-generated signature verifies`() {
        val (privateKey, publicKey) = generateP256KeyPair()
        val (x, y) = extractCoords(publicKey)

        val authenticatorData = ByteArray(37) { it.toByte() } // 37 bytes is the minimum: rpIdHash(32) + flags(1) + signCount(4)
        val clientDataJson = """{"type":"webauthn.get","challenge":"AAAA","origin":"https://test"}""".toByteArray()
        val message = authenticatorData + sha256(clientDataJson)

        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(message)
        }.sign()

        val ok = P256Verify.verifyAssertion(
            x, y, authenticatorData, clientDataJson, signature,
        )
        assertTrue("self-generated signature should verify", ok)
    }

    /**
     * Tampered authenticatorData should fail verification. Flipping any bit
     * in the signed message gives a different hash, so the signature can't
     * possibly be valid — even though it was produced honestly for the
     * original bytes.
     */
    @Test
    fun `rejects tampered authenticator data`() {
        val (privateKey, publicKey) = generateP256KeyPair()
        val (x, y) = extractCoords(publicKey)

        val authenticatorData = ByteArray(37) { it.toByte() }
        val clientDataJson = """{"type":"webauthn.get","challenge":"AAAA","origin":"https://test"}""".toByteArray()
        val message = authenticatorData + sha256(clientDataJson)

        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(message)
        }.sign()

        // Flip one bit in authenticatorData and re-verify against the original signature.
        val tampered = authenticatorData.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        val ok = P256Verify.verifyAssertion(
            x, y, tampered, clientDataJson, signature,
        )
        assertFalse("tampered authenticatorData should fail verification", ok)
    }

    /**
     * Tampered clientDataJSON should also fail. Same mechanism — the message
     * is authenticatorData || sha256(clientDataJSON), so changing the JSON
     * changes the hash.
     */
    @Test
    fun `rejects tampered client data`() {
        val (privateKey, publicKey) = generateP256KeyPair()
        val (x, y) = extractCoords(publicKey)

        val authenticatorData = ByteArray(37) { it.toByte() }
        val clientDataJson = """{"type":"webauthn.get","challenge":"AAAA","origin":"https://test"}""".toByteArray()
        val message = authenticatorData + sha256(clientDataJson)

        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(message)
        }.sign()

        val tamperedJson = """{"type":"webauthn.get","challenge":"BBBB","origin":"https://test"}""".toByteArray()
        val ok = P256Verify.verifyAssertion(
            x, y, authenticatorData, tamperedJson, signature,
        )
        assertFalse("tampered clientDataJSON should fail verification", ok)
    }

    /**
     * derToRs round-trip with a normal 32-byte r and s. The DER encoder
     * preserves the magnitude exactly when both values fit in 32 bytes
     * without the high bit set.
     */
    @Test
    fun `derToRs decodes a normal 32-byte signature`() {
        // Construct a DER signature with known 32-byte r and s.
        val r = ByteArray(32) { 0x42 }
        val s = ByteArray(32) { 0x73 }
        val der = encodeDer(r, s)

        val (decodedR, decodedS) = P256Verify.derToRs(der)
        assertArrayEquals("r should round-trip", r, decodedR)
        assertArrayEquals("s should round-trip", s, decodedS)
    }

    /**
     * derToRs with a 33-byte r (DER prepends 0x00 when the high bit of
     * the magnitude is set, to disambiguate from a negative two's-
     * complement value). The decoder must strip the leading zero.
     */
    @Test
    fun `derToRs strips leading zero from 33-byte r`() {
        // r with high bit set in the first byte → DER encodes as 33 bytes
        // with leading 0x00. The "real" magnitude is the remaining 32 bytes.
        val rMagnitude = ByteArray(32).apply {
            this[0] = 0xFF.toByte()
            for (i in 1 until 32) this[i] = i.toByte()
        }
        val rWithLeadingZero = ByteArray(33).apply {
            this[0] = 0x00
            System.arraycopy(rMagnitude, 0, this, 1, 32)
        }
        val s = ByteArray(32) { 0x55 }
        val der = encodeDerRaw(rWithLeadingZero, s)

        val (decodedR, decodedS) = P256Verify.derToRs(der)
        assertEquals("decoded r should be 32 bytes", 32, decodedR.size)
        assertEquals("decoded s should be 32 bytes", 32, decodedS.size)
        assertArrayEquals("r should match magnitude (leading zero stripped)", rMagnitude, decodedR)
        assertArrayEquals("s should round-trip", s, decodedS)
    }

    /**
     * derToRs with a value shorter than 32 bytes (DER strips leading zero
     * bytes from positive magnitudes). The decoder must left-pad to 32.
     */
    @Test
    fun `derToRs left-pads short r`() {
        // r = 31 bytes (high byte was 0x00 in the original 32-byte form)
        val shortR = ByteArray(31) { (it + 1).toByte() }
        val s = ByteArray(32) { 0x66 }
        val der = encodeDerRaw(shortR, s)

        val (decodedR, decodedS) = P256Verify.derToRs(der)
        assertEquals("decoded r should be left-padded to 32 bytes", 32, decodedR.size)
        assertEquals("first byte of r should be 0x00 (left-pad)", 0.toByte(), decodedR[0])
        for (i in 0 until 31) {
            assertEquals("byte $i", shortR[i], decodedR[i + 1])
        }
        assertArrayEquals("s should round-trip", s, decodedS)
    }

    // --- helpers ---

    private fun generateP256KeyPair(): Pair<java.security.PrivateKey, ECPublicKey> {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val pair = gen.generateKeyPair()
        return Pair(pair.private, pair.public as ECPublicKey)
    }

    private fun extractCoords(key: ECPublicKey): Pair<ByteArray, ByteArray> {
        val w = key.w
        return Pair(toFixed32(w.affineX), toFixed32(w.affineY))
    }

    private fun toFixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        // BigInteger.toByteArray() returns a two's-complement representation, so a
        // positive value with the high bit set gets a leading 0x00. Strip it.
        val stripped = if (raw.size == 33 && raw[0] == 0.toByte()) {
            raw.copyOfRange(1, 33)
        } else {
            raw
        }
        if (stripped.size == 32) return stripped
        val out = ByteArray(32)
        System.arraycopy(stripped, 0, out, 32 - stripped.size, stripped.size)
        return out
    }

    private fun sha256(b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(b)

    /**
     * Encode (r, s) as a DER ECDSA signature. Adds 0x00 prefix to either
     * if the high bit is set, per DER's two's-complement disambiguation.
     */
    private fun encodeDer(r: ByteArray, s: ByteArray): ByteArray {
        val rDer = if ((r[0].toInt() and 0x80) != 0) byteArrayOf(0x00) + r else r
        val sDer = if ((s[0].toInt() and 0x80) != 0) byteArrayOf(0x00) + s else s
        return encodeDerRaw(rDer, sDer)
    }

    /** Encode DER without the high-bit check (caller is responsible). */
    private fun encodeDerRaw(r: ByteArray, s: ByteArray): ByteArray {
        val rPart = byteArrayOf(0x02, r.size.toByte()) + r
        val sPart = byteArrayOf(0x02, s.size.toByte()) + s
        val body = rPart + sPart
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
