package org.openchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.AppLanguage
import org.openchat.android.app.ChatMessage
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.Commands
import org.openchat.android.app.Media
import org.openchat.android.app.VoiceRecorder
import org.openchat.android.app.Conversation
import org.openchat.android.app.DeliveryState
import org.openchat.android.app.PeerLabel
import org.openchat.android.app.Social
import org.openchat.android.core.Mentions
import org.openchat.android.nostr.Geohash
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Dark palette from docs/design/ui-prototype.html: signal orange on near-black.
private val Signal = Color(0xFFFF7840)
private val SignalSoft = Color(0xFF3A1A0C)

/** Dark by default, whatever the system setting. */
@Composable
fun OpenChatTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Signal, onPrimary = Color(0xFF1A0A03),
        primaryContainer = SignalSoft, onPrimaryContainer = Color(0xFFEDF1EF),
        secondary = Signal, onSecondary = Color(0xFF1A0A03),
        secondaryContainer = SignalSoft, onSecondaryContainer = Signal,
        background = Color(0xFF0B0D0D), onBackground = Color(0xFFEDF1EF),
        surface = Color(0xFF0B0D0D), onSurface = Color(0xFFEDF1EF),
        surfaceVariant = Color(0xFF121616), onSurfaceVariant = Color(0xFF9AA4A3),
        outline = Color(0xFF4A5555), outlineVariant = Color(0xFF262D2D),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun plural(@PluralsRes id: Int, count: Int): String = LocalContext.current.resources.getQuantityString(id, count, count)

/** One button naming the current language; a menu lists the rest. Choosing one restarts the screen in that language. */
@Composable
fun LanguagePicker(onLanguage: (AppLanguage.Choice) -> Unit) {
    val current = AppLanguage.get(LocalContext.current)
    var open by remember { mutableStateOf(false) }
    val system = stringResource(R.string.lang_system)
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(current.label ?: system)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppLanguage.Choice.entries.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.label ?: system, fontWeight = if (c == current) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; if (c != current) onLanguage(c) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- onboarding

@Composable
fun OnboardingScreen(onStart: () -> Unit, onLanguage: (AppLanguage.Choice) -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) { LanguagePicker(onLanguage) }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.onboard_title), fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.onboard_body),
                fontSize = 17.sp, lineHeight = 26.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.onboard_no_account), fontSize = 17.sp, lineHeight = 26.sp)
            Spacer(Modifier.height(24.dp))
            SafetyNote()
            Spacer(Modifier.height(32.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(stringResource(R.string.onboard_start), fontSize = 18.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.onboard_next), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SafetyNote() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.safety_title), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.safety_body),
                fontSize = 14.sp, lineHeight = 21.sp,
            )
        }
    }
}

// ---------------------------------------------------------------- main shell

