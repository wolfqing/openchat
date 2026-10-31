package org.openchat.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.aspectRatio
import android.media.MediaPlayer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import android.util.LruCache
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.ChatMessage
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.Commands
import org.openchat.android.app.Media
import org.openchat.android.app.VoiceRecorder
import org.openchat.android.app.Conversation
import org.openchat.android.app.DeliveryState
import org.openchat.android.app.PeerLabel
import org.openchat.android.app.Social
import org.openchat.android.core.Cashu
import org.openchat.android.core.Mentions
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatPane(
    conversation: String,
    emptyText: String,
    placeholder: String,
    onSend: (String) -> Unit,
    onSenderClick: ((ChatMessage) -> Unit)? = null,
    onVisible: (List<ChatMessage>) -> Unit = {},
) {
    val all by ChatRepository.messages.collectAsState()
    val social by Social.state.collectAsState()
    val list = all[conversation].orEmpty().filter { m ->
        when {
            m.mine -> true
            conversation.startsWith("geo:") || conversation.startsWith("gdm:") -> m.senderKey !in social.geoBlocked
            else -> !social.isBlockedPeer(m.senderKey)
        }
    }
    val state = rememberLazyListState()

    DisposableEffect(conversation) {
        ChatRepository.openConversation = conversation
        ChatRepository.clearUnread(conversation)
        onDispose { if (ChatRepository.openConversation == conversation) ChatRepository.openConversation = null }
    }
    // Follow the conversation only while the reader is at its end, or when the new line is theirs:
    // a history sync inserting older lines, or a timeline at its cap, must not yank them around.
    val atBottom by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 2
        }
    }
    val newest = list.lastOrNull()
    LaunchedEffect(newest?.id, newest?.senderKey) {
        if (newest != null && (newest.mine || atBottom)) state.animateScrollToItem(list.size - 1)
        ChatRepository.clearUnread(conversation)
        onVisible(list)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (list.isEmpty()) {
                Text(emptyText, Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
            } else {
                LazyColumn(state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.senderKey + it.id }) { m -> Bubble(m, onSenderClick) }
                }
            }
        }
        val peers by ChatRepository.peers.collectAsState()
        val mesh by ChatRepository.mesh.collectAsState()
        val names by ChatRepository.names.collectAsState()
        val persons = if (conversation.startsWith("geo:")) {
            list.filter { !it.mine && !it.system }.distinctBy { it.senderKey }.map { Commands.Person(PeerLabel.of(it), it.senderKey) }
        } else peers.filter { it.peerIdHex != mesh.myPeerHex }.map { Commands.Person(PeerLabel.mesh(it.nickname, it.peerIdHex), it.peerIdHex) }
        val dmPeer = conversation.takeIf { it.startsWith("dm:") }?.removePrefix("dm:")?.let { Commands.Person(PeerLabel.mesh(names[it], it), it) }
        val ctx = LocalContext.current
        Composer(conversation, placeholder, persons.map { it.label }) { text ->
            if (!Commands.isCommand(text)) onSend(text)
            else applyCommand(ctx, Commands.run(text, conversation, mesh.myNickname, persons, dmPeer), conversation, onSend)
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, onSenderClick: ((ChatMessage) -> Unit)?) {
    if (m.system) {
        Text(
            m.text, Modifier.fillMaxWidth().padding(horizontal = 8.dp), fontSize = 13.sp, lineHeight = 19.sp,
            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val time = remember(m.timestamp) { stampText(m.timestamp) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
        if (!m.mine) {
            Text(
                buildAnnotatedString {
                    val social by Social.state.collectAsState()
                    val mesh = !m.bridged && !m.conversation.startsWith("geo:")
                    append(labelText(PeerLabel.of(m), m.teleported, verified = mesh && social.isVerifiedPeer(m.senderKey), vouched = mesh && social.isVouchedPeer(m.senderKey)))
                    if (m.bridged) withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) { append(" · " + stringResource(R.string.bridge_via)) }
                }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.let {
                    if (onSenderClick != null && !m.bridged) it.clickable(role = Role.Button) { onSenderClick(m) }.defaultMinSize(minHeight = 32.dp).wrapContentHeight(Alignment.CenterVertically) else it
                },
            )
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            border = if (m.mentionsMe) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            when {
                m.attachment != null && m.audio -> VoiceNote(m.attachment)
                m.attachment != null -> PhotoView(m.attachment, covered = !m.mine)
                else -> Column {
                    Text(withMentions(m.text), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 16.sp)
                    Cashu.tokensIn(m.text).take(3).forEach { CashuCard(it) }
                }
            }
        }
        val status = if (m.mine) " · " + stringResource(
            when (m.state) {
                DeliveryState.SENDING -> R.string.state_sending; DeliveryState.SENT -> R.string.state_sent; DeliveryState.DELIVERED -> R.string.state_delivered
                DeliveryState.QUEUED -> R.string.state_queued; DeliveryState.CARRIED -> R.string.state_carried
                DeliveryState.READ -> R.string.state_read; DeliveryState.FAILED -> R.string.state_failed
            },
        ) else ""
        Text(time + status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A Cashu token in a message: what it is worth, and a hand-off to a wallet app (§28). */
@Composable
private fun CashuCard(token: String) {
    val ctx = LocalContext.current
    val info = remember(token) { Cashu.decode(token) } ?: return
    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
        Text((info.display ?: stringResource(R.string.pay_token)) + (info.mintHost?.let { " · $it" } ?: ""), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        info.memo?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("cashu:$token"))
                try { ctx.startActivity(intent) } catch (e: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(ctx, R.string.pay_no_wallet, android.widget.Toast.LENGTH_LONG).show()
                }
            }) { Text(stringResource(R.string.pay_open_wallet)) }
            TextButton(onClick = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("cashu", token))
                android.widget.Toast.makeText(ctx, R.string.pay_copied, android.widget.Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.pay_copy)) }
        }
    }
}

