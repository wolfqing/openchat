package org.openchat.android.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import org.openchat.android.app.TorState
import org.openchat.android.mesh.MeshPeer

// v0.7.4 tab icon family preserved: outlined when idle, filled when selected, always paired with text labels.
private enum class Tab(@StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    NEARBY(R.string.tab_nearby, Icons.Outlined.Sensors, Icons.Filled.Sensors),
    PRIVATE(R.string.tab_private, Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble),
    LOCATION(R.string.tab_location, Icons.Outlined.Place, Icons.Filled.Place),
    ME(R.string.tab_me, Icons.Outlined.Person, Icons.Filled.Person),
}

/** What a wipe actually did, for the dialog that follows it. */
private enum class Wiped { NEW_IDENTITY, STORAGE_ONLY, INCOMPLETE }

internal val FLOATING_BAR_MAX_WIDTH = 352.dp
internal val FLOATING_BAR_HORIZONTAL_PADDING = 16.dp
private val FLOATING_NAV_HEIGHT = 62.dp
private val FLOATING_NAV_MARGIN_BOTTOM = 10.dp

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
    var showNearbyPeers by remember { mutableStateOf(false) }
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
        showNearbyPeers = false
        tab = Tab.NEARBY
    }

    wiped?.let { result ->
        AlertDialog(
            onDismissRequest = { wiped = null },
            containerColor = OpenChatPalette.Surface,
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
        val inlineIcons = rememberLabelInlineIcons()
        AlertDialog(
            onDismissRequest = onScanResultSeen,
            containerColor = OpenChatPalette.Surface,
            confirmButton = { TextButton(onClick = onScanResultSeen) { Text(stringResource(R.string.ok)) } },
            text = {
                Text(
                    withInlineVectorBadges(if (scanResult.isEmpty()) stringResource(R.string.verify_scan_bad) else stringResource(R.string.verify_scan_ok, scanResult)),
                    inlineContent = inlineIcons,
                )
            },
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
    if (showNearbyPeers) {
        NearbyPeersSheet(
            onDismiss = { showNearbyPeers = false },
            onSelectPerson = { showNearbyPeers = false; person = it },
            onMessage = { hex -> showNearbyPeers = false; openChat = Conversation.Private(hex).key },
            onOpenTopology = { showNearbyPeers = false; openChat = TOPOLOGY },
        )
    }

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
            route == GUIDE -> GuideScreen(onBack = { openChat = null }, onPanic = onPanic)
            route == TOPOLOGY -> MeshTopologyScreen(onBack = { openChat = null }, onPanic = onPanic, onOpenPrivate = { openChat = it })
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
                onOpenGuide = { openChat = GUIDE },
                onNewGroup = { creatingGroup = true },
                onGrantPermissions = onGrantPermissions,
                onEnableBluetooth = onEnableBluetooth,
                onRequestLocation = onRequestLocation,
                onShareApp = onShareApp,
                onLanguage = onLanguage,
                onScan = onScan,
                onOpenChat = { openChat = it },
                onSelectPerson = { person = it },
                onShowNearbyPeers = { showNearbyPeers = true },
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
    onOpenGuide: () -> Unit,
    onNewGroup: () -> Unit,
    onGrantPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onRequestLocation: () -> Unit,
    onShareApp: () -> Unit,
    onLanguage: (AppLanguage.Choice) -> Unit,
    onScan: () -> Unit,
    onOpenChat: (String) -> Unit,
    onSelectPerson: (PersonRef) -> Unit,
    onShowNearbyPeers: () -> Unit,
) {
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val navBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeVisible = imeBottomPx > with(density) { 80.dp.toPx() }
    val bottomClearance = if (imeVisible) 0.dp else FLOATING_NAV_HEIGHT + FLOATING_NAV_MARGIN_BOTTOM + navBottomInset

    val pttOpenSet by ChatRepository.pttOpen.collectAsState()
    val nearbyPttOpen = Conversation.Mesh.key in pttOpenSet
    val liveSpeakers by ChatRepository.liveSpeakers.collectAsState()
    val nearbySpeaker = remember(liveSpeakers) {
        liveSpeakers.values.firstOrNull { it.conversation == Conversation.Mesh.key }
            ?.let { PeerLabel.mesh(it.senderName, it.senderKey).toString() }
    }

    val otherPeers = remember(peers, mesh.myPeerHex) { peers.filter { it.peerIdHex != mesh.myPeerHex } }
    val directCount = remember(otherPeers) { otherPeers.count { it.direct } }
    val hasProblem = missingPermissions || mesh.storageLocked || !mesh.bluetoothOn || !mesh.running
    val unseenBoard by ChatRepository.boardUnseen.collectAsState()
    val geo by ChatRepository.geo.collectAsState()
    val ctx = LocalContext.current
    val versionTag = remember {
        runCatching { "v${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName}" }.getOrDefault("")
    }

    FluidGlassHost {
        CompositionLocalProvider(LocalBottomBarClearance provides bottomClearance) {
            Box(Modifier.fillMaxSize().openChatCanvas()) {
                Scaffold(
                    containerColor = Color.Transparent,
                    contentColor = OpenChatPalette.Ink,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        ProgressiveBlurTopBar {
                            Column(
                                Modifier
                                    .statusBarsPadding()
                                    .windowInsetsPadding(
                                        WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
                                    ),
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 52.dp)
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    when (tab) {
                                        Tab.NEARBY -> {
                                            PanicTitle(stringResource(R.string.tab_nearby), onPanic, fontSize = 21f)
                                            if (!hasProblem) {
                                                // Tappable status pill: live speaker or "3 直连 / 11 可达" or "蓝牙巡航中"
                                                val isLiveSpeaker = nearbySpeaker != null
                                                Surface(
                                                    shape = CircleShape,
                                                    color = when {
                                                        isLiveSpeaker -> OpenChatPalette.SignalSoft
                                                        otherPeers.isNotEmpty() -> OpenChatPalette.Surface
                                                        else -> OpenChatPalette.Card
                                                    },
                                                    border = BorderStroke(0.5.dp, OpenChatPalette.Line),
                                                    tonalElevation = 0.dp,
                                                    shadowElevation = 0.dp,
                                                    modifier = Modifier
                                                        .weight(1f, fill = false)
                                                        .pressScale(0.96f)
                                                        .clip(CircleShape)
                                                        .clickable(role = Role.Button) {
                                                            if (isLiveSpeaker) ChatRepository.setPttOpen(Conversation.Mesh.key, true)
                                                            else onShowNearbyPeers()
                                                        },
                                                ) {
                                                    Row(
                                                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                    ) {
                                                        if (isLiveSpeaker) {
                                                            LiveEqualizerBars(
                                                                active = true,
                                                                color = OpenChatPalette.Signal,
                                                                modifier = Modifier.size(width = 13.dp, height = 11.dp),
                                                            )
                                                            Text(
                                                                nearbySpeaker.orEmpty(),
                                                                fontSize = 11.5.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = OpenChatPalette.Signal,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                        } else {
                                                            Box(
                                                                Modifier
                                                                    .size(6.dp)
                                                                    .background(
                                                                        if (otherPeers.isNotEmpty()) OpenChatPalette.Signal else OpenChatPalette.Muted.copy(alpha = 0.55f),
                                                                        CircleShape,
                                                                    ),
                                                            )
                                                            Text(
                                                                if (otherPeers.isNotEmpty()) {
                                                                    "$directCount ${stringResource(R.string.band_direct)} / ${otherPeers.size} ${stringResource(R.string.band_reach)}"
                                                                } else {
                                                                    stringResource(R.string.radar_scanning_pill)
                                                                },
                                                                style = TabularMonoStyle,
                                                                fontSize = 11.5.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                color = OpenChatPalette.Ink,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                                            // Labeled PTT pill button (Braun instrument pill)
                                            Surface(
                                                shape = CircleShape,
                                                color = if (nearbyPttOpen) OpenChatPalette.PrimaryAction else OpenChatPalette.Card,
                                                border = if (nearbyPttOpen) null else BorderStroke(0.5.dp, OpenChatPalette.Line),
                                                tonalElevation = 0.dp,
                                                shadowElevation = 0.dp,
                                                modifier = Modifier
                                                    .pressScale(0.95f)
                                                    .clip(CircleShape)
                                                    .clickable(role = Role.Button) {
                                                        ChatRepository.togglePttOpen(Conversation.Mesh.key)
                                                    },
                                            ) {
                                                Row(
                                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                ) {
                                                    if (nearbyPttOpen) {
                                                        Box(Modifier.size(6.5.dp).background(OpenChatPalette.Signal, CircleShape))
                                                    } else {
                                                        Icon(
                                                            WalkieTalkieIcon,
                                                            contentDescription = null,
                                                            tint = if (nearbySpeaker != null) OpenChatPalette.Signal else OpenChatPalette.Ink,
                                                            modifier = Modifier.size(13.dp),
                                                        )
                                                    }
                                                    Text(
                                                        stringResource(R.string.ptt_button),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = if (nearbyPttOpen) OpenChatPalette.OnPrimaryAction else OpenChatPalette.Ink,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                            // Labeled Notice Board pill button
                                            Surface(
                                                shape = CircleShape,
                                                color = OpenChatPalette.Card,
                                                border = BorderStroke(0.5.dp, OpenChatPalette.Line),
                                                tonalElevation = 0.dp,
                                                shadowElevation = 0.dp,
                                                modifier = Modifier
                                                    .pressScale(0.95f)
                                                    .clip(CircleShape)
                                                    .clickable(role = Role.Button, onClick = onOpenBoard),
                                            ) {
                                                Row(
                                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Text(
                                                        stringResource(R.string.top_board_short),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = OpenChatPalette.Ink,
                                                        maxLines = 1,
                                                    )
                                                    if (unseenBoard > 0) {
                                                        Text(
                                                            "$unseenBoard",
                                                            style = TabularMonoStyle,
                                                            fontSize = 11.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = OpenChatPalette.Signal,
                                                            maxLines = 1,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        Tab.PRIVATE -> {
                                            PanicTitle(stringResource(R.string.tab_private), onPanic, fontSize = 21f)
                                            Surface(
                                                shape = CircleShape,
                                                color = OpenChatPalette.LockSoft,
                                                tonalElevation = 0.dp,
                                                shadowElevation = 0.dp,
                                            ) {
                                                Row(
                                                    Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Icon(
                                                        Icons.Outlined.Lock,
                                                        contentDescription = null,
                                                        tint = OpenChatPalette.Lock,
                                                        modifier = Modifier.size(11.dp),
                                                    )
                                                    Text(
                                                        stringResource(R.string.chip_encrypted),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = OpenChatPalette.Lock,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                                            Surface(
                                                shape = CircleShape,
                                                color = OpenChatPalette.Card,
                                                border = BorderStroke(0.5.dp, OpenChatPalette.Line),
                                                tonalElevation = 0.dp,
                                                shadowElevation = 0.dp,
                                                modifier = Modifier
                                                    .pressScale(0.95f)
                                                    .clip(CircleShape)
                                                    .clickable(role = Role.Button, onClick = onNewGroup),
                                            ) {
                                                Text(
                                                    stringResource(R.string.top_group_new_short),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = OpenChatPalette.Ink,
                                                    maxLines = 1,
                                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                                )
                                            }
                                        }
                                        Tab.LOCATION -> {
                                            PanicTitle(stringResource(R.string.tab_location), onPanic, fontSize = 21f)
                                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                                            Surface(
                                                shape = CircleShape,
                                                color = OpenChatPalette.Card,
                                                border = BorderStroke(0.5.dp, OpenChatPalette.Line),
                                                tonalElevation = 0.dp,
                                                shadowElevation = 0.dp,
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .clickable(role = Role.Button) { onTab(Tab.ME) },
                                            ) {
                                                Row(
                                                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                ) {
                                                    Box(
                                                        Modifier
                                                            .size(6.dp)
                                                            .background(
                                                                when {
                                                                    geo.tor == TorState.ON -> OpenChatPalette.Signal
                                                                    geo.tor == TorState.STARTING && geo.torStalled -> OpenChatPalette.Signal
                                                                    geo.tor == TorState.STARTING -> OpenChatPalette.Muted
                                                                    else -> MaterialTheme.colorScheme.error
                                                                },
                                                                CircleShape,
                                                            ),
                                                    )
                                                    Text(
                                                        when {
                                                            geo.tor == TorState.ON -> "Tor"
                                                            geo.tor == TorState.STARTING && geo.torStalled -> stringResource(R.string.tor_stalled_short)
                                                            geo.tor == TorState.STARTING -> "Tor ${geo.torProgress}%"
                                                            else -> stringResource(R.string.tor_use_direct)
                                                        },
                                                        style = TabularMonoStyle,
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = if (geo.tor == TorState.STARTING && geo.torStalled) OpenChatPalette.Signal else OpenChatPalette.Ink,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                        }
                                        Tab.ME -> {
                                            PanicTitle(stringResource(R.string.me_header_title), onPanic, fontSize = 21f)
                                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                                            if (versionTag.isNotEmpty()) {
                                                Text(
                                                    versionTag,
                                                    style = TabularMonoStyle,
                                                    fontSize = 12.sp,
                                                    color = OpenChatPalette.Muted,
                                                    maxLines = 1,
                                                )
                                            }
                                            Surface(
                                                shape = CircleShape,
                                                color = OpenChatPalette.Card,
                                                border = BorderStroke(0.5.dp, OpenChatPalette.Line),
                                                tonalElevation = 0.dp,
                                                shadowElevation = 0.dp,
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .pressScale(0.94f)
                                                    .clip(CircleShape)
                                                    .clickable(role = Role.Button, onClick = onOpenGuide),
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        Icons.AutoMirrored.Outlined.HelpOutline,
                                                        contentDescription = stringResource(R.string.guide_button),
                                                        tint = OpenChatPalette.Ink,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (hasProblem) {
                                    ProblemBanner(
                                        missing = missingPermissions,
                                        locked = mesh.storageLocked,
                                        btOn = mesh.bluetoothOn,
                                        running = mesh.running,
                                        onGrant = onGrantPermissions,
                                        onEnable = onEnableBluetooth,
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    val topClearance = padding.calculateTopPadding()
                    Box(
                        Modifier
                            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
                            .fillMaxSize(),
                    ) {
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                if (reducedMotion) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                                else fadeIn(tween(150)) togetherWith fadeOut(tween(100))
                            },
                            modifier = Modifier.fillMaxSize(),
                            label = "tab-content",
                        ) { currentTab ->
                            when (currentTab) {
                                Tab.NEARBY -> ChatPane(
                                    conversation = Conversation.Mesh.key,
                                    emptyText = if (otherPeers.isEmpty()) stringResource(R.string.nearby_empty_alone) else plural(R.plurals.nearby_empty_peers, otherPeers.size),
                                    placeholder = stringResource(R.string.nearby_placeholder),
                                    onSend = { ChatRepository.controller?.sendPublic(it) },
                                    onSenderClick = { m -> if (m.senderKey != mesh.myPeerHex) onSelectPerson(PersonRef.Mesh(m.senderKey, m.senderName)) },
                                    onOpenBoard = onOpenBoard,
                                    empty = if (otherPeers.isEmpty() && mesh.running) {
                                        val extraClearance = if (nearbyPttOpen) WALKIE_TALKIE_DRAWER_HEIGHT + 10.dp else 66.dp
                                        { onShowHistory ->
                                            NobodyNearby(
                                                onShareApp = onShareApp,
                                                onShowHistory = onShowHistory,
                                                onOpenGuide = onOpenGuide,
                                                onOpenLocation = { onTab(Tab.LOCATION) },
                                                onOpenBoard = onOpenBoard,
                                                topClearance = topClearance,
                                                bottomClearance = bottomClearance + extraClearance,
                                            )
                                        }
                                    } else null,
                                    cover = otherPeers.isEmpty() && mesh.running,
                                    topClearance = topClearance,
                                )
                                Tab.PRIVATE -> PrivateList(
                                    onOpen = { onOpenChat(if (it.startsWith("gdm:") || it.startsWith("grp:")) it else Conversation.Private(it).key) },
                                    onNewGroup = onNewGroup,
                                    topClearance = topClearance,
                                )
                                Tab.LOCATION -> LocationPane(
                                    onRequestLocation = onRequestLocation,
                                    onOpen = { onOpenChat(Conversation.Geo(it).key) },
                                    topClearance = topClearance,
                                )
                                Tab.ME -> MePane(
                                    onShareApp = onShareApp,
                                    onLanguage = onLanguage,
                                    onScan = onScan,
                                    onPanic = onPanic,
                                    onOpenGuide = onOpenGuide,
                                    topClearance = topClearance,
                                )
                            }
                        }

                        // Floating bottom Liquid Glass capsule tab bar (auto-hides while software keyboard is up)
                        val updateState by AppUpdater.state.collectAsState()
                        val hasUpdateDot = updateState is AppUpdater.UpdateState.Available || updateState is AppUpdater.UpdateState.ReadyToInstall
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !imeVisible,
                            enter = fadeIn(tween(150)) + slideInVertically(tween(180)) { it / 2 },
                            exit = fadeOut(tween(110)) + slideOutVertically(tween(140)) { it / 2 },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(horizontal = FLOATING_BAR_HORIZONTAL_PADDING, vertical = FLOATING_NAV_MARGIN_BOTTOM),
                        ) {
                            CapsuleNavigationBar(
                                selected = tab,
                                onSelect = onTab,
                                unread = unread,
                                hasUpdateDot = hasUpdateDot,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Floating optical-refraction Liquid Glass tab capsule preserving v0.7.4's four tabs, icons,
 * and text labels, with an Apple Music-inspired spring-animated translucent neutral-gray active pill.
 */
@Composable
private fun CapsuleNavigationBar(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    unread: Map<String, Int>,
    hasUpdateDot: Boolean,
) {
    val isLight = OpenChatPalette.isLight
    val haptic = LocalHapticFeedback.current
    FluidGlassCapsule(
        modifier = Modifier
            .widthIn(max = FLOATING_BAR_MAX_WIDTH)
            .fillMaxWidth()
            .height(FLOATING_NAV_HEIGHT),
        shadowElevation = 10.dp,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            val itemWidth = maxWidth / Tab.entries.size
            val indicatorOffset by animateDpAsState(
                targetValue = itemWidth * selected.ordinal,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "nav-droplet-offset",
            )
            // Sliding Apple Music-inspired translucent gray active pill
            Box(
                Modifier
                    .offset(x = indicatorOffset)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .fluidGlassDroplet(isLight),
            )

            Row(Modifier.fillMaxSize()) {
                Tab.entries.forEach { t ->
                    val isSelected = selected == t
                    val count = when (t) {
                        Tab.NEARBY -> unread[Conversation.Mesh.key] ?: 0
                        Tab.PRIVATE -> unread.filterKeys { it.startsWith("dm:") || it.startsWith("gdm:") || it.startsWith("grp:") }.values.sum()
                        Tab.LOCATION -> unread.filterKeys { it.startsWith("geo:") }.values.sum()
                        Tab.ME -> 0
                    }
                    val fg = when {
                        isSelected -> OpenChatPalette.Signal
                        isLight -> OpenChatPalette.Ink.copy(alpha = 0.64f)
                        else -> OpenChatPalette.Muted
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .semantics {
                                this.selected = isSelected
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                            ) {
                                if (!isSelected) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelect(t)
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
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
                                imageVector = if (isSelected) t.selectedIcon else t.icon,
                                contentDescription = null,
                                tint = fg,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Text(
                            text = stringResource(t.label),
                            color = fg,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            fontSize = 10.5.sp,
                            lineHeight = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

internal const val INLINE_VERIFIED = "verified"
internal const val INLINE_VOUCHED = "vouched"
internal const val INLINE_TELEPORTED = "teleported"
internal const val INLINE_STAR = "star"

/** Pure Material vector inline icons for peer badges — zero emoji or dingbat font glyphs. */
@Composable
internal fun rememberLabelInlineIcons(): Map<String, InlineTextContent> {
    val signal = OpenChatPalette.Signal
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(signal, muted) {
        val badgeSize = Placeholder(0.95.em, 0.95.em, PlaceholderVerticalAlign.Center)
        mapOf(
            INLINE_VERIFIED to InlineTextContent(badgeSize) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = signal, modifier = Modifier.fillMaxSize())
            },
            INLINE_VOUCHED to InlineTextContent(badgeSize) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = muted, modifier = Modifier.fillMaxSize())
            },
            INLINE_TELEPORTED to InlineTextContent(badgeSize) {
                Icon(Icons.Outlined.Flight, contentDescription = null, tint = muted, modifier = Modifier.fillMaxSize())
            },
            INLINE_STAR to InlineTextContent(badgeSize) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = signal, modifier = Modifier.fillMaxSize())
            },
        )
    }
}

/** Name with its #xxxx suffix set smaller, monospaced and muted, plus pure vector inline badges. */
@Composable
fun labelText(
    label: PeerLabel,
    teleported: Boolean = false,
    verified: Boolean = false,
    vouched: Boolean = false,
    starred: Boolean = false,
): AnnotatedString = buildAnnotatedString {
    append(label.name)
    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 0.8.em, color = MaterialTheme.colorScheme.onSurfaceVariant)) {
        append(label.suffix)
    }
    if (verified) { append(" "); appendInlineContent(INLINE_VERIFIED, "[verified]") }
    else if (vouched) { append(" "); appendInlineContent(INLINE_VOUCHED, "[vouched]") }
    if (teleported) { append(" "); appendInlineContent(INLINE_TELEPORTED, "[teleported]") }
    if (starred) { append(" "); appendInlineContent(INLINE_STAR, "[favorite]") }
}

/** Replaces legacy glyph markers in localized prose with pure Compose Material vector inline icons. */
internal fun withInlineVectorBadges(raw: String, checkId: String = INLINE_VERIFIED): AnnotatedString = buildAnnotatedString {
    val cleaned = raw.replace("\u2708\uFE0E", "\u2708")
    var i = 0
    while (i < cleaned.length) {
        when (cleaned[i]) {
            '\u2708' -> appendInlineContent(INLINE_TELEPORTED, "[teleported]")
            '\u2713' -> appendInlineContent(checkId, "[verified]")
            else -> append(cleaned[i])
        }
        i++
    }
}

/** Title that wipes everything on a triple tap, with no confirmation (docs/protocol-spec.md §10). */
@Composable
fun PanicTitle(text: String, onPanic: () -> Unit, fontSize: Float = 17.5f) = PanicTitle(AnnotatedString(text), onPanic, fontSize)

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
fun PanicTitle(text: AnnotatedString, onPanic: () -> Unit, fontSize: Float = 17.5f) {
    val tap = rememberPanicTaps(onPanic)
    val inlineIcons = rememberLabelInlineIcons()
    Text(
        text,
        inlineContent = inlineIcons,
        fontSize = fontSize.sp,
        lineHeight = (fontSize + 5f).sp,
        fontWeight = if (fontSize >= 20f) FontWeight.Bold else FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = tap),
    )
}

/**
 * Minimalist geometric radio-node mark in the top-left corner (`46dp` triple-tap panic target):
 * calm monochrome concentric wave arcs around a single 6dp Signal-orange core dot.
 */
@Composable
fun PanicLogo(onPanic: () -> Unit) {
    val tap = rememberPanicTaps(onPanic)
    val desc = stringResource(R.string.panic_logo)
    val ink = OpenChatPalette.Ink
    val signal = OpenChatPalette.Signal
    Box(
        modifier = Modifier
            .size(46.dp)
            .semantics { contentDescription = desc }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = tap),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val strokePx = 1.75.dp.toPx()
            val r = 7.4.dp.toPx()
            val arcTopLeft = Offset(center.x - r, center.y - r)
            val arcSize = Size(r * 2f, r * 2f)
            drawArc(
                color = ink.copy(alpha = 0.85f),
                startAngle = -46f,
                sweepAngle = 92f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            drawArc(
                color = ink.copy(alpha = 0.85f),
                startAngle = 134f,
                sweepAngle = 92f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            drawCircle(color = signal, radius = 2.8.dp.toPx(), center = center)
        }
    }
}

@Composable
private fun ProblemBanner(
    missing: Boolean,
    locked: Boolean,
    btOn: Boolean,
    running: Boolean,
    onGrant: () -> Unit,
    onEnable: () -> Unit,
) {
    val ctx = LocalContext.current
    val problem = when {
        locked -> stringResource(R.string.banner_storage_locked) to { MeshService.start(ctx) }
        missing -> stringResource(R.string.banner_need_permission) to onGrant
        !btOn -> stringResource(R.string.banner_bt_off) to onEnable
        !running -> stringResource(R.string.banner_starting) to { MeshService.start(ctx) }
        else -> return
    }
    val warn = missing || locked || !btOn
    val (text, action) = problem
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (warn) MaterialTheme.colorScheme.errorContainer else OpenChatPalette.Surface)
                .clickable { action() }
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (locked) {
                    Text(
                        stringResource(R.string.storage_locked_hint, stringResource(R.string.me_wipe_title), stringResource(R.string.tab_me)),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Hairline()
    }
}