private enum class Tab(@StringRes val label: Int) { NEARBY(R.string.tab_nearby), PRIVATE(R.string.tab_private), LOCATION(R.string.tab_location), ME(R.string.tab_me) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    missingPermissions: Boolean,
    onGrantPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onRequestLocation: () -> Unit,
    onShareApp: () -> Unit,
    onLanguage: (AppLanguage.Choice) -> Unit,
    onScan: () -> Unit,
    scanResult: String?,
    onScanResultSeen: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.NEARBY) }
    var openChat by rememberSaveable { mutableStateOf<String?>(null) } // conversation key
    var wiped by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf<PersonRef?>(null) }
    val unread by ChatRepository.unread.collectAsState()
    val mesh by ChatRepository.mesh.collectAsState()
    val peers by ChatRepository.peers.collectAsState()

    val onPanic = {
        ChatRepository.controller?.panic()
        drafts.clear()
        openChat = null
        tab = Tab.NEARBY
        wiped = true
    }

    if (wiped) {
        AlertDialog(
            onDismissRequest = { wiped = false },
            confirmButton = { TextButton(onClick = { wiped = false }) { Text(stringResource(R.string.ok)) } },
            text = { Text(stringResource(R.string.wiped, PeerLabel.mesh(mesh.myNickname, mesh.myPeerHex).toString())) },
        )
    }

    if (scanResult != null) {
        AlertDialog(
            onDismissRequest = onScanResultSeen,
            confirmButton = { TextButton(onClick = onScanResultSeen) { Text(stringResource(R.string.ok)) } },
            text = { Text(if (scanResult.isEmpty()) stringResource(R.string.verify_scan_bad) else stringResource(R.string.verify_scan_ok, scanResult)) },
        )
    }
    val openRequest by ChatRepository.openRequest.collectAsState()
    LaunchedEffect(openRequest) { openRequest?.let { openChat = it; ChatRepository.requestOpen(null) } }
    person?.let { p -> PersonSheet(p, onDismiss = { person = null }, onMessage = { openChat = Conversation.Private(it).key }) }
    openChat?.takeIf { it.startsWith("grp:") }?.let { key ->
        GroupChatScreen(key.removePrefix("grp:"), onBack = { openChat = null }, onPanic = onPanic)
        return
    }
    Conversation.GeoPrivate.parse(openChat ?: "")?.let { c ->
        GeoPrivateChatScreen(c, onBack = { openChat = null }, onPanic = onPanic)
        return
    }

    val chat = openChat
    if (chat != null && chat.startsWith("dm:")) {
        PrivateChatScreen(chat.removePrefix("dm:"), onBack = { openChat = null }, onPanic = onPanic)
        return
    }
    if (chat == BOARD) {
        BoardScreen(onBack = { openChat = null }, onPanic = onPanic)
        return
    }
    if (chat != null && chat.startsWith("geo:")) {
        GeoChatScreen(chat.removePrefix("geo:"), onBack = { openChat = null }, onPanic = onPanic)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { PanicLogo(onPanic) },
                title = { PanicTitle(stringResource(tab.label), onPanic) },
                actions = {
                    if (tab == Tab.NEARBY) {
                        val unseen by ChatRepository.boardUnseen.collectAsState()
                        TextButton(onClick = { openChat = BOARD }) {
                            BadgedBox(badge = { if (unseen > 0) Badge { Text("$unseen") } }) { Text(stringResource(R.string.board_title)) }
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    val count = when (t) {
                        Tab.NEARBY -> unread[Conversation.Mesh.key] ?: 0
                        Tab.PRIVATE -> unread.filterKeys { it.startsWith("dm:") || it.startsWith("gdm:") || it.startsWith("grp:") }.values.sum()
                        Tab.LOCATION -> unread.filterKeys { it.startsWith("geo:") }.values.sum()
                        Tab.ME -> 0
                    }
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        label = { Text(stringResource(t.label)) },
                        icon = {
                            BadgedBox(badge = { if (count > 0) Badge { Text("$count") } }) {
                                Icon(
                                    when (t) { Tab.NEARBY -> Icons.Filled.Share; Tab.PRIVATE -> Icons.Filled.Lock; Tab.LOCATION -> Icons.Filled.LocationOn; Tab.ME -> Icons.Filled.Person },
                                    contentDescription = stringResource(t.label),
                                )
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusBanner(missingPermissions, mesh.storageLocked, mesh.bluetoothOn, mesh.running, peers.size, onGrantPermissions, onEnableBluetooth)
            when (tab) {
                Tab.NEARBY -> ChatPane(
                    conversation = Conversation.Mesh.key,
                    emptyText = if (peers.isEmpty()) stringResource(R.string.nearby_empty_alone) else plural(R.plurals.nearby_empty_peers, peers.size),
                    placeholder = stringResource(R.string.nearby_placeholder),
                    onSend = { ChatRepository.controller?.sendPublic(it) },
                    onSenderClick = { m -> if (m.senderKey != mesh.myPeerHex) person = PersonRef.Mesh(m.senderKey, m.senderName) },
                )
                Tab.PRIVATE -> PrivateList(onOpen = { openChat = if (it.startsWith("gdm:") || it.startsWith("grp:")) it else Conversation.Private(it).key })
                Tab.LOCATION -> LocationPane(onRequestLocation, onOpen = { openChat = Conversation.Geo(it).key })
                Tab.ME -> MePane(onShareApp, onLanguage, onScan)
            }
        }
    }
}

/** Name with its #xxxx suffix set smaller, monospaced and muted, so the name itself leads. */
@Composable
fun labelText(label: PeerLabel, teleported: Boolean = false, verified: Boolean = false): AnnotatedString = buildAnnotatedString {
    append(label.name)
    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 0.8.em, color = MaterialTheme.colorScheme.onSurfaceVariant)) {
        append(label.suffix)
    }
    if (verified) withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(" ✓") }
    if (teleported) append(" ✈")
}