/** Photo picker and a tap-to-start, tap-to-send voice recorder (§18). */
@Composable
private fun MediaButtons(conversation: String) {
    val ctx = LocalContext.current
    val recorder = remember { VoiceRecorder(ctx) } // fallback when the live encoder is unavailable
    var recording by remember { mutableStateOf(false) }
    var viaRecorder by remember { mutableStateOf(false) }
    fun send(bytes: ByteArray?, mime: String) {
        val reason = if (bytes == null) R.string.media_too_big else ChatRepository.controller?.sendMedia(conversation, bytes, mime) ?: 0
        if (reason != 0) ChatRepository.addLocal(conversation, ctx.getString(reason))
    }
    // Recording streams live where it can (§29): people hear it while you talk.
    fun begin(): Boolean {
        viaRecorder = ChatRepository.controller?.startVoice(conversation) != true
        return if (viaRecorder) recorder.start() else true
    }
    fun stopAndSend() {
        recording = false
        if (viaRecorder) recorder.stop()?.let { f -> send(f.readBytes(), "audio/mp4"); f.delete() }
        else Thread { ChatRepository.controller?.finishVoice() }.start() // waits for the encoder to drain
    }
    fun cancel() { recording = false; if (viaRecorder) recorder.cancel() else ChatRepository.controller?.cancelVoice() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { send(Media.jpegFrom(ctx, it), "image/jpeg") } }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) recording = begin() }
    DisposableEffect(conversation) { onDispose { if (recording) cancel() } }
    LaunchedEffect(recording) { if (recording) { kotlinx.coroutines.delay(Media.MAX_VOICE_MS.toLong()); if (recording) stopAndSend() } }
    if (!recording) {
        IconButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.media_photo))
        }
    }
    val hint = stringResource(R.string.media_mic_hint)
    val stopHint = stringResource(R.string.media_stop)
    // What a screen reader's activation does: no holding or tapping in rhythm, one click starts, the next sends.
    fun toggle() {
        if (recording) { stopAndSend(); return }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) recording = begin() else mic.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    // Hold to talk, release to send; a quick tap keeps recording until the next tap.
    Box(
        Modifier.size(48.dp).clearAndSetSemantics {
            contentDescription = if (recording) stopHint else hint
            role = Role.Button
            onClick { toggle(); true }
        }.pointerInput(conversation) {
            detectTapGestures(onPress = {
                if (recording) { if (tryAwaitRelease()) stopAndSend(); return@detectTapGestures }
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) { mic.launch(android.Manifest.permission.RECORD_AUDIO); return@detectTapGestures }
                recording = begin()
                if (!recording) return@detectTapGestures
                val pressedAt = System.currentTimeMillis()
                if (!tryAwaitRelease()) cancel()
                else if (System.currentTimeMillis() - pressedAt >= 400) stopAndSend()
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (recording) Icons.Outlined.Stop else Icons.Outlined.Mic, contentDescription = null, tint = if (recording) MaterialTheme.colorScheme.error else LocalContentColor.current)
    }
    if (recording) Text(stringResource(R.string.media_recording), fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 8.dp))
}

