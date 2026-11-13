package org.openchat.android.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import org.openchat.android.app.AppUpdater
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.Conversation
import org.openchat.android.app.MeshService
import org.openchat.android.app.MeshStatus
import org.openchat.android.app.Panic
import org.openchat.android.app.PeerLabel
import org.openchat.android.mesh.MeshPeer

// One icon family: outlined, filled when the tab is selected.
private enum class Tab(@StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    NEARBY(R.string.tab_nearby, Icons.Outlined.Sensors, Icons.Filled.Sensors),
    PRIVATE(R.string.tab_private, Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble),
    LOCATION(R.string.tab_location, Icons.Outlined.Place, Icons.Filled.Place),
    ME(R.string.tab_me, Icons.Outlined.Person, Icons.Filled.Person),
}

/** What a wipe actually did, for the dialog that follows it. */
private enum class Wiped { NEW_IDENTITY, STORAGE_ONLY, INCOMPLETE }

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
    var openChat by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var wiped by remember { mutableStateOf<Wiped?>(null) }
    val context = LocalContext.current
    var person by remember { mutableStateOf<PersonRef?>(null) }
    val unread by ChatRepository.unread.collectAsState()
    val mesh by ChatRepository.mesh.collectAsState()
    val peers by ChatRepository.peers.collectAsState()
    val reducedMotion = rememberReducedMotion()

    val onPanic = {
        val controller = ChatRepository.controller
        wiped = if (controller != null) {
            controller.panic()
            Wiped.NEW_IDENTITY
        } else {
            val clean = Panic.wipeWithoutService(context.applicationContext)
            if (clean && !missingPermissions) MeshService.start(context)
            if (clean) Wiped.STORAGE_ONLY else Wiped.INCOMPLETE
        }
        drafts.clear()
        openChat = null
        tab = Tab.NEARBY
    }

    wiped?.let { result ->
        AlertDialog(
            onDismissRequest = { wiped = null },
            confirmButton = { TextButton(onClick = { wiped = null }) { Text(stringResource(R.string.ok)) } },
            text = {
                Text(
                    when (result) {
                        Wiped.NEW_IDENTITY -> stringResource(R.string.wiped, PeerLabel.mesh(mesh.myNickname, mesh.myPeerHex).toString())
                        Wiped.STORAGE_ONLY -> stringResource(R.string.wiped_offline)
                        Wiped.INCOMPLETE -> stringResource(R.string.wipe_failed)
                    },
                )
            },
        )
    }

    if (scanResult != null) {
        AlertDialog(
            onDismissRequest = onScanResultSeen,
            confirmButton = { TextButton(onClick = onScanResultSeen) { Text(stringResource(R.string.ok)) } },
            text = { Text(if (scanResult.isEmpty()) stringResource(R.string.verify_scan_bad) else stringResource(R.string.verify_scan_ok, scanResult)) },
        )
    }
    if (creatingGroup) {
        NewGroupDialog(
            onDismiss = { creatingGroup = false },
            onCreated = { creatingGroup = false; openChat = it },
        )
    }

    val openRequest by ChatRepository.openRequest.collectAsState()
    LaunchedEffect(openRequest) { openRequest?.let { openChat = it; ChatRepository.requestOpen(null) } }
    LaunchedEffect(Unit) { AppUpdater.maybeAutoCheck(context) }
    person?.let { p -> PersonSheet(p, onDismiss = { person = null }, onMessage = { openChat = Conversation.Private(it).key }) }

    AnimatedContent(
        targetState = openChat,
        transitionSpec = {
            if (reducedMotion) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            } else if (targetState != null && initialState == null) {
                (slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { -it / 8 } + fadeOut(tween(140)))
            } else if (targetState == null && initialState != null) {
                (slideInHorizontally(tween(200, easing = FastOutSlowInEasing)) { -it / 8 } + fadeIn(tween(160))) togetherWith
                    (slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { it / 4 } + fadeOut(tween(150)))
            } else {
                fadeIn(tween(160)) togetherWith fadeOut(tween(120))
            }
        },
        label = "screen-route",
    ) { route ->
        when {
            route != null && route.startsWith("grp:") -> GroupChatScreen(route.removePrefix("grp:"), onBack = { openChat = null }, onPanic = onPanic)
            route != null && Conversation.GeoPrivate.parse(route) != null -> GeoPrivateChatScreen(Conversation.GeoPrivate.parse(route)!!, onBack = { openChat = null }, onPanic = onPanic)
            route != null && route.startsWith("dm:") -> PrivateChatScreen(route.removePrefix("dm:"), onBack = { openChat = null }, onPanic = onPanic)
            route == BOARD -> BoardScreen(onBack = { openChat = null }, onPanic = onPanic)
            route != null && route.startsWith("geo:") -> GeoChatScreen(route.removePrefix("geo:"), onBack = { openChat = null }, onPanic = onPanic)
            else -> ShellScaffold(
                tab = tab,
                onTab = { tab = it },
                unread = unread,
                missingPermissions = missingPermissions,
                mesh = mesh,
                peers = peers,
                reducedMotion = reducedMotion,
                onPanic = onPanic,
                onOpenBoard = { openChat = BOARD },
                onNewGroup = { creatingGroup = true },
                onGrantPermissions = onGrantPermissions,
                onEnableBluetooth = onEnableBluetooth,
                onRequestLocation = onRequestLocation,
                onShareApp = onShareApp,
                onLanguage = onLanguage,
                onScan = onScan,
                onOpenChat = { openChat = it },
                onSelectPerson = { person = it },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShellScaffold(
    tab: Tab,
    onTab: (Tab) -> Unit,
    unread: Map<String, Int>,
    missingPermissions: Boolean,
    mesh: MeshStatus,
    peers: List<MeshPeer>,
    reducedMotion: Boolean,
    onPanic: () -> Unit,
    onOpenBoard: () -> Unit,
    onNewGroup: () -> Unit,
    onGrantPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onRequestLocation: () -> Unit,
    onShareApp: () -> Unit,
    onLanguage: (AppLanguage.Choice) -> Unit,
    onScan: () -> Unit,
    onOpenChat: (String) -> Unit,
    onSelectPerson: (PersonRef) -> Unit,
) {
    Scaffold(
        containerColor = OpenChatPalette.Screen,
        topBar = {
            Column {
                TopAppBar(
                    colors = chromeTopBarColors(),
                    navigationIcon = { PanicLogo(onPanic) },
                    title = {
                        Column {
                            PanicTitle(stringResource(tab.label), onPanic)
                            if (tab == Tab.NEARBY) {
                                Text(stringResource(R.string.nearby_subtitle), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    actions = {
                        if (tab == Tab.NEARBY) {
                            val unseen by ChatRepository.boardUnseen.collectAsState()
                            BadgedBox(
                                badge = { UnreadBadge(unseen) },
                                modifier = Modifier.padding(end = 12.dp),
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = OpenChatPalette.Card,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenBoard),
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Icon(Icons.Outlined.PushPin, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(stringResource(R.string.board_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        } else if (tab == Tab.PRIVATE) {
                            Surface(
                                shape = CircleShape,
                                color = OpenChatPalette.Card,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.padding(end = 12.dp).clip(CircleShape).clickable(onClick = onNewGroup),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurface)
                                    Text(stringResource(R.string.group_new), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            val updateState by AppUpdater.state.collectAsState()
            val hasUpdateDot = updateState is AppUpdater.UpdateState.Available || updateState is AppUpdater.UpdateState.ReadyToInstall
            Column {
                Hairline()
                NavigationBar(
                    containerColor = NavContainerColor,
                    tonalElevation = NavTonalElevation,
                ) {
                    Tab.entries.forEach { t ->
                        val count = when (t) {
                            Tab.NEARBY -> unread[Conversation.Mesh.key] ?: 0
                            Tab.PRIVATE -> unread.filterKeys { it.startsWith("dm:") || it.startsWith("gdm:") || it.startsWith("grp:") }.values.sum()
                            Tab.LOCATION -> unread.filterKeys { it.startsWith("geo:") }.values.sum()
                            Tab.ME -> 0
                        }
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { onTab(t) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = OpenChatPalette.Ink,
                                selectedTextColor = OpenChatPalette.Ink,
                                indicatorColor = OpenChatPalette.CardRaised,
                                unselectedIconColor = OpenChatPalette.Muted,
                                unselectedTextColor = OpenChatPalette.Muted,
                            ),
                            label = { Text(stringResource(t.label), fontWeight = if (tab == t) FontWeight.SemiBold else FontWeight.Normal, fontSize = 11.5.sp) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (t == Tab.ME && hasUpdateDot) {
                                            Box(Modifier.size(8.dp).clip(CircleShape).background(OpenChatPalette.Signal))
                                        } else {
                                            UnreadBadge(count)
                                        }
                                    },
                                ) {
                                    Icon(
                                        if (tab == t) t.selectedIcon else t.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val otherPeers = remember(peers, mesh.myPeerHex) { peers.filter { it.peerIdHex != mesh.myPeerHex } }
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusBanner(missingPermissions, mesh.storageLocked, mesh.bluetoothOn, mesh.running, otherPeers, onGrantPermissions, onEnableBluetooth)
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    if (reducedMotion) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    else fadeIn(tween(150)) togetherWith fadeOut(tween(100))
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "tab-content",
            ) { currentTab ->
                when (currentTab) {
                    Tab.NEARBY -> ChatPane(
                        conversation = Conversation.Mesh.key,
                        emptyText = if (otherPeers.isEmpty()) stringResource(R.string.nearby_empty_alone) else plural(R.plurals.nearby_empty_peers, otherPeers.size),
                        placeholder = stringResource(R.string.nearby_placeholder),
                        onSend = { ChatRepository.controller?.sendPublic(it) },
                        onSenderClick = { m -> if (m.senderKey != mesh.myPeerHex) onSelectPerson(PersonRef.Mesh(m.senderKey, m.senderName)) },
                        empty = if (otherPeers.isEmpty() && mesh.running) ({ onShowHistory -> NobodyNearby(onShareApp, onShowHistory) }) else null,
                        cover = otherPeers.isEmpty() && mesh.running,
                    )
                    Tab.PRIVATE -> PrivateList(
                        onOpen = { onOpenChat(if (it.startsWith("gdm:") || it.startsWith("grp:")) it else Conversation.Private(it).key) },
                        onNewGroup = onNewGroup,
                    )
                    Tab.LOCATION -> LocationPane(onRequestLocation, onOpen = { onOpenChat(Conversation.Geo(it).key) })
                    Tab.ME -> MePane(onShareApp, onLanguage, onScan, onPanic)
                }
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
    if (verified) withStyle(SpanStyle(color = OpenChatPalette.Signal, fontWeight = FontWeight.Bold)) { append(" \u2713\uFE0E") }
    else if (vouched) withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" \u2713\uFE0E") }
    if (teleported) append(" \u2708\uFE0E")
}

/** Title that wipes everything on a triple tap, with no confirmation (docs/protocol-spec.md §10). */
@Composable
fun PanicTitle(text: String, onPanic: () -> Unit) = PanicTitle(AnnotatedString(text), onPanic)

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
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
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
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = stringResource(R.string.panic_logo),
            modifier = Modifier.fillMaxSize().scale(1.7f * 2 / 3),
        )
    }
}

@Composable
private fun StatusBanner(missing: Boolean, locked: Boolean, btOn: Boolean, running: Boolean, peers: List<MeshPeer>, onGrant: () -> Unit, onEnable: () -> Unit) {
    val ctx = LocalContext.current
    val problem = when {
        locked -> stringResource(R.string.banner_storage_locked) to { MeshService.start(ctx) }
        missing -> stringResource(R.string.banner_need_permission) to onGrant
        !btOn -> stringResource(R.string.banner_bt_off) to onEnable
        !running -> stringResource(R.string.banner_starting) to null
        else -> null
    }
    val alone = peers.isEmpty()
    val aloneSince = remember(alone, running) { System.currentTimeMillis() }
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        when {
            problem != null -> {
                val warn = missing || locked || !btOn
                val (text, action) = problem
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (warn) MaterialTheme.colorScheme.errorContainer else OpenChatPalette.Surface)
                        .let { if (action != null) it.clickable { action() } else it }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (locked) Text(
                            stringResource(R.string.storage_locked_hint, stringResource(R.string.me_wipe_title), stringResource(R.string.tab_me)),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    if (action != null) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            alone -> ScanningBand(aloneSince)
            else -> SignalBand(direct = peers.count { it.direct }, reach = peers.size, lastHeard = peers.maxOf { it.lastSeen })
        }
        Hairline()
    }
}