/** Title that wipes everything on a triple tap, with no confirmation (docs/protocol-spec.md §10). */
@Composable
fun PanicTitle(text: String, onPanic: () -> Unit) = PanicTitle(AnnotatedString(text), onPanic)

/**
 * Three taps in a row fire [onPanic]. Each tap must follow the previous one within
 * [PANIC_TAP_GAP_MS]; a slower tap starts a new count. Every counted tap gives a short
 * haptic tick so the user can feel that taps register, since there is no ripple.
 */
private const val PANIC_TAP_GAP_MS = 500L

@Composable
private fun rememberPanicTaps(onPanic: () -> Unit): () -> Unit {
    var taps by remember { mutableStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    val haptic = LocalHapticFeedback.current
    return {
        val now = System.currentTimeMillis()
        if (now - lastTap > PANIC_TAP_GAP_MS) taps = 0
        lastTap = now
        taps++
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (taps >= 3) { taps = 0; lastTap = 0; onPanic() }
    }
}

@Composable
fun PanicTitle(text: AnnotatedString, onPanic: () -> Unit) {
    val tap = rememberPanicTaps(onPanic)
    Text(
        text,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = tap),
    )
}

/** The app mark in the top-left corner: a 48dp target for the triple-tap wipe. */
@Composable
fun PanicLogo(onPanic: () -> Unit) {
    val tap = rememberPanicTaps(onPanic)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = tap)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        // The launcher foreground keeps its pixels inside the adaptive-icon safe zone (about half
        // the canvas), so scale it up to fill the box.
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = stringResource(R.string.panic_logo),
            modifier = Modifier.fillMaxSize().scale(1.7f),
        )
    }
}