/**
 * A received photo starts as coarse blocks and opens on a tap (§21.4), so nobody
 * standing nearby gets an unwanted picture shown to them on the screen.
 */
@Composable
private fun PhotoView(path: String, covered: Boolean) {
    var open by remember(path) { mutableStateOf(!covered) }
    // Decoding happens off the main thread; a bubble scrolled out and back finds its bitmap in the cache.
    val decoded by produceState(thumbnails.get(path), path) {
        if (value == null) value = withContext(Dispatchers.IO) { Media.thumbnail(path)?.also { thumbnails.put(path, it) } }
    }
    val bmp = decoded ?: run { Icon(Icons.Outlined.Image, contentDescription = stringResource(R.string.media_photo), modifier = Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); return }
    val shown = remember(path, open, bmp) { (if (open) bmp else Media.pixelate(bmp)).asImageBitmap() }
    Box(contentAlignment = Alignment.Center) {
        Image(
            shown, contentDescription = stringResource(R.string.media_photo),
            filterQuality = if (open) FilterQuality.Medium else FilterQuality.None,
            contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = 260.dp).heightIn(max = 320.dp).aspectRatio(bmp.width.toFloat() / bmp.height).padding(4.dp)
                .let { if (!open) it.clickable { open = true } else it },
        )
        if (!open) {
            Text(
                stringResource(R.string.media_tap_to_show), fontSize = 13.sp, color = Color.White,
                modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

private val thumbnails = LruCache<String, android.graphics.Bitmap>(24)
private val voiceDurations = LruCache<String, Int>(64)

@Composable
private fun VoiceNote(path: String) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    // The length is read off the main thread once per file, not on every composition.
    val duration by produceState(voiceDurations.get(path) ?: 0, path) {
        if (voiceDurations.get(path) == null) value = withContext(Dispatchers.IO) {
            try { MediaPlayer().run { setDataSource(path); prepare(); duration.also { release() } } } catch (e: Exception) { 0 }
        }.also { voiceDurations.put(path, it) }
    }
    DisposableEffect(path) { onDispose { player?.release() } }
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val label = stringResource(if (player != null) R.string.media_stop else R.string.media_play)
        IconButton(
            onClick = {
                val p = player
                if (p != null) { p.release(); player = null }
                else player = try { MediaPlayer().apply { setDataSource(path); setOnCompletionListener { it.release(); player = null }; prepare(); start() } } catch (e: Exception) { null }
            },
            modifier = Modifier.semantics { contentDescription = label },
        ) { Icon(if (player != null) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, contentDescription = null) } // the button itself says play or stop
        Text(stringResource(R.string.media_voice) + " · " + "%d:%02d".format(duration / 60_000, (duration / 1000) % 60), fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
    }
}

/** Carries out a command outcome; local lines go into this chat only. */
private fun applyCommand(ctx: android.content.Context, o: Commands.Outcome, conversation: String, onSend: (String) -> Unit) {
    fun local(text: String) = ChatRepository.addLocal(conversation, text)
    when (o) {
        is Commands.Outcome.Send -> onSend(o.text)
        is Commands.Outcome.Clear -> ChatRepository.clear(conversation)
        is Commands.Outcome.OpenPrivate -> {
            o.text?.let { ChatRepository.controller?.sendPrivate(o.peerHex, it) }
            ChatRepository.requestOpen(Conversation.Private(o.peerHex).key)
        }
        is Commands.Outcome.Block -> {
            if (conversation.startsWith("geo:")) Social.blockGeo(o.person.key, o.person.label.name)
            else meshFingerprint(o.person.key)?.let { Social.block(it, o.person.label.name) }
            local(ctx.getString(R.string.cmd_blocked, o.person.label.toString()))
        }
        is Commands.Outcome.Unblock -> {
            val s = Social.state.value
            val n = o.name.lowercase()
            val mesh = s.blocked.entries.firstOrNull { (fp, name) -> name.lowercase() == n || PeerLabel.mesh(name, fp.take(16)).toString().lowercase() == n }
            val geo = s.geoBlocked.entries.firstOrNull { (pk, name) -> name.lowercase() == n || PeerLabel.nostr(name, pk).toString().lowercase() == n }
            when {
                mesh != null -> { Social.unblock(mesh.key); local(ctx.getString(R.string.cmd_unblocked, o.name)) }
                geo != null -> { Social.unblockGeo(geo.key); local(ctx.getString(R.string.cmd_unblocked, o.name)) }
                else -> local(ctx.getString(R.string.cmd_not_blocked, o.name))
            }
        }
        is Commands.Outcome.Favorite -> {
            ChatRepository.controller?.setFavorite(o.person.key, o.person.label.name, o.on)
            local(ctx.getString(if (o.on) R.string.cmd_favorited else R.string.cmd_unfavorited, o.person.label.toString()))
        }
        is Commands.Outcome.GroupCreate -> ChatRepository.controller?.createGroup(o.name)?.let { ChatRepository.requestOpen(it) }
        is Commands.Outcome.GroupList -> local(
            ChatRepository.groups.value.values.joinToString("\n") { "#${it.name} · ${it.members.size}/${org.openchat.android.core.Group.MAX_MEMBERS}" + if (it.iAmCreator) " \u2605\uFE0E" else "" }
                .ifEmpty { ctx.getString(R.string.group_none) },
        )
        is Commands.Outcome.GroupInvite, is Commands.Outcome.GroupRemove, Commands.Outcome.GroupLeave -> {
            val id = conversation.removePrefix("grp:").takeIf { conversation.startsWith("grp:") } ?: return local(ctx.getString(R.string.group_open_first))
            val c = ChatRepository.controller ?: return
            when (o) {
                is Commands.Outcome.GroupInvite -> c.groupInvite(id, o.person.key)
                is Commands.Outcome.GroupRemove -> {
                    val n = o.name.lowercase()
                    ChatRepository.groups.value[id]?.members?.firstOrNull { m ->
                        m.nickname.lowercase() == n || PeerLabel.mesh(m.nickname, m.fingerprint.take(16)).toString().lowercase() == n
                    }?.let { c.groupRemove(id, it.fingerprint) } ?: local(ctx.getString(R.string.cmd_not_found, o.name))
                }
                else -> c.groupLeave(id) // the group screen closes itself once the group is gone
            }
        }
        is Commands.Outcome.Pay -> {
            onSend(o.token)
            local(ctx.getString(if (o.private) R.string.pay_sent_private else R.string.pay_sent_public, o.summary))
        }
        is Commands.Outcome.Ping -> {
            local(ctx.getString(R.string.cmd_ping_started, o.person.label.toString()))
            ChatRepository.controller?.ping(o.person.key, o.person.label.toString(), conversation)
        }
        is Commands.Outcome.Trace -> {
            val path = ChatRepository.controller?.estimatePath(o.person.key)
            local(
                if (path == null) ctx.getString(R.string.cmd_trace_none, o.person.label.toString())
                else ctx.getString(R.string.cmd_trace_path, (listOf(ctx.getString(R.string.cmd_trace_you)) + path + o.person.label.toString()).joinToString(" → "), path.size + 1),
            )
        }
        is Commands.Outcome.Drop -> local(ctx.getString(if (ChatRepository.controller?.dropNote(o.text) == true) R.string.note_dropped else R.string.note_no_location))
        is Commands.Outcome.Local -> local(
            when (o.note) {
                Commands.Note.HELP -> ctx.getString(R.string.cmd_help) + "\n" + ctx.getString(R.string.cmd_help_group) + "\n" + ctx.getString(R.string.cmd_help_diag) + "\n" + ctx.getString(R.string.cmd_help_pay)
                Commands.Note.WHO -> ctx.getString(R.string.cmd_who, o.arg)
                Commands.Note.WHO_NONE -> ctx.getString(R.string.cmd_who_none)
                Commands.Note.NOT_FOUND -> ctx.getString(R.string.cmd_not_found, o.arg)
                Commands.Note.USAGE -> ctx.getString(R.string.cmd_usage, o.arg)
                Commands.Note.UNKNOWN -> ctx.getString(R.string.cmd_unknown, o.arg)
                Commands.Note.MESH_ONLY -> ctx.getString(R.string.cmd_mesh_only)
                Commands.Note.NO_LOCATION -> ctx.getString(R.string.cmd_geo_only)
                Commands.Note.PAY_NOT_TOKEN -> ctx.getString(R.string.pay_not_token)
                Commands.Note.PAY_INVALID -> ctx.getString(R.string.pay_invalid)
                Commands.Note.PAY_CONFIRM_PUBLIC -> ctx.getString(R.string.pay_confirm_public)
                else -> o.arg
            },
        )
    }
}

/** Message text with every @mention set in the accent colour. */
@Composable
internal fun withMentions(text: String): AnnotatedString {
    val ranges = Mentions.ranges(text)
    if (ranges.isEmpty()) return AnnotatedString(text)
    val accent = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var at = 0
        for (r in ranges) {
            append(text.substring(at, r.first))
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(text.substring(r.first, r.last + 1)) }
            at = r.last + 1
        }
        append(text.substring(at))
    }
}

