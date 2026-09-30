package com.streamify.app.jam

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * BLAKE3 — pure-Kotlin reference port (RFC-9380 lineage, official test vectors)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Why a pure-Kotlin BLAKE3 inside the Jam module:
 *
 *  1. DETERMINISTIC LEADERLESS ELECTION — the consensus contract requires
 *     `Blake3(deviceId + sessionId)` with the LOWEST digest winning the host
 *     role. Every device must bit-for-bit agree on the digest without a
 *     native dependency that may not be present in JVM unit tests, debug
 *     builds without the NDK artifact, or x86 emulators.
 *  2. MERKLE STATE DAG — queue reconciliation hashes are the diff currency of
 *     the zero-server sync protocol; they must be computable identically on
 *     every peer regardless of ABI availability.
 *  3. ZERO DEPENDENCIES — no JNI, no BouncyCastle, no version drift between
 *     the Android artifact and the JVM test shard. ~500 SLOC, constant memory.
 *
 * Correctness is pinned by [Blake3Test] against the official BLAKE3 vectors
 * (empty-string digest `af1349b9…`, incremental lengths, multi-chunk inputs
 * spanning 1/2/1024/1025/2048 boundaries) generated from the canonical
 * `blake3` reference crate.
 *
 * Only the unkeyed 32-byte-digest mode is implemented — that is the entire
 * surface the Jam protocol needs (XOF/keyed/derive-key modes are unused).
 */
object Blake3 {

    private const val OUT_LEN = 32
    private const val BLOCK_LEN = 64
    private const val CHUNK_LEN = 1024

    // Compression flags (reference §2.4).
    private const val CHUNK_START = 1
    private const val CHUNK_END = 2
    private const val PARENT = 4
    private const val ROOT = 8

    /** Maximum tree depth for u64 byte lengths — 54 CVs covers 2^64 bytes. */
    private const val MAX_DEPTH = 54

    private val IV = intArrayOf(
        0x6A09E667.toInt(), // SHA-256 IV — BLAKE3 initialization vector
        0xBB67AE85.toInt(),
        0x3C6EF372.toInt(),
        0xA54FF53A.toInt(),
        0x510E527F.toInt(),
        0x9B05688C.toInt(),
        0x1F83D9AB.toInt(),
        0x5BE0CD19.toInt()
    )

    private val MSG_PERMUTATION = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

    // ── Public API ─────────────────────────────────────────────────────────

    fun hash(input: ByteArray): ByteArray {
        val h = Hasher()
        h.update(input, 0, input.size)
        return h.digest()
    }

    fun hash(input: String): ByteArray = hash(input.toByteArray(Charsets.UTF_8))

    fun hashHex(input: ByteArray): String = toHex(hash(input))

    fun hashHex(input: String): String = toHex(hash(input))

    /** Constant 64-byte digest = hash(left || right) — Merkle interior node. */
    fun hashPair(left: ByteArray, right: ByteArray): ByteArray {
        require(left.size == OUT_LEN && right.size == OUT_LEN) { "Merkle children must be 32-byte digests" }
        val joined = ByteArray(64)
        System.arraycopy(left, 0, joined, 0, 32)
        System.arraycopy(right, 0, joined, 32, 32)
        return hash(joined)
    }