@Composable
private fun StatusBanner(missing: Boolean, locked: Boolean, btOn: Boolean, running: Boolean, peers: Int, onGrant: () -> Unit, onEnable: () -> Unit) {
    val ctx = LocalContext.current
    val (text, action) = when {
        locked -> stringResource(R.string.banner_storage_locked) to { org.openchat.android.app.MeshService.start(ctx) }
        missing -> stringResource(R.string.banner_need_permission) to onGrant
        !btOn -> stringResource(R.string.banner_bt_off) to onEnable
        !running -> stringResource(R.string.banner_starting) to null
        peers == 0 -> stringResource(R.string.banner_searching) to null
        else -> plural(R.plurals.banner_connected, peers) to null
    }
    val warn = missing || locked || !btOn
    val connected = !warn && running && peers > 0
    Box(
        Modifier.fillMaxWidth()
            .background(if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)
            .let { if (action != null) it.clickable { action() } else it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text, fontSize = 14.sp,
            fontFamily = if (connected) FontFamily.Monospace else null,
            color = when {
                warn -> MaterialTheme.colorScheme.onErrorContainer
                connected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

// ---------------------------------------------------------------- chat

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
    LaunchedEffect(list.size) {
        if (list.isNotEmpty()) state.animateScrollToItem(list.size - 1)
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
                    append(labelText(PeerLabel.of(m), m.teleported, verified = !m.bridged && !m.conversation.startsWith("geo:") && Social.state.collectAsState().value.isVerifiedPeer(m.senderKey)))
                    if (m.bridged) withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) { append(" · " + stringResource(R.string.bridge_via)) }
                }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.let { if (onSenderClick != null && !m.bridged) it.clickable { onSenderClick(m) } else it },
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
                else -> Text(withMentions(m.text), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 16.sp)
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

/** Photo picker and a tap-to-start, tap-to-send voice recorder (§18). */
@Composable
private fun MediaButtons(conversation: String) {
    val ctx = LocalContext.current
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    fun send(bytes: ByteArray?, mime: String) {
        val reason = if (bytes == null) R.string.media_too_big else ChatRepository.controller?.sendMedia(conversation, bytes, mime) ?: 0
        if (reason != 0) ChatRepository.addLocal(conversation, ctx.getString(reason))
    }
    fun stopAndSend() { recording = false; recorder.stop()?.let { f -> send(f.readBytes(), "audio/mp4"); f.delete() } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { send(Media.jpegFrom(ctx, it), "image/jpeg") } }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) recording = recorder.start() }
    DisposableEffect(conversation) { onDispose { recorder.cancel() } }
    LaunchedEffect(recording) { if (recording) { kotlinx.coroutines.delay(Media.MAX_VOICE_MS.toLong()); if (recording) stopAndSend() } }
    if (!recording) {
        IconButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.media_photo))
        }
    }
    IconButton(onClick = {
        if (recording) stopAndSend()
        else if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) recording = recorder.start()
        else mic.launch(android.Manifest.permission.RECORD_AUDIO)
    }) {
        Text(if (recording) "■" else "🎙", color = if (recording) MaterialTheme.colorScheme.error else Color.Unspecified, fontSize = 20.sp)
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
    val bmp = remember(path) { Media.thumbnail(path) }
    if (bmp == null) { Text("🖼", Modifier.padding(12.dp)); return }
    val shown = remember(path, open) { (if (open) bmp else Media.pixelate(bmp)).asImageBitmap() }
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

@Composable
private fun VoiceNote(path: String) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val duration = remember(path) {
        try { MediaPlayer().run { setDataSource(path); prepare(); duration.also { release() } } } catch (e: Exception) { 0 }
    }
    DisposableEffect(path) { onDispose { player?.release() } }
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            val p = player
            if (p != null) { p.release(); player = null }
            else player = try { MediaPlayer().apply { setDataSource(path); setOnCompletionListener { it.release(); player = null }; prepare(); start() } } catch (e: Exception) { null }
        }) { Text(if (player != null) "■" else "▶", fontSize = 18.sp) }
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
            ChatRepository.groups.value.values.joinToString("\n") { "#${it.name} · ${it.members.size}/${org.openchat.android.core.Group.MAX_MEMBERS}" + if (it.iAmCreator) " ★" else "" }
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
        is Commands.Outcome.Drop -> local(ctx.getString(if (ChatRepository.controller?.dropNote(o.text) == true) R.string.note_dropped else R.string.note_no_location))
        is Commands.Outcome.Local -> local(
            when (o.note) {
                Commands.Note.HELP -> ctx.getString(R.string.cmd_help) + "\n" + ctx.getString(R.string.cmd_help_group)
                Commands.Note.WHO -> ctx.getString(R.string.cmd_who, o.arg)
                Commands.Note.WHO_NONE -> ctx.getString(R.string.cmd_who_none)
                Commands.Note.NOT_FOUND -> ctx.getString(R.string.cmd_not_found, o.arg)
                Commands.Note.USAGE -> ctx.getString(R.string.cmd_usage, o.arg)
                Commands.Note.UNKNOWN -> ctx.getString(R.string.cmd_unknown, o.arg)
                Commands.Note.MESH_ONLY -> ctx.getString(R.string.cmd_mesh_only)
                Commands.Note.NO_LOCATION -> ctx.getString(R.string.cmd_geo_only)
                else -> o.arg
            },
        )
    }
}

