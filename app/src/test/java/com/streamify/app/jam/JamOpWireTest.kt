package com.streamify.app.jam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 48-byte JamOpWire envelope: exact layout, frozen forever — a byte-level
 * contract shared with the native CRDT and the Merkle leaves.
 */
class JamOpWireTest {

    private val op = JamOpWire(
        opId = 0x0102030405060708L,
        sender = -0x1122334455667788L, // exercises sign bit
        type = JamOpWire.OP_REORDER,
        policy = 1,
        cadId = 0x7FEDCBA987654321L,
        frac = 0.5,
        target = 42L
    )

    @Test
    fun `pack produces exactly 48 bytes`() {
        assertEquals(48, JamOpWire.pack(op).size)
    }

    @Test
    fun `layout matches the frozen little-endian spec`() {
        val b = ByteBuffer.wrap(JamOpWire.pack(op)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(op.opId, b.long)
        assertEquals(op.sender, b.long)
        assertEquals(op.type, b.int)
        assertEquals(op.policy, b.int)
        assertEquals(op.cadId, b.long)
        assertEquals(op.frac.toRawBits(), b.long)
        assertEquals(op.target, b.long)
        assertEquals(0, b.remaining())
    }

    @Test
    fun `unpack round-trips every field`() {
        val restored = JamOpWire.unpack(JamOpWire.pack(op))
        assertEquals(op, restored)
    }

    @Test
    fun `packing is canonical — identical ops produce identical bytes`() {
        assertArrayEquals(JamOpWire.pack(op), JamOpWire.pack(op.copy()))
    }

    @Test
    fun `wrong size rejected`() {
        assertNull(JamOpWire.unpack(ByteArray(47)))
        assertNull(JamOpWire.unpack(ByteArray(49)))
        assertNull(JamOpWire.unpack(ByteArray(0)))
    }

    @Test
    fun `out-of-range type and policy rejected`() {
        for (bad in intArrayOf(0, 4, -1, Int.MAX_VALUE)) {
            val bytes = JamOpWire.pack(op.copy(type = bad))
            assertNull("type $bad must be rejected", JamOpWire.unpack(bytes))
        }
        for (bad in intArrayOf(-1, 2, 255)) {
            val bytes = JamOpWire.pack(op.copy(policy = bad))
            assertNull("policy $bad must be rejected", JamOpWire.unpack(bytes))
        }
    }

    @Test
    fun `non-finite fractional index rejected`() {
        for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val bytes = JamOpWire.pack(op.copy(frac = bad))
            assertNull(JamOpWire.unpack(bytes))
        }
    }

    @Test
    fun `leaf hash is 32 bytes and stable`() {
        val h1 = JamOpWire.leafHash(op)
        val h2 = JamOpWire.leafHash(op)
        assertEquals(32, h1.size)
        assertArrayEquals(h1, h2)
        // A one-ULP change in frac must change the leaf hash (Merkle diffing).
        val changed = JamOpWire.leafHash(op.copy(frac = Math.nextUp(op.frac)))
        assertFalse(h1.contentEquals(changed))
    }

    @Test
    fun `pad leaf is the blake3 of 48 zero bytes`() {
        assertArrayEquals(Blake3.hash(ByteArray(48)), JamOpWire.PAD_LEAF)
        assertTrue(JamOpWire.isPadLeaf(ByteArray(48)))
        assertFalse(JamOpWire.isPadLeaf(JamOpWire.pack(op)))
    }
}
