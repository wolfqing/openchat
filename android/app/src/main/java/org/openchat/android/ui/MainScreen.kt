package org.openchat.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.AppLanguage
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.Conversation
import org.openchat.android.app.PeerLabel

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
                                    contentDescription = null, // the label names the tab; a description here would be read twice
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
                Tab.ME -> MePane(onShareApp, onLanguage, onScan, onPanic)
            }
        }
    }
}

/** Name with its #xxxx suffix set smaller, monospaced and muted, so the name itself leads. */
@Composable
fun labelText(label: PeerLabel, teleported: Boolean = false, verified: Boolean = false, vouched: Boolean = false): AnnotatedString = buildAnnotatedString {
    append(label.name)
    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 0.8.em, color = MaterialTheme.colorScheme.onSurfaceVariant)) {
        append(label.suffix)
    }
    if (verified) withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(" ✓") }
    else if (vouched) withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" ✓") } // verified by someone we verified
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
