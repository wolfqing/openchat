package org.openchat.android.app

import org.openchat.android.core.Vouch
import org.openchat.android.core.hexToBytes
import org.openchat.android.core.sha256
import org.openchat.android.core.toHex
import org.openchat.android.mesh.MeshEngine

/**
 * Vouching (docs/protocol-spec.md §26), bitchat's policy: when a session is up with
 * someone we verified, we tell them, at most once a day, whom else we verified; vouches
 * count only from people we verified ourselves. Runs on the mesh thread.
 */
class Vouching(private val engine: () -> MeshEngine) {
    private fun fingerprintOf(peerHex: String) = engine().noiseKeyOf(peerHex)?.let { sha256(it).toHex() }

    /** Sends a batch to [peerHex] if the policy allows; true when one went out. */
    fun attempt(peerHex: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val e = engine()
        if (!e.hasSession(peerHex)) return false
        val fp = fingerprintOf(peerHex) ?: return false
        val s = Social.state.value
        if (fp !in s.verified) return false
        // Capabilities can lag the session; only an announce that lists others but not vouching rules it out.
        val caps = e.capabilitiesOf(peerHex)
        if (caps != 0L && caps and MeshEngine.CAP_VOUCH == 0L) return false
        if (s.vouchSent[fp]?.let { nowMs - it < BATCH_INTERVAL_MS } == true) return false
        val batch = s.verified.filter { it != fp }.sortedByDescending { s.verifiedAt[it] ?: 0L }.take(Vouch.MAX_BATCH).mapNotNull { c ->
            // Only someone whose announced signing key we hold can be named concretely.
            val hex = c.take(16)
            if (fingerprintOf(hex) != c) return@mapNotNull null
            val key = e.signingKeyOf(hex) ?: return@mapNotNull null
            Vouch.create(c.hexToBytes() ?: return@mapNotNull null, key, nowMs, e.identity.signing)
        }
        val payload = Vouch.encodeList(batch) ?: return false
        e.sendVouches(peerHex, payload)
        Social.markVouchSent(fp, nowMs)
        return true
    }

    fun attemptAll() = engine().peersSnapshot().forEach { attempt(it.peerIdHex) }

    fun receive(sessionKey: ByteArray, signingKey: ByteArray, batch: ByteArray) {
        val voucher = sha256(sessionKey).toHex()
        if (voucher !in Social.state.value.verified) return
        val now = System.currentTimeMillis()
        Vouch.decodeList(batch).forEach { v ->
            if (v.verify(signingKey) && !v.expired(now)) Social.recordVouch(v.voucheeHex, voucher, v.timestampMs)
        }
    }

    private companion object { const val BATCH_INTERVAL_MS = 24 * 3600_000L }
}
