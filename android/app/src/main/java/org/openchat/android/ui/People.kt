package org.openchat.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.Conversation
import org.openchat.android.app.PeerLabel
import org.openchat.android.app.Social
import org.openchat.android.app.TorState
import org.openchat.android.core.sha256
import org.openchat.android.core.toHex

/** Who a tap on a name refers to: a mesh peer (by peer ID) or a location-channel sender (by Nostr key). */
sealed class PersonRef {
    data class Mesh(val peerHex: String, val nickname: String?) : PersonRef()
    data class Geo(val pubkey: String, val nickname: String?, val geohash: String) : PersonRef()
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
                            val verified = fp in social.verified
                            Text(
                                stringResource(if (verified) R.string.verify_badge_on else R.string.verify_badge_off),
                                fontSize = 13.sp, color = if (verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(fp.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { Social.setVerified(fp, !verified) }) {
                                Text(stringResource(if (verified) R.string.verify_unmark else R.string.verify_mark))
                            }
                        }
                        if (!blocked) {
                            FilledTonalButton(onClick = { onDismiss(); onMessage(person.peerHex) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.peer_message)) }
                            val fav = fp?.let { social.favorites[it] }
                            val mine = fav?.mine == true
                            OutlinedButton(
                                onClick = { ChatRepository.controller?.setFavorite(person.peerHex, person.nickname ?: person.peerHex.take(8), !mine) },
                                enabled = fp != null, modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(if (mine) R.string.peer_fav_remove else R.string.peer_fav_add)) }
                            val note = when {
                                mine && fav?.theyFavorited == true -> R.string.fav_mutual_note
                                mine -> R.string.fav_waiting_note
                                fav?.theyFavorited == true -> R.string.fav_they_note
                                else -> null
                            }
                            note?.let { Text(stringResource(it), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                        if (!blocked) {
                            FilledTonalButton(
                                onClick = { onDismiss(); onMessage(Conversation.GeoPrivate(person.geohash, person.pubkey).key) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.peer_message)) }
                        }
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

/** My verification QR (§16), rebuilt each time it is shown since it is valid for five minutes. */
@Composable
fun MyQrDialog(onDismiss: () -> Unit) {
    val url = remember { ChatRepository.controller?.myVerifyUrl() }
    val bitmap = remember(url) { url?.let { qrBitmap(it, 720) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.verify_my_qr)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bitmap != null) {
                    Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White).padding(8.dp))
                }
                Text(stringResource(R.string.verify_my_qr_note), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** Black-on-white QR code; the white quiet zone comes from the caller's background. */
fun qrBitmap(text: String, size: Int): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to "M"))
    val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Show my QR, or scan someone else's. */
@Composable
fun VerifySection(onScan: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    if (show) MyQrDialog { show = false }
    Text(stringResource(R.string.verify_title), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp))
    Text(stringResource(R.string.verify_body), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        FilledTonalButton(onClick = { show = true }) { Text(stringResource(R.string.verify_show)) }
        OutlinedButton(onClick = onScan) { Text(stringResource(R.string.verify_scan)) }
    }
}

/** Tor routing for relay traffic: on by default, fail-closed (§19). */
@Composable
fun TorSection() {
    val geo by ChatRepository.geo.collectAsState()
    val on = geo.tor != TorState.OFF
    Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.tor_title), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(when (geo.tor) { TorState.ON -> R.string.tor_on; TorState.STARTING -> R.string.tor_starting; TorState.OFF -> R.string.tor_off }),
                fontSize = 13.sp, color = if (geo.tor == TorState.OFF) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = { ChatRepository.controller?.setTor(it) })
    }
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