/** "HH:mm" today, otherwise the short local date as well. */
internal fun stampText(ts: Long): String {
    val locale = Locale.getDefault()
    val clock = SimpleDateFormat("HH:mm", locale).format(Date(ts))
    val day = SimpleDateFormat("yyyyDDD", locale)
    return if (day.format(Date(ts)) == day.format(Date())) clock
    else DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(ts)) + " " + clock
}

/** Unsent text per conversation, kept while the user switches tabs. Cleared by the panic wipe. */
internal val drafts = HashMap<String, String>()

@Composable
private fun Composer(conversation: String, placeholder: String, people: List<PeerLabel>, onSend: (String) -> Unit) {
    var text by remember(conversation) { mutableStateOf(drafts[conversation].orEmpty()) }
    fun set(t: String) { text = t; if (t.isEmpty()) drafts.remove(conversation) else drafts[conversation] = t }
    val send = { val t = text.trim(); if (t.isNotEmpty()) { onSend(t); set("") } }
    HorizontalDivider()
    if (text.startsWith("/") && !text.contains(' ')) {
        val cmds = Commands.available(conversation).filter { it.startsWith(text.lowercase()) }
        if (cmds.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                cmds.forEach { c -> TextButton(onClick = { set("$c ") }) { Text(c, fontFamily = FontFamily.Monospace) } }
            }
        }
    }
    val typing = Mentions.typing(text)
    if (typing != null) {
        val matches = people.filter { it.toString().lowercase().startsWith(typing.lowercase()) }.take(6)
        if (matches.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                matches.forEach { p ->
                    // A name shared with someone else gets its #suffix, so the mention is unambiguous.
                    val token = if (people.count { it.name.equals(p.name, ignoreCase = true) } > 1) p.toString() else p.name
                    TextButton(onClick = { set(text.substring(0, text.lastIndexOf('@')) + "@" + token + " ") }) { Text(labelText(p)) }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!conversation.startsWith("geo:") && !conversation.startsWith("gdm:") && !conversation.startsWith("grp:")) MediaButtons(conversation)
        OutlinedTextField(
            value = text, onValueChange = { if (it.length <= 1000) set(it) },
            placeholder = { Text(placeholder) },
            modifier = Modifier.weight(1f),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        IconButton(onClick = send) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send), tint = MaterialTheme.colorScheme.primary) }
    }
}