/** Message text with every @mention set in the accent colour. */
@Composable
private fun withMentions(text: String): AnnotatedString {
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
private val drafts = HashMap<String, String>()

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateChatScreen(peerHex: String, onBack: () -> Unit, onPanic: () -> Unit) {
    val names by ChatRepository.names.collectAsState()
    val peers by ChatRepository.peers.collectAsState()
    val peer = peers.firstOrNull { it.peerIdHex == peerHex }
    val label = PeerLabel.mesh(peer?.nickname ?: names[peerHex], peerHex)
    val name = label.toString()
    val readSent = remember { HashSet<String>() }
    var sheet by remember { mutableStateOf(false) }
    val social by Social.state.collectAsState()
    BackHandler(onBack = onBack)
    if (sheet) PersonSheet(PersonRef.Mesh(peerHex, peer?.nickname ?: names[peerHex]), onDismiss = { sheet = false }, onMessage = {})
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = {
                Column {
                    PanicTitle(labelText(label, verified = social.isVerifiedPeer(peerHex)), onPanic)
                    Text(
                        stringResource(
                            when {
                                social.isBlockedPeer(peerHex) -> R.string.peer_blocked_short
                                peer != null -> R.string.dm_status_here
                                social.favoriteOf(peerHex)?.let { it.mine && it.theyFavorited && it.theirNpub != null } == true -> R.string.dm_status_internet
                                else -> R.string.dm_status_away
                            },
                        ),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = { IconButton(onClick = { sheet = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.peer_more)) } },
        )
    }) { padding ->
        Box(Modifier.padding(padding)) {
            ChatPane(
                conversation = Conversation.Private(peerHex).key,
                emptyText = stringResource(R.string.dm_empty, name),
                placeholder = stringResource(R.string.dm_placeholder, name),
                onSend = { ChatRepository.controller?.sendPrivate(peerHex, it) },
                onVisible = { list ->
                    val unreadIds = list.filter { !it.mine && readSent.add(it.id) }.map { it.id }
                    if (unreadIds.isNotEmpty()) ChatRepository.controller?.markRead(peerHex, unreadIds)
                },
            )
        }
    }
}

