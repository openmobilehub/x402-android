package app.x402spike

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/**
 * Off-chain WebAuthn assertion verifier.
 *
 * Path A M1's "did this round-trip work?" gate: take an assertion produced by
 * the device passkey + the public key we extracted at creation time, recompute
 * the signed message, and verify the ECDSA signature on plain JVM. No Android
 * dependencies. The unit tests for this class run via `gradlew testDebugUnitTest`
 * and are part of the autonomous-verification loop — they pass before the phone
 * is ever plugged in.
 *
 * Why JCA built-in (`Signature.getInstance("SHA256withECDSA")`) over BouncyCastle:
 *   - Already in the JDK; zero new dependencies (BC was a web3j transitive dep
 *     that we dropped along with web3j).
 *   - Accepts DER-encoded signatures directly — no manual r/s decode for verify.
 *   - JDK SunEC + Android Conscrypt both implement P-256 ECDSA correctly.
 */
object P256Verify {

    /**
     * Verify a WebAuthn assertion against a P-256 public key.
     *
     * The signed message per WebAuthn §6.3.3 is exactly:
     *     authenticatorData || SHA-256(clientDataJSON)
     *
     * The ES256 algorithm (`alg: -7`) hashes that with SHA-256 internally, then
     * ECDSA-signs. JCA's "SHA256withECDSA" does the same hash before verify, so
     * we pass the message directly to `update(...)` (not the pre-hashed value).
     */
    fun verifyAssertion(
        publicKeyX: ByteArray,
        publicKeyY: ByteArray,
        authenticatorData: ByteArray,
        clientDataJson: ByteArray,
        signatureDer: ByteArray,
    ): Boolean {
        require(publicKeyX.size == 32) { "publicKeyX must be 32 bytes (got ${publicKeyX.size})" }
        require(publicKeyY.size == 32) { "publicKeyY must be 32 bytes (got ${publicKeyY.size})" }
        val message = authenticatorData + sha256(clientDataJson)
        val pk = ecPublicKey(publicKeyX, publicKeyY)
        return Signature.getInstance("SHA256withECDSA").apply {
            initVerify(pk)
            update(message)
        }.verify(signatureDer)
    }

    /**
     * Decode a DER-encoded ECDSA signature into its raw (r, s) integer parts,
     * each left-padded to 32 bytes (big-endian).
     *
     * DER format: 0x30 totalLen 0x02 rLen r 0x02 sLen s
     * r and s can be 31 or 32 bytes (DER strips leading zero bytes for positive
     * values), or 33 bytes (DER prepends 0x00 if the high bit of the magnitude
     * is set, to disambiguate from a negative two's-complement value).
     *
     * This is needed at signing time because the smart wallet contract on Base
     * (M2) wants raw (r, s) pairs in the WebAuthn assertion encoding, not DER.
     * For M1 it's also useful diagnostic output.
     */
    fun derToRs(der: ByteArray): Pair<ByteArray, ByteArray> {
        require(der.size >= 8) { "DER signature too short: ${der.size} bytes" }
        require(der[0] == 0x30.toByte()) { "DER signature must start with 0x30, got 0x${"%02x".format(der[0])}" }

        // Read sequence length (could be one or two bytes — DER short/long form).
        var idx = 1
        val seqLen: Int
        if ((der[idx].toInt() and 0x80) == 0) {
            seqLen = der[idx].toInt() and 0xFF
            idx += 1
        } else {
            val lenBytes = der[idx].toInt() and 0x7F
            require(lenBytes in 1..2) { "unsupported DER length encoding" }
            idx += 1
            var v = 0
            repeat(lenBytes) { v = (v shl 8) or (der[idx++].toInt() and 0xFF) }
            seqLen = v
        }
        require(idx + seqLen <= der.size) { "DER length exceeds signature size" }

        require(der[idx] == 0x02.toByte()) { "expected INTEGER tag for r" }
        idx += 1
        val rLen = der[idx].toInt() and 0xFF
        idx += 1
        val rRaw = der.copyOfRange(idx, idx + rLen)
        idx += rLen

        require(der[idx] == 0x02.toByte()) { "expected INTEGER tag for s" }
        idx += 1
        val sLen = der[idx].toInt() and 0xFF
        idx += 1
        val sRaw = der.copyOfRange(idx, idx + sLen)

        return Pair(leftPad32(rRaw), leftPad32(sRaw))
    }

    private fun leftPad32(bytes: ByteArray): ByteArray {
        // Strip leading 0x00 (DER's two's-complement disambiguator) if present.
        val stripped = if (bytes.size == 33 && bytes[0] == 0x00.toByte()) {
            bytes.copyOfRange(1, 33)
        } else {
            bytes
        }
        require(stripped.size <= 32) { "value too large for 32 bytes after strip: ${stripped.size}" }
        if (stripped.size == 32) return stripped
        val out = ByteArray(32)
        System.arraycopy(stripped, 0, out, 32 - stripped.size, stripped.size)
        return out
    }

    private fun sha256(b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(b)

    private fun ecPublicKey(x: ByteArray, y: ByteArray): PublicKey {
        // Build the P-256 ECParameterSpec from the named curve. KeyFactory.generatePublic
        // wants a fully-formed ECPublicKeySpec including curve params (it can't infer
        // the curve from just x/y).
        val params = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }
        val ecSpec = params.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, x), BigInteger(1, y))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, ecSpec))
    }
}
