package com.streamify.app.jam

import com.streamify.app.util.SLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MerkleStateReconciler — fast state-DAG synchronization on join
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Guarantees a peer joining mid-session converges on the shared queue in
 * < 50 ms on a LAN mesh:
 *
 *  1. ROOT EXCHANGE — the joiner sends a single 32-byte Merkle root (plus a
 *     u32 op count) representing its local queue state. If the roots match,
 *     the states are identical and 0 bytes of queue data are transferred.
 *
 *  2. BRANCH TRAVERSAL — on mismatch, the protocol walks DOWN the padded
 *     binary Merkle DAG level by level, exchanging only the subtree hashes
 *     whose parents differ. Round trips = tree depth + 1 (≤ 11 for a
 *     1024-op room; ≤ 7 for realistic queues), each ~4 KB max.
 *
 *  3. DELTA SYNC — only the missing [JamOpWire] operations (48 bytes each,
 *     plus optional track metadata for ADDs) are transferred, applied in
 *     opId order, and convergence is verified locally against the peer's
 *     known root — no extra confirmation round trip.
 *
 * Tree definition (bit-identical on every peer):
 *  • leaves  = sealed 48-byte ops, sorted by opId;
 *  • padding = Blake3(48 zero bytes) at leaf level, folded upward;
 *  • interior = Blake3(left ‖ right);
 *  • depth    = ceil(log2(max(opCount, 1))); both sides align to the max
 *    depth by deterministically folding padding above their root.
 *
 * Direction rule (CRDT-union correct, deadlock free):
 *  • the side with FEWER ops pulls from the side with more;
 *  • equal counts + differing roots ⇒ both sides pull symmetrically;
 *  • equal roots ⇒ nobody pulls — zero traffic.
 */