@Composable
private fun NewGroupDialog(onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_new)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it.take(org.openchat.android.core.Group.MAX_NAME) }, singleLine = true, placeholder = { Text(stringResource(R.string.group_name_hint)) })
                Text(stringResource(R.string.group_new_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { ChatRepository.controller?.createGroup(name)?.let(onCreated) }) { Text(stringResource(R.string.group_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A private group: sealed with the group key, flooded over the mesh (§22). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(idHex: String, onBack: () -> Unit, onPanic: () -> Unit) {
    val groups by ChatRepository.groups.collectAsState()
    val g = groups[idHex]
    var members by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    if (g == null) { LaunchedEffect(Unit) { onBack() }; return }
    if (members) GroupMembersDialog(g, onDismiss = { members = false }, onLeave = { members = false; ChatRepository.controller?.groupLeave(idHex); onBack() })
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = {
                Column {
                    PanicTitle("#" + g.name, onPanic)
                    Text(stringResource(R.string.group_status, plural(R.plurals.group_members, g.members.size)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = { TextButton(onClick = { members = true }) { Text(stringResource(R.string.group_members_title)) } },
        )
    }) { padding ->
        Box(Modifier.padding(padding)) {
            ChatPane(
                conversation = Conversation.Group(idHex).key,
                emptyText = stringResource(R.string.group_empty),
                placeholder = stringResource(R.string.group_placeholder, g.name),
                onSend = { ChatRepository.controller?.sendGroup(idHex, it) },
            )
        }
    }
}

@Composable
private fun GroupMembersDialog(g: org.openchat.android.app.GroupInfo, onDismiss: () -> Unit, onLeave: () -> Unit) {
    val peers by ChatRepository.peers.collectAsState()
    val memberIds = g.members.map { it.fingerprint.take(16) }.toSet()
    val invitable = peers.filter { it.peerIdHex !in memberIds }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = { TextButton(onClick = onLeave) { Text(stringResource(R.string.group_leave), color = MaterialTheme.colorScheme.error) } },
        title = { Text("#" + g.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                g.members.forEach { m ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildAnnotatedString {
                                append(labelText(PeerLabel.mesh(m.nickname, m.fingerprint.take(16))))
                                if (m.fingerprint == g.myFingerprint) append(" · " + stringResource(R.string.group_you))
                            },
                            Modifier.weight(1f), fontSize = 14.sp,
                        )
                        if (m.fingerprint == g.creatorFingerprint) {
                            Text("★ " + stringResource(R.string.group_creator), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        } else if (g.iAmCreator) {
                            TextButton(onClick = { ChatRepository.controller?.groupRemove(g.idHex, m.fingerprint) }) { Text(stringResource(R.string.group_remove)) }
                        }
                    }
                }
                if (g.iAmCreator) {
                    Text(stringResource(R.string.group_invite_title), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                    if (invitable.isEmpty()) Text(stringResource(R.string.group_none_nearby), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    invitable.forEach { p ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(labelText(PeerLabel.mesh(p.nickname, p.peerIdHex)), Modifier.weight(1f), fontSize = 14.sp)
                            TextButton(enabled = g.members.size < org.openchat.android.core.Group.MAX_MEMBERS, onClick = { ChatRepository.controller?.groupInvite(g.idHex, p.peerIdHex) }) {
                                Text(stringResource(R.string.group_invite))
                            }
                        }
                    }
                } else {
                    Text(stringResource(R.string.group_creator_manages), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
    )
}

/** Private chat with someone met in a location channel, over Nostr under my persona for that cell. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeoPrivateChatScreen(c: Conversation.GeoPrivate, onBack: () -> Unit, onPanic: () -> Unit) {
    val names by ChatRepository.names.collectAsState()
    val label = PeerLabel.nostr(names[c.pubkey], c.pubkey)
    val readSent = remember { HashSet<String>() }
    BackHandler(onBack = onBack)
    LaunchedEffect(c) { ChatRepository.controller?.joinGeo(c.geohash, teleported = ChatRepository.geo.value.locationGeohash?.startsWith(c.geohash) != true) }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = {
                Column {
                    PanicTitle(labelText(label), onPanic)
                    Text(stringResource(R.string.geo_dm_status, c.geohash), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }) { padding ->
        Box(Modifier.padding(padding)) {
            ChatPane(
                conversation = c.key,
                emptyText = stringResource(R.string.geo_dm_empty, label.toString(), c.geohash),
                placeholder = stringResource(R.string.dm_placeholder, label.toString()),
                onSend = { ChatRepository.controller?.sendGeoPrivate(c.geohash, c.pubkey, it) },
                onVisible = { list ->
                    val unreadIds = list.filter { !it.mine && !it.system && readSent.add(it.id) }.map { it.id }
                    if (unreadIds.isNotEmpty()) ChatRepository.controller?.markRead(c.key, unreadIds)
                },
            )
        }
    }
}

@Composable
private fun PrivateList(onOpen: (String) -> Unit) {
    val peers by ChatRepository.peers.collectAsState()
    val all by ChatRepository.messages.collectAsState()
    val names by ChatRepository.names.collectAsState()
    val unread by ChatRepository.unread.collectAsState()
    val me by ChatRepository.mesh.collectAsState()
    val social by Social.state.collectAsState()
    val withHistory = all.keys.filter { it.startsWith("dm:") }.map { it.removePrefix("dm:") }
    val ids = (peers.map { it.peerIdHex } + withHistory).distinct().filter { it != me.myPeerHex }
    val geoChats = all.keys.filter { it.startsWith("gdm:") }
    val groups by ChatRepository.groups.collectAsState()
    var creating by remember { mutableStateOf(false) }
    if (creating) NewGroupDialog(onDismiss = { creating = false }, onCreated = { creating = false; onOpen(it) })
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { creating = true }) { Text(stringResource(R.string.group_new)) }
    }
    if (ids.isEmpty() && geoChats.isEmpty() && groups.isEmpty()) {
        Box(Modifier.fillMaxSize()) {
            Text(stringResource(R.string.dm_list_empty), Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn {
        items(groups.values.toList(), key = { "grp:" + it.idHex }) { g ->
            val key = Conversation.Group(g.idHex).key
            Row(Modifier.fillMaxWidth().clickable { onOpen(key) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("#", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, fontWeight = FontWeight.SemiBold)
                    Text(all[key]?.lastOrNull { !it.system }?.text ?: plural(R.plurals.group_members, g.members.size), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                val n = unread[key] ?: 0
                if (n > 0) Badge { Text("$n") }
            }
            HorizontalDivider()
        }
        items(geoChats, key = { it }) { key ->
            val c = Conversation.GeoPrivate.parse(key)!!
            val last = all[key]?.lastOrNull()
            Row(Modifier.fillMaxWidth().clickable { onOpen(key) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(buildAnnotatedString { append(labelText(PeerLabel.nostr(names[c.pubkey], c.pubkey))); append("  #" + c.geohash) }, fontWeight = FontWeight.SemiBold)
                    Text(last?.text.orEmpty(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                val n = unread[key] ?: 0
                if (n > 0) Badge { Text("$n") }
            }
            HorizontalDivider()
        }
        items(ids, key = { it }) { hex ->
            val peer = peers.firstOrNull { it.peerIdHex == hex }
            val last = all["dm:$hex"]?.lastOrNull()
            Row(Modifier.fillMaxWidth().clickable { onOpen(hex) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (peer != null) Color(0xFF2EB872) else Color.Gray, CircleShape))
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    val fav = social.favoriteOf(hex)
                    Text(
                        buildAnnotatedString {
                            append(labelText(PeerLabel.mesh(peer?.nickname ?: names[hex], hex), verified = social.isVerifiedPeer(hex)))
                            if (fav?.mine == true) withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(" ★") }
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        last?.text ?: stringResource(if (peer?.direct == true) R.string.dm_direct else if (peer != null) R.string.dm_relayed else R.string.dm_away),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
                val n = unread["dm:$hex"] ?: 0
                if (n > 0) Badge { Text("$n") }
            }
            HorizontalDivider()
        }
    }
}

// ---------------------------------------------------------------- location channels

@Composable
private fun LocationPane(onRequestLocation: () -> Unit, onOpen: (String) -> Unit) {
    val geo by ChatRepository.geo.collectAsState()
    var manual by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.geo_intro), lineHeight = 22.sp)
        Spacer(Modifier.height(16.dp))
        val here = geo.locationGeohash
        if (here == null) {
            FilledTonalButton(onClick = onRequestLocation) { Text(stringResource(R.string.geo_use_location)) }
        } else {
            Geohash.Level.entries.forEach { level ->
                val cell = here.take(level.precision)
                val n = geo.participants[cell] ?: 0
                OutlinedButton(
                    onClick = { ChatRepository.controller?.joinGeo(cell, teleported = false); onOpen(cell) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text("${stringResource(levelLabel(level))} · #$cell" + if (n > 0) " · " + plural(R.plurals.geo_online, n) else "", Modifier.weight(1f))
                }
            }
            TextButton(onClick = onRequestLocation) { Text(stringResource(R.string.geo_refresh)) }
            NotesSection(here)
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.geo_elsewhere), fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(manual, { manual = it.lowercase().filter { c -> c.isLetterOrDigit() }.take(8) }, placeholder = { Text(stringResource(R.string.geo_hint)) }, modifier = Modifier.weight(1f), singleLine = true)
            Spacer(Modifier.size(8.dp))
            Button(enabled = Geohash.isValid(manual) && manual.length >= 2, onClick = {
                ChatRepository.controller?.joinGeo(manual, teleported = true); onOpen(manual)
            }) { Text(stringResource(R.string.geo_enter)) }
        }
        Text(stringResource(R.string.geo_teleport_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (geo.joined.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.geo_joined), fontWeight = FontWeight.SemiBold)
            geo.joined.sorted().forEach { g -> TextButton(onClick = { onOpen(g) }) { Text("#$g") } }
        }
    }
}

/**
 * Plain-text invite to a location channel (§21.5): readable without the app, and a
 * `bitchat://geohash/` link both apps open. A fine-grained cell asks first, since
 * whoever carries the text learns which small area you care about.
 */
@Composable
private fun ShareChannelButton(geohash: String) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    fun share() {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, ctx.getString(R.string.share_channel_text, geohash))
        ctx.startActivity(android.content.Intent.createChooser(send, null))
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.share_channel_warn_title)) },
            text = { Text(stringResource(R.string.share_channel_warn_body, geohash)) },
            confirmButton = { TextButton(onClick = { confirm = false; share() }) { Text(stringResource(R.string.share_anyway)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    TextButton(onClick = { if (geohash.length >= 6) confirm = true else share() }) { Text(stringResource(R.string.share_channel)) }
}

/** Notes left at this building-level cell and the ones around it (§17). */
@Composable
private fun NotesSection(here: String) {
    val geo by ChatRepository.geo.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    DisposableEffect(here) {
        ChatRepository.controller?.watchNotes(here)
        onDispose { ChatRepository.controller?.watchNotes(null) }
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.notes_title), fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.notes_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(draft, { if (it.length <= 500) draft = it }, placeholder = { Text(stringResource(R.string.notes_placeholder)) }, modifier = Modifier.weight(1f), maxLines = 3)
        IconButton(enabled = draft.isNotBlank(), onClick = { if (ChatRepository.controller?.dropNote(draft.trim()) == true) draft = "" }) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send), tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (geo.notes.isEmpty()) {
        Text(stringResource(R.string.notes_empty), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
    }
    geo.notes.take(50).forEach { n ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                buildAnnotatedString {
                    append(labelText(PeerLabel.nostr(n.nickname, n.pubkey)))
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) { append("  " + stampText(n.createdAt)) }
                },
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(withMentions(n.text), fontSize = 15.sp)
        }
    }
}

@StringRes
private fun levelLabel(level: Geohash.Level): Int = when (level) {
    Geohash.Level.BLOCK -> R.string.geo_level_block
    Geohash.Level.NEIGHBORHOOD -> R.string.geo_level_neighborhood
    Geohash.Level.CITY -> R.string.geo_level_city
    Geohash.Level.PROVINCE -> R.string.geo_level_province
    Geohash.Level.REGION -> R.string.geo_level_region
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeoChatScreen(geohash: String, onBack: () -> Unit, onPanic: () -> Unit) {
    val geo by ChatRepository.geo.collectAsState()
    var person by remember { mutableStateOf<PersonRef?>(null) }
    BackHandler(onBack = onBack)
    person?.let { PersonSheet(it, onDismiss = { person = null }, onMessage = { key -> ChatRepository.requestOpen(key) }) }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = {
                Column {
                    PanicTitle("#$geohash", onPanic)
                    val n = geo.participants[geohash] ?: 0
                    Text(if (geo.relaysConnected == 0) stringResource(R.string.geo_connecting) else plural(R.plurals.geo_recent, n), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                ShareChannelButton(geohash)
                TextButton(onClick = { ChatRepository.controller?.leaveGeo(geohash); onBack() }) { Text(stringResource(R.string.geo_leave)) }
            },
        )
    }) { padding ->
        Box(Modifier.padding(padding)) {
            ChatPane(
                conversation = Conversation.Geo(geohash).key,
                emptyText = stringResource(R.string.geo_empty, geohash),
                placeholder = stringResource(R.string.geo_placeholder, geohash),
                onSend = { ChatRepository.controller?.sendGeo(geohash, it) },
                onSenderClick = { m -> person = PersonRef.Geo(m.senderKey, m.senderName, geohash) },
            )
        }
    }
}

// ---------------------------------------------------------------- me

@Composable
private fun MePane(onShareApp: () -> Unit, onLanguage: (AppLanguage.Choice) -> Unit, onScan: () -> Unit) {
    val mesh by ChatRepository.mesh.collectAsState()
    var nick by rememberSaveable(mesh.myNickname) { mutableStateOf(mesh.myNickname) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.me_name), fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(nick, { nick = it.take(32) }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.size(8.dp))
            Button(enabled = nick.isNotBlank() && nick != mesh.myNickname, onClick = { ChatRepository.controller?.setNickname(nick) }) { Text(stringResource(R.string.save)) }
        }
        val seenAs = stringResource(R.string.me_seen_as)
        Text(
            buildAnnotatedString { append(seenAs); append(labelText(PeerLabel.mesh(mesh.myNickname, mesh.myPeerHex))) },
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.me_suffix_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.me_language), fontWeight = FontWeight.SemiBold)
        LanguagePicker(onLanguage)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.me_share_title), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.me_share_body), fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onShareApp, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.me_share_button)) }
        Spacer(Modifier.height(24.dp))
        SafetyNote()
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.me_fp_title), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.me_fp_body), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(mesh.fingerprint.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.me_device_id, mesh.myPeerHex), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        VerifySection(onScan)
        if (mesh.carriedCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.me_carrying, mesh.carriedCount), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        }
        NotificationSection()
        BridgeSection()
        TorSection()
        RelaySection()
        BlockedList()
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.me_about), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
