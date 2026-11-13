package org.openchat.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.openchat.android.R
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.Conversation
import org.openchat.android.app.NostrDirect
import org.openchat.android.app.NotificationPrivacy
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

/** 64-hex fingerprint formatted in 4x4 monospace blocks (docs/design/ui-prototype.html `.fp`). */
@Composable
internal fun FingerprintGrid(hex: String, modifier: Modifier = Modifier) {
    val chunks = remember(hex) { hex.chunked(4) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = OpenChatPalette.Screen,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            chunks.chunked(4).forEach { rowChunks ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    rowChunks.forEach { block ->
                        Text(
                            block,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.5.sp,
                            letterSpacing = 0.5.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** Bottom sheet for inspecting a person's emblem, fingerprint, and actions (message, favorite, block). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonSheet(person: PersonRef, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val social by Social.state.collectAsState()
    val emblemKey = when (person) {
        is PersonRef.Mesh -> person.peerHex
        is PersonRef.Geo -> person.pubkey
    }
    val peerLabel = when (person) {
        is PersonRef.Mesh -> PeerLabel.mesh(person.nickname, person.peerHex)
        is PersonRef.Geo -> PeerLabel.nostr(person.nickname, person.pubkey)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = OpenChatPalette.Surface,
        contentColor = OpenChatPalette.Ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EmblemView(emblemKey, 76.dp)
            Text(
                labelText(
                    peerLabel,
                    verified = person is PersonRef.Mesh && social.isVerifiedPeer(person.peerHex),
                    vouched = person is PersonRef.Mesh && social.isVouchedPeer(person.peerHex),
                ),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            when (person) {
                is PersonRef.Mesh -> {
                    val fp = meshFingerprint(person.peerHex)
                    val blocked = fp != null && fp in social.blocked
                    val fav = fp?.let { social.favorites[it] }
                    val mine = fav?.mine == true

                    if (!blocked) {
                        Button(
                            onClick = { onDismiss(); onMessage(person.peerHex) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.peer_message), fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (fp != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!blocked) {
                                OutlinedButton(
                                    onClick = { ChatRepository.controller?.setFavorite(person.peerHex, person.nickname ?: person.peerHex.take(8), !mine) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        if (mine) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = null,
                                        tint = if (mine) OpenChatPalette.Signal else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(if (mine) R.string.peer_fav_remove else R.string.peer_fav_add), fontSize = 13.sp, maxLines = 1)
                                }
                            }
                            OutlinedButton(
                                onClick = { if (blocked) Social.unblock(fp) else Social.block(fp, person.nickname ?: person.peerHex.take(8)) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (blocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (blocked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
                                ),
                            ) {
                                Text(stringResource(if (blocked) R.string.peer_unblock else R.string.peer_block), fontSize = 13.sp)
                            }
                        }

                        val note = when {
                            blocked -> R.string.peer_blocked_note
                            mine && fav.theyFavorited -> R.string.fav_mutual_note
                            mine -> R.string.fav_waiting_note
                            fav?.theyFavorited == true -> R.string.fav_they_note
                            else -> null
                        }
                        note?.let {
                            Text(stringResource(it), fontSize = 12.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }

                        Hairline(Modifier.padding(vertical = 2.dp))

                        val verified = fp in social.verified
                        if (verified) {
                            Surface(
                                shape = CircleShape,
                                color = OpenChatPalette.SignalSoft,
                                border = BorderStroke(1.dp, OpenChatPalette.Signal.copy(alpha = 0.6f)),
                            ) {
                                Text(
                                    stringResource(R.string.verify_badge_on),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = OpenChatPalette.Signal,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            }
                        } else {
                            Text(
                                stringResource(R.string.verify_badge_off),
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }

                        val vouchers = social.vouchersFor(fp)
                        if (vouchers.isNotEmpty()) {
                            val names by ChatRepository.names.collectAsState()
                            val peers by ChatRepository.peers.collectAsState()
                            val who = vouchers.joinToString(", ") { v ->
                                val hex = v.take(16)
                                PeerLabel.mesh(peers.firstOrNull { it.peerIdHex == hex }?.nickname ?: social.favorites[v]?.nickname ?: names[hex], hex).toString()
                            }
                            Text(stringResource(R.string.vouch_by, who), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }

                        FingerprintGrid(fp)
                        TextButton(onClick = { Social.setVerified(fp, !verified) }) {
                            Text(
                                stringResource(if (verified) R.string.verify_unmark else R.string.verify_mark),
                                color = if (verified) MaterialTheme.colorScheme.onSurfaceVariant else OpenChatPalette.Signal,
                            )
                        }
                    }
                }
                is PersonRef.Geo -> {
                    val blocked = person.pubkey in social.geoBlocked
                    if (!blocked) {
                        Button(
                            onClick = { onDismiss(); onMessage(Conversation.GeoPrivate(person.geohash, person.pubkey).key) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.peer_message), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    OutlinedButton(
                        onClick = { if (blocked) Social.unblockGeo(person.pubkey) else Social.blockGeo(person.pubkey, person.nickname ?: "anon") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (blocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (blocked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
                        ),
                    ) { Text(stringResource(if (blocked) R.string.peer_unblock else R.string.peer_block)) }
                    Text(
                        stringResource(if (blocked) R.string.peer_blocked_note else R.string.peer_geo_note),
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** My verification QR (§16), rebuilt each time it is shown since it is valid for five minutes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyQrDialog(onDismiss: () -> Unit) {
    val url = remember { ChatRepository.controller?.myVerifyUrl() }
    val bitmap = remember(url) { url?.let { qrBitmap(it, 720) } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = OpenChatPalette.Surface,
        contentColor = OpenChatPalette.Ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.verify_my_qr), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            if (bitmap != null) {
                Box(
                    Modifier
                        .widthIn(max = 260.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .padding(14.dp),
                ) {
                    Image(bitmap, contentDescription = stringResource(R.string.verify_my_qr), modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                }
            }
            Text(
                stringResource(R.string.verify_my_qr_note),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

/** Black-on-white QR code; the white quiet zone comes from the caller's background. */
fun qrBitmap(text: String, size: Int): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to "M"))
    val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Rounded container grouping related settings rows on the Me tab. */
@Composable
internal fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = OpenChatPalette.Card,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/** Show my QR, or scan someone else's. */
@Composable
fun VerifySection(onShowQr: () -> Unit, onScan: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.verify_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(
            stringResource(R.string.verify_body) + " " + stringResource(R.string.vouch_note),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
            FilledTonalButton(onClick = onShowQr, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.verify_show), maxLines = 1)
            }
            OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.verify_scan), maxLines = 1)
            }
        }
    }
}

/** Whether notifications may show sender and text; off by default (§21.3). */
@Composable
fun NotificationSection() {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(NotificationPrivacy.showPreviews(ctx)) }
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.notify_previews_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                stringResource(if (show) R.string.notify_previews_on else R.string.notify_previews_off),
                fontSize = 13.sp,
                lineHeight = 18.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = show, onCheckedChange = { show = it; NotificationPrivacy.setShowPreviews(ctx, it) })
    }
}

/** Live voice (§29): one switch for streaming your voice notes and playing others' as they come. */
@Composable
fun LiveVoiceSection() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(org.openchat.android.app.LiveVoicePrefs.enabled(ctx)) }
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.live_voice_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                stringResource(R.string.live_voice_body),
                fontSize = 13.sp,
                lineHeight = 18.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = { on = it; org.openchat.android.app.LiveVoicePrefs.setEnabled(ctx, it) })
    }
}

/** The mesh bridge (§24): off by default; it puts this phone's public messages online. */
@Composable
fun BridgeSection() {
    val b by ChatRepository.bridge.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.bridge_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                stringResource(R.string.bridge_body) + " " + stringResource(R.string.bridge_gateway),
                fontSize = 13.sp,
                lineHeight = 18.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (b.enabled) {
                val cell = b.cell
                val line = if (cell == null) stringResource(R.string.bridge_need_cell)
                else if (b.online) stringResource(R.string.bridge_online, cell) + " · " + plural(R.plurals.bridge_across, b.across)
                else stringResource(R.string.bridge_offline, cell)
                Text(line, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = OpenChatPalette.Signal, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Switch(checked = b.enabled, onCheckedChange = { ChatRepository.controller?.setBridge(it) })
    }
}

/** Tor routing for relay traffic: on by default, fail-closed (§19). */
@Composable
fun TorSection() {
    val geo by ChatRepository.geo.collectAsState()
    val on = geo.tor != TorState.OFF
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.tor_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                stringResource(when (geo.tor) { TorState.ON -> R.string.tor_on; TorState.STARTING -> R.string.tor_starting; TorState.OFF -> R.string.tor_off }),
                fontSize = 13.sp,
                lineHeight = 18.5.sp,
                color = when (geo.tor) {
                    TorState.OFF -> MaterialTheme.colorScheme.error
                    TorState.ON -> OpenChatPalette.Online
                    TorState.STARTING -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Switch(checked = on, onCheckedChange = { ChatRepository.controller?.setTor(it) })
    }
}

/** The user's own relays for private messages, on top of the four built in (§15.4). */
@Composable
fun RelaySection() {
    val social by Social.state.collectAsState()
    var draft by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.relays_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(stringResource(R.string.relays_body), fontSize = 13.sp, lineHeight = 18.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        social.relays.forEach { r ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = OpenChatPalette.Screen,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.5.sp)
                    TextButton(
                        onClick = { Social.removeRelay(r) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text(stringResource(R.string.relays_remove), color = MaterialTheme.colorScheme.error, fontSize = 12.5.sp) }
                }
            }
        }
        if (social.relays.size < NostrDirect.MAX_EXTRA_RELAYS) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(200); bad = false },
                    placeholder = { Text("wss://relay.example.org", fontSize = 13.5.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    isError = bad,
                )
                FilledTonalButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        val url = NostrDirect.normalizeRelay(draft)
                        if (url == null) bad = true else { Social.addRelay(url); draft = "" }
                    },
                ) { Text(stringResource(R.string.relays_add)) }
            }
            if (bad) Text(stringResource(R.string.relays_invalid), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Blocked people, listed on the Me tab with a way back. */
@Composable
fun BlockedList() {
    val social by Social.state.collectAsState()
    if (social.blocked.isEmpty() && social.geoBlocked.isEmpty()) return
    SettingsGroupCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.me_blocked_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp))
            social.blocked.entries.forEachIndexed { idx, (fp, name) ->
                if (idx > 0) Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmblemView(fp.take(16), 28.dp)
                    Text(labelText(PeerLabel.mesh(name, fp.take(16))), Modifier.weight(1f), fontSize = 14.sp)
                    TextButton(onClick = { Social.unblock(fp) }) { Text(stringResource(R.string.peer_unblock)) }
                }
            }
            social.geoBlocked.entries.forEachIndexed { idx, (pk, name) ->
                if (idx > 0 || social.blocked.isNotEmpty()) Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmblemView(pk, 28.dp)
                    Text(labelText(PeerLabel.nostr(name, pk)), Modifier.weight(1f), fontSize = 14.sp)
                    TextButton(onClick = { Social.unblockGeo(pk) }) { Text(stringResource(R.string.peer_unblock)) }
                }
            }
        }
    }
}
