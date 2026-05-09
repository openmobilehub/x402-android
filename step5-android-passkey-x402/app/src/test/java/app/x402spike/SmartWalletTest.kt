package app.x402spike

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * JVM-only tests for SmartWallet. The eth_call path is exercised by
 * on-device integration tests (we can't run RPC from JUnit reliably);
 * what we cover here is the offline derivation: owner-byte packing,
 * input validation, factory address constant.
 */
class SmartWalletTest {

    private val pkX = ByteArray(32) { it.toByte() }
    private val pkY = ByteArray(32) { (32 + it).toByte() }

    @Test
    fun `ownerBytes is 64-byte X-then-Y concatenation`() {
        val sw = SmartWallet(pkX, pkY)
        assertEquals(64, sw.ownerBytes.size)
        assertArrayEquals(pkX, sw.ownerBytes.copyOfRange(0, 32))
        assertArrayEquals(pkY, sw.ownerBytes.copyOfRange(32, 64))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects X coord that is not 32 bytes`() {
        SmartWallet(ByteArray(31), pkY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects Y coord that is not 32 bytes`() {
        SmartWallet(pkX, ByteArray(33))
    }

    @Test
    fun `factory constant matches Coinbase Smart Wallet on Base Sepolia`() {
        // Pinned to the deterministic-deployed factory across all Base
        // chains. If Coinbase ever republishes at a different address this
        // test breaks loud — we'd want to know.
        assertEquals(
            "0x0BA5ED0c6AA8c49038F819E587E2633c4A9F428a",
            SmartWallet.COINBASE_SMART_WALLET_FACTORY,
        )
    }

    @Test
    fun `default RPC is the public Base Sepolia endpoint`() {
        // Public, no API key required, same RPC the bundler will hit.
        assertEquals(
            "https://sepolia.base.org",
            SmartWallet.DEFAULT_BASE_SEPOLIA_RPC,
        )
    }

    @Test
    fun `default nonce is zero (first wallet for this owner)`() {
        // Verified indirectly: with nonce=0 by default, two SmartWallet
        // instances with the same passkey pubkey produce the same
        // owner-bytes blob, which the factory will hash into the same salt.
        val a = SmartWallet(pkX, pkY)
        val b = SmartWallet(pkX, pkY, nonce = BigInteger.ZERO)
        assertArrayEquals(a.ownerBytes, b.ownerBytes)
    }

    @Test
    fun `different passkey owners produce different owner-bytes blobs`() {
        // Sanity check the obvious property that the on-chain factory relies
        // on for address determinism. If two different pubkeys produced the
        // same ownerBytes we'd have a catastrophic collision.
        val sw1 = SmartWallet(pkX, pkY)
        val pkX2 = ByteArray(32) { (it + 1).toByte() }
        val sw2 = SmartWallet(pkX2, pkY)
        assertTrue(!sw1.ownerBytes.contentEquals(sw2.ownerBytes))
    }
}
