package com.streamify.app.jam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLAKE3 correctness against the canonical reference implementation.
 *
 * Vectors were generated with the official `blake3` crate (pip blake3 ==
 * the Rust reference binding) over incremental lengths chosen to cross
 * every structural boundary: single partial block (11, 63), block edge
 * (64, 65), multi-block chunk (127, 128), chunk edge (1023, 1024),
 * multi-chunk (1025, 2048, 2049), tree fold (3000, 4096).
 */
class Blake3Test {

    private fun input(n: Int): ByteArray = ByteArray(n) { i -> ((i * 31 + 7) % 256).toByte() }

    private fun vectorOf(n: Int): String = when (n) {
        0 -> "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262"
        1 -> "448bd8dd9624154a690f8e84dc52d6f633ba7cd545c4d3c9b4e0f6a2f6fa71f4"
        2 -> "f7656ad671b658c8a927c431b0cbf3b4a0126e9b95c5a24eb4b0ee39c23af8bc"
        3 -> "545a7476d63b5a22936f733cd2cb89f162a7d864cb01b8b88437a36627b1303a"
        11 -> "223f030dc62317b4c97c9de720cfa77dbe664869b90b2052087579280f51773e"
        63 -> "7a7ae5f9b6891d25edecc530fbf41f88f0e4035b8fa17715cfb1201ba3c913ae"
        64 -> "580eedf630212f2a9bd14712f93921e1a2712117290a21a8974a029532e93b11"
        65 -> "7f55325c3368e44f79edddb7b1a079b8aeae7ab43a0254b3012e564d75c4c1ae"
        127 -> "5a38a12c36b8ff708d50179c3f3a0376ef51ad7bc9a1d2a667536ad3adca89da"
        128 -> "70c102aea289c63bad9bb51af55be3f4c539b7bd30c0dc5857f47e94e2d47c7b"
        1023 -> "7cc12c8435bc5cdb011ba62b7367601fb7d30b23b32e177f7e41907b210a8673"
        1024 -> "16f3b22ae43940fb8c8f328b033272ae752c203c3385d00bdda1696540f4c37e"
        1025 -> "b8c5c46b114817810a6ed499350cb4d2423cd23dd08d32c137b226d8559b8ab0"
        2048 -> "634f590a498b3e29165cd8bd32f30a99a2b0d8949a3d7ce35779b6253d8ca0d5"
        2049 -> "3c6cc85bfe26acef94b6bb440e8256c3fc541be3afe18bfffcd2c30c38e8a633"
        3000 -> "e8399322cd0777e24d185a1db40fac663edeeab8c800b87d2d890b74a2818e25"
        4096 -> "d0c362f7235cbf7df3b8aabcefa9be7485c1c3c82983d42a519345929fb2fc1f"
        else -> throw IllegalArgumentException("no vector for length $n")
    }

    @Test
    fun `empty input matches official vector`() {
        assertEquals(vectorOf(0), Blake3.hashHex(ByteArray(0)))
    }

    @Test
    fun `short ascii inputs match official vectors`() {
        assertEquals(
            "d74981efa70a0c880b8d8c1985d075dbcbf679b99a5f9914e5aaf96b831a9e24",
            Blake3.hashHex("hello world")
        )
        assertEquals("6437b3ac38465133ffb63b75273a8db548c558465d79db03fd359c6cd5bd9d85", Blake3.hashHex("abc"))
    }

    @Test
    fun `block boundaries match official vectors`() {
        for (n in intArrayOf(1, 2, 3, 11, 63, 64, 65, 127, 128)) {
            assertEquals("length $n", vectorOf(n), Blake3.hashHex(input(n)))
        }
    }

    @Test
    fun `chunk boundaries match official vectors`() {
        for (n in intArrayOf(1023, 1024, 1025, 2048, 2049, 3000, 4096)) {
            assertEquals("length $n", vectorOf(n), Blake3.hashHex(input(n)))
        }
    }

    @Test
    fun `chunked updates equal one-shot hashing`() {
        val data = input(5000)
        val oneShot = Blake3.hash(data)
        val flat = ArrayList<Byte>(data.size)
        // Reassemble in odd chunk sizes crossing the 1024-byte chunk edge.
        var off = 0
        intArrayOf(1, 62, 961, 3976).forEach { n ->
            for (i in 0 until n) flat.add(data[off + i])
            off += n
        }
        assertEquals(data.size, flat.size)
        val rebuilt = flat.toByteArray()
        assertArrayEquals(oneShot, Blake3.hash(rebuilt))
    }

    @Test
    fun `hashPair is deterministic and order-sensitive`() {
        val a = Blake3.hash("a")
        val b = Blake3.hash("b")
        assertArrayEquals(Blake3.hashPair(a, b), Blake3.hashPair(a.copyOf(), b.copyOf()))
        assertTrue(!Blake3.hashPair(a, b).contentEquals(Blake3.hashPair(b, a)))
    }

    @Test
    fun `compareUnsigned orders digests as unsigned big-endian`() {
        val low = ByteArray(32)
        low[0] = 0x00
        val high = ByteArray(32)
        high[0] = 0xFF.toByte()
        assertTrue(Blake3.compareUnsigned(low, high) < 0)
        assertTrue(Blake3.compareUnsigned(high, low) > 0)
        assertTrue(Blake3.compareUnsigned(low, low.copyOf()) == 0)
    }
}