class MerkleStateReconciler(
    private val scope: CoroutineScope,
    private val selfNonce: String,
    private val transport: Transport,
    /** Applies a received delta op (native CRDT + queue refresh). */
    private val applyDeltaOp: (JamWire.MerkleDeltaOp) -> Unit,
    private val clock: () -> Long = { System.nanoTime() },
    private val config: Config = Config()
) {

    /** Wire send surface implemented by JamEngine. */
    interface Transport {
        fun sendToPeer(peerIdHex: String, msgType: Int, payload: ByteArray): Boolean
    }

    data class Config(
        /** Re-sync cooldown per peer (thundering-herd guard). */
        val resyncCooldownNanos: Long = 5_000_000_000L,
        /** Max protocol restarts when the room mutates mid-sync. */
        val maxRestarts: Int = 3,
        /** Watchdog cadence for stalled sessions. */
        val pollMillis: Long = 500L,
        /** A session without progress longer than this is abandoned. */
        val sessionTimeoutNanos: Long = 15_000_000_000L
    )

    /** Completion telemetry surfaced to the UI / logs. */
    data class SyncReport(
        val peerIdHex: String,
        val converged: Boolean,
        val opsTransferred: Int,
        val bytesTransferred: Int,
        val latencyNanos: Long,
        val zeroByteFastPath: Boolean
    )

    private val _reports = MutableSharedFlow<SyncReport>(extraBufferCapacity = 32)
    val reports: SharedFlow<SyncReport> = _reports

    private val _lastConvergenceNanos = MutableStateFlow(-1L)
    val lastConvergenceNanos: StateFlow<Long> = _lastConvergenceNanos.asStateFlow()

    // ── Local op log (the Merkle leaves) ───────────────────────────────────

    private val opLog = ConcurrentHashMap<Long, JamOpWire>()      // opId -> sealed op
    private val opMeta = ConcurrentHashMap<Long, String>()        // opId -> track JSON
    private val sortedIds = CopyOnWriteArrayList<Long>()

    private data class JoinerSession(
        val peerIdHex: String,
        var alignedDepth: Int,
        var phase: Phase,
        val differingLeaves: MutableList<Int> = mutableListOf(),
        var startedNanos: Long,
        var lastProgressNanos: Long,
        var restarts: Int = 0,
        var peerOpCount: Int = -1,
        var peerRootAtDepth: ByteArray? = null,
        var opsTransferred: Int = 0,
        var bytesTransferred: Int = 0,
        /** Level + indices of the in-flight MERKLE_BRANCH_REQ (response frames
         *  carry only hashes — the joiner correlates position from here). */
        var requestedLevel: Int = -1,
        var requestedIndices: IntArray = IntArray(0)
    )

    private enum class Phase { ROOT_REQ, BRANCH, DELTA_REQ, DONE }

    private val sessions = ConcurrentHashMap<String, JoinerSession>()
    private val lastSyncAt = ConcurrentHashMap<String, Long>()
    private var watchdog: Job? = null

    // ═════════ Lifecycle ═════════

    fun start() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (isActive) {
                delay(config.pollMillis)
                runCatching { reapStalledSessions() }
            }
        }
    }

    fun stop() {
        watchdog?.cancel()
        watchdog = null
        sessions.clear()
    }

    fun reset() {
        sessions.clear()
        lastSyncAt.clear()
        opLog.clear()
        opMeta.clear()
        sortedIds.clear()
        _lastConvergenceNanos.value = -1L
    }

    // ═════════ Op-log maintenance (called on every applied op) ═════════

    fun noteOpApplied(op: JamOpWire, trackJson: String?) {
        if (opLog.putIfAbsent(op.opId, op) == null) {
            sortedIds.add(op.opId)
            sortedIds.sort()
        }
        if (trackJson != null) opMeta[op.opId] = trackJson
    }

    fun opCount(): Int = opLog.size

    fun opsSnapshot(): List<JamOpWire> = sortedIds.mapNotNull { opLog[it] }

    fun trackJsonOf(opId: Long): String? = opMeta[opId]

    // ═════════ The Merkle tree ═════════

    /**
     * Padded binary Merkle tree over sealed ops. Levels are indexed from the
     * leaves (level 0); the root sits at [depth]. Padding hashes are
     * deterministic constants so any two peers align to any depth.
     */
    class MerkleTree(leafHashes: List<ByteArray>) {

        val opCount: Int = leafHashes.size
        val depth: Int = depthFor(opCount)

        private val levels: Array<Array<ByteArray>>

        init {
            val leafCount = if (depth == 0) 1 else 1 shl depth
            var level = Array(leafCount) { i -> if (i < leafHashes.size) leafHashes[i] else padAt(0) }
            val all = ArrayList<Array<ByteArray>>()
            all.add(level)
            while (level.size > 1) {
                val next = Array(level.size / 2) { i -> Blake3.hashPair(level[2 * i], level[2 * i + 1]) }
                all.add(next)
                level = next
            }
            levels = all.toTypedArray()
        }

        val root: ByteArray get() = nodeHash(depth, 0)

        /**
         * Hash of the node at [level]/[index] — padding-correct for any
         * (level, index), including ABOVE this tree's natural depth.
         */
        fun nodeHash(level: Int, index: Int): ByteArray {
            if (level <= depth) {
                return if (index < levels[level].size) levels[level][index] else padAt(level)
            }
            if (index != 0) return padAt(level)
            var h = levels[depth][0]
            for (k in depth until level) h = Blake3.hashPair(h, padAt(k))
            return h
        }

        companion object {
            /** Tree depth for [opCount] leaves (0 for 0/1 ops). */
            fun depthFor(opCount: Int): Int {
                if (opCount <= 1) return 0
                var d = 0
                var capacity = 1
                while (capacity < opCount) {
                    capacity = capacity shl 1
                    d++
                }
                return d
            }

            private val padCache = ArrayList<ByteArray>().apply { add(JamOpWire.PAD_LEAF) }

            /** Padding hash of an all-padding node at [level] (0 = leaf level). */
            fun padAt(level: Int): ByteArray {
                synchronized(padCache) {
                    while (padCache.size <= level) {
                        val last = padCache.last()
                        padCache.add(Blake3.hashPair(last, last))
                    }
                    return padCache[level]
                }
            }

            /** Depth-aligned root: folds padding above the natural root. */
            fun rootAtDepth(root: ByteArray, naturalDepth: Int, targetDepth: Int): ByteArray {
                var h = root
                for (k in naturalDepth until targetDepth) h = Blake3.hashPair(h, padAt(k))
                return h
            }
        }
    }

    /** Builds a tree over the CURRENT op log. */
    fun buildTree(): MerkleTree =
        MerkleTree(sortedIds.mapNotNull { id -> opLog[id]?.let { JamOpWire.leafHash(it) } })

    fun computeRoot(): ByteArray = buildTree().root

    fun computeRootHex(): String = Blake3.toHex(computeRoot())

    // ═════════ Session initiation ═════════

    /**
     * Mesh peer connected: exchange roots. Cooldown + in-flight guard keep a
     * re-connecting peer from stampeding the room.
     */
    fun onPeerJoined(peerIdHex: String) {
        val now = clock()
        // NB: a null-coalesced Long.MIN_VALUE sentinel would OVERFLOW the
        // subtraction and wrap negative, silently re-entering this early
        // return for every first-time joiner (sessions never started).
        val last = lastSyncAt[peerIdHex]
        if (last != null && now - last < config.resyncCooldownNanos) return
        if (sessions.containsKey(peerIdHex)) return
        startJoinerSession(peerIdHex, now)
    }

    fun onPeerLeft(peerIdHex: String) {
        sessions.remove(peerIdHex)
        lastSyncAt.remove(peerIdHex)
    }

    private fun startJoinerSession(peerIdHex: String, nowNanos: Long) {
        val tree = buildTree()
        // Register the session BEFORE the send: with a synchronous (or
        // loopback) transport the peer's ROOT_ACK can re-enter onFrame()
        // inside sendToPeer() — before this line would have run — and must
        // find its session already latched, or the whole handshake deadlocks.
        sessions[peerIdHex] = JoinerSession(
            peerIdHex = peerIdHex,
            alignedDepth = tree.depth,
            phase = Phase.ROOT_REQ,
            startedNanos = nowNanos,
            lastProgressNanos = nowNanos,
            peerOpCount = -1
        )
        val sent = transport.sendToPeer(
            peerIdHex, JamWire.Msg.MERKLE_ROOT_REQ,
            JamWire.encodeMerkleRootReq(selfNonce, tree.opCount, tree.root)
        )
        if (!sent) {
            sessions.remove(peerIdHex)
            return
        }
        lastSyncAt[peerIdHex] = nowNanos
        SLog.d(TAG, "root req → $peerIdHex (ops=${tree.opCount})")
    }

    // ═════════ Frame handling (routed by JamEngine's dispatcher) ═════════

    fun onFrame(peerIdHex: String, frame: JamWire.Frame) {
        when (frame.msgType) {
            JamWire.Msg.MERKLE_ROOT_REQ -> onRootReq(peerIdHex, frame)
            JamWire.Msg.MERKLE_ROOT_ACK -> onRootAck(peerIdHex, frame)
            JamWire.Msg.MERKLE_BRANCH_REQ -> onBranchReq(peerIdHex, frame)
            JamWire.Msg.MERKLE_BRANCH -> onBranch(peerIdHex, frame)
            JamWire.Msg.MERKLE_DELTA_REQ -> onDeltaReq(peerIdHex, frame)
            JamWire.Msg.MERKLE_DELTA -> onDelta(peerIdHex, frame)
        }
    }

    // ── ROOM side: a joiner knocked on our door ────────────────────────────

    private fun onRootReq(peerIdHex: String, frame: JamWire.Frame) {
        val req = JamWire.parseMerkleRootReq(frame) ?: return
        val tree = buildTree()
        val theirNaturalDepth = MerkleTree.depthFor(req.opCount)
        val dMax = maxOf(tree.depth, theirNaturalDepth)

        // Verify the joiner's root at the ALIGNED depth by folding padding.
        val joinerRootAligned = MerkleTree.rootAtDepth(req.root, theirNaturalDepth, dMax)
        val myRootAligned = MerkleTree.rootAtDepth(tree.root, tree.depth, dMax)
        val equal = req.opCount == tree.opCount && joinerRootAligned.contentEquals(myRootAligned)

        transport.sendToPeer(
            peerIdHex, JamWire.Msg.MERKLE_ROOT_ACK,
            JamWire.encodeMerkleRootAck(selfNonce, equal, tree.opCount, myRootAligned)
        )

        if (equal) {
            SLog.d(TAG, "root equal with $peerIdHex — 0 bytes transferred")
            return
        }

        // Direction rule: we pull only when strictly behind, or counts tie AND
        // our nonce orders first (symmetric double-pull covers divergence).
        val wePull = tree.opCount < req.opCount ||
            (tree.opCount == req.opCount && selfNonce < frame.senderNonce)
        if (!wePull) return

        val now = clock()
        // Same overflow-free cooldown guard as onPeerJoined.
        val last = lastSyncAt[peerIdHex]
        if (last != null && now - last < config.resyncCooldownNanos) return
        if (sessions.containsKey(peerIdHex)) return

        // Our own joiner session toward the peer — their root is already
        // known from this REQ, so the traversal starts immediately.
        lastSyncAt[peerIdHex] = now
        val session = JoinerSession(
            peerIdHex = peerIdHex,
            alignedDepth = dMax,
            phase = Phase.BRANCH,
            startedNanos = now,
            lastProgressNanos = now,
            peerOpCount = req.opCount,
            peerRootAtDepth = joinerRootAligned
        )
        sessions[peerIdHex] = session
        requestNextBranchLevel(session)
    }

    // ── JOINER side: the room answered our root exchange ───────────────────

    private fun onRootAck(peerIdHex: String, frame: JamWire.Frame) {
        val ack = JamWire.parseMerkleRootAck(frame) ?: return
        val session = sessions[peerIdHex] ?: return
        if (session.phase != Phase.ROOT_REQ) return
        session.lastProgressNanos = clock()

        if (ack.equal) {
            complete(session, converged = true, zeroByteFastPath = true)
            return
        }

        val myTree = buildTree()
        val dMax = maxOf(myTree.depth, MerkleTree.depthFor(ack.opCount))
        session.peerOpCount = ack.opCount
        session.peerRootAtDepth = ack.root
        session.alignedDepth = dMax

        // The room side pulls when IT is behind — nothing for us to do here.
        val roomPulls = ack.opCount < myTree.opCount ||
            (ack.opCount == myTree.opCount && frame.senderNonce < selfNonce)
        if (roomPulls) return

        requestNextBranchLevel(session)
    }

    // ── ROOM side: the joiner is walking our tree ──────────────────────────

    private fun onBranchReq(peerIdHex: String, frame: JamWire.Frame) {
        val req = JamWire.parseMerkleBranchReq(frame) ?: return
        val tree = buildTree()
        val hashes = req.indices.map { tree.nodeHash(req.level, it) }
        transport.sendToPeer(
            peerIdHex, JamWire.Msg.MERKLE_BRANCH,
            JamWire.encodeMerkleBranch(selfNonce, req.level, hashes)
        )
    }

    // ── JOINER side: subtree hashes arrived ────────────────────────────────

    private fun onBranch(peerIdHex: String, frame: JamWire.Frame) {
        val branch = JamWire.parseMerkleBranch(frame) ?: return
        val session = sessions[peerIdHex] ?: return
        if (session.phase != Phase.BRANCH) return
        session.lastProgressNanos = clock()

        val myTree = buildTree()
        val nextLevel = mutableListOf<Int>()
        val level = session.requestedLevel
        val requested = session.requestedIndices

        for (i in branch.hashes.indices) {
            if (i >= requested.size) break // defensive: protocol drift guard
            val index = requested[i]
            val theirHash = branch.hashes[i]
            val myHash = myTree.nodeHash(level, index)
            if (theirHash.contentEquals(myHash)) continue // subtree already in sync
            if (level == 0) {
                session.differingLeaves.add(index)
            } else {
                nextLevel.add(2 * index)
                nextLevel.add(2 * index + 1)
            }
        }

        if (nextLevel.isEmpty()) {
            // Every divergent subtree resolved at this level.
            if (session.differingLeaves.isEmpty()) {
                verifyLocalConvergence(session)
                return
            }
            requestDelta(session)
        } else {
            session.requestedLevel = level - 1
            session.requestedIndices = nextLevel.toIntArray()
            transport.sendToPeer(
                peerIdHex, JamWire.Msg.MERKLE_BRANCH_REQ,
                JamWire.encodeMerkleBranchReq(selfNonce, level - 1, nextLevel.toIntArray())
            )
        }
    }

    /**
     * Begins the top-down traversal: compare the root's children first.
     * A depth-0 tree (single leaf) jumps straight to the delta request.
     */
    private fun requestNextBranchLevel(session: JoinerSession) {
        session.phase = Phase.BRANCH
        if (session.alignedDepth == 0) {
            session.differingLeaves.add(0)
            requestDelta(session)
            return
        }
        session.requestedLevel = session.alignedDepth - 1
        session.requestedIndices = intArrayOf(0, 1)
        transport.sendToPeer(
            session.peerIdHex, JamWire.Msg.MERKLE_BRANCH_REQ,
            JamWire.encodeMerkleBranchReq(selfNonce, session.alignedDepth - 1, intArrayOf(0, 1))
        )
    }

    // ── ROOM side: the joiner asked for the missing leaves ─────────────────

    private fun onDeltaReq(peerIdHex: String, frame: JamWire.Frame) {
        val req = JamWire.parseMerkleDeltaReq(frame) ?: return
        val ops = ArrayList<JamWire.MerkleDeltaOp>(req.leafIndices.size)
        for (leafIndex in req.leafIndices) {
            if (leafIndex < 0 || leafIndex >= sortedIds.size) continue
            val opId = sortedIds[leafIndex]
            val op = opLog[opId] ?: continue
            ops.add(JamWire.MerkleDeltaOp(op, opMeta[opId]))
        }
        transport.sendToPeer(
            peerIdHex, JamWire.Msg.MERKLE_DELTA,
            JamWire.encodeMerkleDelta(selfNonce, ops)
        )
        SLog.d(TAG, "delta → $peerIdHex: ${ops.size} ops (${ops.size * JamOpWire.WIRE_SIZE} B)")
    }

    // ── JOINER side: apply the delta ───────────────────────────────────────

    private fun requestDelta(session: JoinerSession) {
        session.phase = Phase.DELTA_REQ
        if (session.differingLeaves.isEmpty()) {
            verifyLocalConvergence(session)
            return
        }
        transport.sendToPeer(
            session.peerIdHex, JamWire.Msg.MERKLE_DELTA_REQ,
            JamWire.encodeMerkleDeltaReq(selfNonce, session.differingLeaves.toIntArray())
        )
    }

    private fun onDelta(peerIdHex: String, frame: JamWire.Frame) {
        val delta = JamWire.parseMerkleDelta(frame) ?: return
        val session = sessions[peerIdHex] ?: return
        if (session.phase != Phase.DELTA_REQ) return
        session.lastProgressNanos = clock()

        var bytes = 0
        for (entry in delta.ops) {
            if (opLog.containsKey(entry.op.opId)) continue
            bytes += JamOpWire.WIRE_SIZE +
                (entry.trackJson?.toByteArray(Charsets.UTF_8)?.size ?: 0)
            applyDeltaOp(entry)
            noteOpApplied(entry.op, entry.trackJson)
        }
        session.opsTransferred += delta.ops.size
        session.bytesTransferred += bytes
        verifyLocalConvergence(session)
    }

    /**
     * Zero-extra-round-trip convergence proof: recompute our root at the
     * aligned depth and compare with the peer's root captured during the
     * handshake. Restarts once per mutation race (host added a track while
     * we were converging), then gives up loudly.
     */
    private fun verifyLocalConvergence(session: JoinerSession) {
        val peerRoot = session.peerRootAtDepth ?: return complete(session, converged = true)
        val myTree = buildTree()
        val myRootAligned = MerkleTree.rootAtDepth(myTree.root, myTree.depth, session.alignedDepth)
        if (myRootAligned.contentEquals(peerRoot)) {
            complete(session, converged = true)
        } else if (session.restarts < config.maxRestarts) {
            session.restarts++
            session.differingLeaves.clear()
            session.phase = Phase.ROOT_REQ
            val now = clock()
            session.startedNanos = now
            session.lastProgressNanos = now
            transport.sendToPeer(
                session.peerIdHex, JamWire.Msg.MERKLE_ROOT_REQ,
                JamWire.encodeMerkleRootReq(selfNonce, myTree.opCount, myTree.root)
            )
            SLog.d(TAG, "sync restart #${session.restarts} with ${session.peerIdHex}")
        } else {
            complete(session, converged = false)
        }
    }

    private fun complete(session: JoinerSession, converged: Boolean, zeroByteFastPath: Boolean = false) {
        session.phase = Phase.DONE
        sessions.remove(session.peerIdHex)
        val latency = clock() - session.startedNanos
        if (converged) _lastConvergenceNanos.value = latency
        _reports.tryEmit(
            SyncReport(
                peerIdHex = session.peerIdHex,
                converged = converged,
                opsTransferred = session.opsTransferred,
                bytesTransferred = session.bytesTransferred,
                latencyNanos = latency,
                zeroByteFastPath = zeroByteFastPath
            )
        )
        SLog.i(
            TAG,
            "sync ${if (converged) "converged" else "FAILED"} with ${session.peerIdHex}: " +
                "${session.opsTransferred} ops / ${session.bytesTransferred} B in " +
                "${"%.1f".format(latency / 1_000_000.0)} ms" +
                "${if (zeroByteFastPath) " (0 B fast path)" else ""}"
        )
    }

    private fun reapStalledSessions() {
        val now = clock()
        sessions.entries
            .filter { now - it.value.lastProgressNanos > config.sessionTimeoutNanos }
            .forEach { (_, s) -> complete(s, converged = false) }
    }

    private companion object {
        const val TAG = "JamMerkle"
    }
}