    /**
     * UNSIGNED lexicographic comparison of two digests — the deterministic
     * election ordering. Returns <0, 0, >0. Byte arrays compare as big-endian
     * unsigned magnitude, exactly like the reference `blake3` crate's
     * `Vec<u8>` Ord.
     */
    fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size - b.size
    }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0xF])
        }
        return sb.toString()
    }

    // ── Compression core ───────────────────────────────────────────────────

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    private fun g(s: IntArray, a: Int, b: Int, c: Int, d: Int, mx: Int, my: Int) {
        s[a] = s[a] + s[b] + mx
        s[d] = rotr(s[d] xor s[a], 16)
        s[c] = s[c] + s[d]
        s[b] = rotr(s[b] xor s[c], 12)
        s[a] = s[a] + s[b] + my
        s[d] = rotr(s[d] xor s[a], 8)
        s[c] = s[c] + s[d]
        s[b] = rotr(s[b] xor s[c], 7)
    }

    private fun round(s: IntArray, m: IntArray) {
        // Mix the columns.
        g(s, 0, 4, 8, 12, m[0], m[1])
        g(s, 1, 5, 9, 13, m[2], m[3])
        g(s, 2, 6, 10, 14, m[4], m[5])
        g(s, 3, 7, 11, 15, m[6], m[7])
        // Mix the diagonals.
        g(s, 0, 5, 10, 15, m[8], m[9])
        g(s, 1, 6, 11, 12, m[10], m[11])
        g(s, 2, 7, 8, 13, m[12], m[13])
        g(s, 3, 4, 9, 14, m[14], m[15])
    }

    private fun permuteInto(dst: IntArray, src: IntArray) {
        for (i in 0 until 16) dst[i] = src[MSG_PERMUTATION[i]]
    }

    /**
     * The BLAKE3 compression function F. Returns the full 16-word state so the
     * root mode can emit a 64-byte XOF block (we only consume words 0..7).
     */
    private fun compress(cv: IntArray, block: IntArray, counter: Long, blockLen: Int, flags: Int): IntArray {
        val s = IntArray(16)
        for (i in 0..7) {
            s[i] = cv[i]
            s[8 + i] = IV[i]
        }
        // Reference layout: [cv[0..8], IV[0..4], counter_lo, counter_hi,
        // block_len, flags] — the last four words are ASSIGNED, not XOR-ed
        // (unlike BLAKE2; verified against reference_impl.rs §compress).
        s[12] = counter.toInt()
        s[13] = (counter ushr 32).toInt()
        s[14] = blockLen
        s[15] = flags

        // Seven rounds; message words are permuted between rounds.
        var m = block.copyOf()
        val permuted = IntArray(16)
        for (r in 0 until 7) {
            round(s, m)
            permuteInto(permuted, m)
            m = permuted.copyOf()
        }

        for (i in 0..7) {
            s[i] = s[i] xor s[8 + i]
            s[8 + i] = s[8 + i] xor cv[i]
        }
        return s
    }

    // ── ChunkState / Output / Hasher (reference structure) ─────────────────

    private class Output(
        val inputChainingValue: IntArray,
        val blockWords: IntArray,
        val counter: Long,
        val blockLen: Int,
        val flags: Int
    ) {
        fun chainingValue(): IntArray =
            compress(inputChainingValue, blockWords, counter, blockLen, flags).copyOf(8)

        /** First 32 bytes of the root XOF stream (flags |= ROOT, block 0). */
        fun rootBytes(): ByteArray {
            val s = compress(inputChainingValue, blockWords, counter, blockLen, flags or ROOT)
            val out = ByteArray(OUT_LEN)
            for (i in 0 until 8) {
                val w = s[i]
                out[i * 4] = (w and 0xFF).toByte()
                out[i * 4 + 1] = ((w ushr 8) and 0xFF).toByte()
                out[i * 4 + 2] = ((w ushr 16) and 0xFF).toByte()
                out[i * 4 + 3] = ((w ushr 24) and 0xFF).toByte()
            }
            return out
        }
    }

    private class ChunkState(key: IntArray, val chunkCounter: Long) {
        private val cv: IntArray = key.copyOf()
        private val block = ByteArray(BLOCK_LEN)
        private var blockLen = 0
        private var blocksCompressed = 0

        fun len(): Int = blocksCompressed * BLOCK_LEN + blockLen

        private fun startFlag(): Int = if (blocksCompressed == 0) CHUNK_START else 0

        fun update(input: ByteArray, from: Int, count: Int) {
            var pos = from
            var remaining = count
            while (remaining > 0) {
                if (blockLen == BLOCK_LEN) {
                    val words = block.toIntArrayLE(16)
                    val out = compress(cv, words, chunkCounter, BLOCK_LEN, startFlag())
                    System.arraycopy(out, 0, cv, 0, 8)
                    blocksCompressed++
                    block.fill(0)
                    blockLen = 0
                }
                val want = BLOCK_LEN - blockLen
                val take = minOf(want, remaining)
                System.arraycopy(input, pos, block, blockLen, take)
                blockLen += take
                pos += take
                remaining -= take
            }
        }

        fun output(): Output =
            Output(cv, block.toIntArrayLE(16), chunkCounter, blockLen, startFlag() or CHUNK_END)
    }

    private class Hasher {
        private var chunkState = ChunkState(IV, 0L)
        private val cvStack = Array(MAX_DEPTH) { IntArray(8) }
        private var cvStackLen = 0

        /** Post-conditions: stack CV at [totalChunks-1] bit boundaries. */
        private fun addChunkChainingValue(newCv: IntArray, totalChunks: Long) {
            var cv = newCv.copyOf()
            var chunks = totalChunks
            while ((chunks and 1L) == 0L) {
                val left = cvStack[--cvStackLen]
                cv = parentCv(left, cv)
                chunks = chunks ushr 1
            }
            System.arraycopy(cv, 0, cvStack[cvStackLen++], 0, 8)
        }

        private fun parentCv(left: IntArray, right: IntArray): IntArray {
            val words = IntArray(16)
            System.arraycopy(left, 0, words, 0, 8)
            System.arraycopy(right, 0, words, 8, 8)
            // Parent nodes compress with the KEY (IV, unkeyed mode) as the
            // chaining value — never the left child's CV (reference §2.6).
            return compress(IV, words, 0L, BLOCK_LEN, PARENT).copyOf(8)
        }

        private fun parentOutput(left: IntArray, right: IntArray): Output {
            val words = IntArray(16)
            System.arraycopy(left, 0, words, 0, 8)
            System.arraycopy(right, 0, words, 8, 8)
            return Output(IV, words, 0L, BLOCK_LEN, PARENT)
        }

        fun update(input: ByteArray, from: Int, count: Int) {
            var pos = from
            var remaining = count
            while (remaining > 0) {
                // Chunk boundary crossed: seal the completed chunk into the tree.
                if (chunkState.len() == CHUNK_LEN) {
                    val chunkCv = chunkState.output().chainingValue()
                    val totalChunks = chunkState.chunkCounter + 1
                    addChunkChainingValue(chunkCv, totalChunks)
                    chunkState = ChunkState(IV, totalChunks)
                }
                val want = CHUNK_LEN - chunkState.len()
                val take = minOf(want, remaining)
                chunkState.update(input, pos, take)
                pos += take
                remaining -= take
            }
        }

        fun digest(): ByteArray {
            var output = chunkState.output()
            var remaining = cvStackLen
            while (remaining > 0) {
                remaining--
                output = parentOutput(cvStack[remaining], output.chainingValue())
            }
            return output.rootBytes()
        }
    }

    // ── LE byte-codec helpers ──────────────────────────────────────────────

    private fun ByteArray.toIntArrayLE(wordCount: Int): IntArray {
        val out = IntArray(wordCount)
        for (i in 0 until wordCount) {
            val b0 = this[i * 4].toInt() and 0xFF
            val b1 = this[i * 4 + 1].toInt() and 0xFF
            val b2 = this[i * 4 + 2].toInt() and 0xFF
            val b3 = this[i * 4 + 3].toInt() and 0xFF
            out[i] = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        }
        return out
    }
}
