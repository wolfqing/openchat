package org.openchat.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.PeerLabel
import org.openchat.android.app.Social
import org.openchat.android.core.sha256
import org.openchat.android.core.toHex

/** Who a tap on a name refers to: a mesh peer (by peer ID) or a location-channel sender (by Nostr key). */
sealed class PersonRef {
    data class Mesh(val peerHex: String, val nickname: String?) : PersonRef()
    data class Geo(val pubkey: String, val nickname: String?) : PersonRef()
}

fun meshFingerprint(peerHex: String): String? =
    ChatRepository.controller?.noiseKey(peerHex)?.let { sha256(it).toHex() } ?: Social.state.value.fingerprintOf(peerHex)

/** Actions on one person: message, block. Opened from a name in a chat. */
@Composable
fun PersonSheet(person: PersonRef, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val social by Social.state.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        title = {
            Text(
                labelText(
                    when (person) {
                        is PersonRef.Mesh -> PeerLabel.mesh(person.nickname, person.peerHex)
                        is PersonRef.Geo -> PeerLabel.nostr(person.nickname, person.pubkey)
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (person) {
                    is PersonRef.Mesh -> {
                        val fp = meshFingerprint(person.peerHex)
                        val blocked = fp != null && fp in social.blocked
                        if (fp != null) {
                            Text(fp.chunked(4).take(8).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!blocked) {
                            FilledTonalButton(onClick = { onDismiss(); onMessage(person.peerHex) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.peer_message)) }
                        }
                        if (fp != null) {
                            OutlinedButton(
                                onClick = { if (blocked) Social.unblock(fp) else Social.block(fp, person.nickname ?: person.peerHex.take(8)) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(if (blocked) R.string.peer_unblock else R.string.peer_block)) }
                            if (blocked) Text(stringResource(R.string.peer_blocked_note), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    is PersonRef.Geo -> {
                        val blocked = person.pubkey in social.geoBlocked
                        OutlinedButton(
                            onClick = { if (blocked) Social.unblockGeo(person.pubkey) else Social.blockGeo(person.pubkey, person.nickname ?: "anon") },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(if (blocked) R.string.peer_unblock else R.string.peer_block)) }
                        Text(stringResource(if (blocked) R.string.peer_blocked_note else R.string.peer_geo_note), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    )
}

/** Blocked people, listed on the Me tab with a way back. */
@Composable
fun BlockedList() {
    val social by Social.state.collectAsState()
    if (social.blocked.isEmpty() && social.geoBlocked.isEmpty()) return
    Text(stringResource(R.string.me_blocked_title), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp))
    social.blocked.forEach { (fp, name) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(labelText(PeerLabel.mesh(name, fp.take(16))), Modifier.weight(1f))
            TextButton(onClick = { Social.unblock(fp) }) { Text(stringResource(R.string.peer_unblock)) }
        }
    }
    social.geoBlocked.forEach { (pk, name) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(labelText(PeerLabel.nostr(name, pk)), Modifier.weight(1f))
            TextButton(onClick = { Social.unblockGeo(pk) }) { Text(stringResource(R.string.peer_unblock)) }
        }
    }
}
