package org.openchat.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import kotlin.math.hypot
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.openchat.android.R
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.LiveVoicePrefs
import org.openchat.android.app.PeerLabel
import org.openchat.android.app.PttFloorCoordinator
import org.openchat.android.app.PttFloorState
import org.openchat.android.app.PttStartResult
import org.openchat.android.app.PttTones

/** Crisp handheld two-way radio vector icon (antenna + chassis + speaker grille + side PTT button). */
val WalkieTalkieIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "WalkieTalkie",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Antenna + top tuning knob + radio body outline + side PTT nub + speaker grille bars
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.85f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero,
        ) {
            // Antenna
            moveTo(9f, 6.5f)
            lineTo(9f, 2.3f)
            // Tuning knob
            moveTo(14.5f, 6.5f)
            lineTo(14.5f, 4.6f)
            // Side PTT key
            moveTo(6f, 10.5f)
            lineTo(4.3f, 10.5f)
            lineTo(4.3f, 14.2f)
            lineTo(6f, 14.2f)
            // Rounded body (x: 6..18, y: 6.5..21.5)
            moveTo(8.5f, 6.5f)
            lineTo(15.5f, 6.5f)
            quadTo(18f, 6.5f, 18f, 9f)
            lineTo(18f, 19f)
            quadTo(18f, 21.5f, 15.5f, 21.5f)
            lineTo(8.5f, 21.5f)
            quadTo(6f, 21.5f, 6f, 19f)
            lineTo(6f, 9f)
            quadTo(6f, 6.5f, 8.5f, 6.5f)
            close()
            // Speaker grille lines
            moveTo(9.2f, 10.5f)
            lineTo(14.8f, 10.5f)
            moveTo(9.2f, 13.2f)
            lineTo(14.8f, 13.2f)
            // Lower LCD / badge slot
            moveTo(9.8f, 17.2f)
            lineTo(14.2f, 17.2f)
        }
    }.build()
}

private enum class PttUnavailable { MUTED, NO_PEERS, PEER_AWAY, HANDSHAKING }

private val BusyAmber = Color(0xFFF59E0B)

/** Height of the docked half-screen Walkie-Talkie drawer, used by [ChatPane] to pad the timeline above it. */
internal val WALKIE_TALKIE_DRAWER_HEIGHT = 246.dp

/**
 * Half-duplex Walkie-Talkie drawer docked at the bottom of Nearby or Private 1-on-1 chats:
 * - Strict single-speaker floor lock: locks out local PTT when a peer is speaking.
 * - Pure live streaming (§29): plays and transmits live audio without creating chat voice bubbles.
 */
@Composable
fun WalkieTalkieDrawer(
    conversation: String,
    onCloseToKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isLight = OpenChatPalette.isLight
    val reducedMotion = rememberReducedMotion()

    val pttStates by ChatRepository.pttStates.collectAsState()
    val peers by ChatRepository.peers.collectAsState()
    val names by ChatRepository.names.collectAsState()
    val mesh by ChatRepository.mesh.collectAsState()
    val sessionPeers by ChatRepository.sessionPeers.collectAsState()
    val liveVoiceOn by LiveVoicePrefs.enabledFlow.collectAsState()
    val liveMicLevel by ChatRepository.liveMicLevel.collectAsState()
    val liveRxLevel by ChatRepository.liveRxLevel.collectAsState()

    val peerHex = remember(conversation) { conversation.removePrefix("dm:").takeIf { conversation.startsWith("dm:") } }
    val encrypted = peerHex != null

    val otherPeers = remember(peers, mesh.myPeerHex) { peers.filter { it.peerIdHex != mesh.myPeerHex } }
    val dmPeer = remember(otherPeers, peerHex) { peerHex?.let { h -> otherPeers.firstOrNull { it.peerIdHex == h } } }
    val dmLabel = remember(peerHex, dmPeer, names) {
        peerHex?.let { PeerLabel.mesh(dmPeer?.nickname ?: names[it], it).toString() }.orEmpty()
    }

    val floorState = pttStates[conversation] ?: PttFloorState.Idle
    val unavailable = when {
        floorState !is PttFloorState.Idle -> null
        !liveVoiceOn -> PttUnavailable.MUTED
        !encrypted && otherPeers.isEmpty() -> PttUnavailable.NO_PEERS
        encrypted && dmPeer == null -> PttUnavailable.PEER_AWAY
        encrypted && peerHex !in sessionPeers -> PttUnavailable.HANDSHAKING
        else -> null
    }

    // Pre-warm Noise XX handshake when opening private Walkie-Talkie with a reachable peer
    LaunchedEffect(conversation, dmPeer?.peerIdHex, peerHex in sessionPeers) {
        if (peerHex != null && dmPeer != null && peerHex !in sessionPeers) {
            ChatRepository.controller?.preparePtt(conversation)
        }
    }

    DisposableEffect(conversation) {
        ChatRepository.controller?.preparePtt(conversation)
        onDispose { ChatRepository.controller?.cancelPtt() }
    }

    var hintText by remember(conversation) { mutableStateOf<String?>(null) }
    LaunchedEffect(hintText) {
        if (hintText != null) {
            delay(1_800)
            hintText = null
        }
    }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    val now = rememberNow()
    val rxSpeakerLabel = (floorState as? PttFloorState.Receiving)?.let {
        PeerLabel.mesh(it.senderName.ifEmpty { names[it.senderKey] }, it.senderKey).toString()
    }
    val rxHops = (floorState as? PttFloorState.Receiving)?.hops?.let { hopsText(it) }

    val tapHintStr = stringResource(R.string.ptt_tap_hint)
    val busyHintTemplate = stringResource(R.string.ptt_busy_hint, rxSpeakerLabel.orEmpty())

    // Keep fresh references inside the long-lived pointerInput gesture block
    val latestFloor by rememberUpdatedState(floorState)
    val latestUnavailable by rememberUpdatedState(unavailable)
    val latestBusyHint by rememberUpdatedState(busyHintTemplate)

    val statusDotColor = when {
        floorState is PttFloorState.Transmitting -> OpenChatPalette.Signal
        floorState is PttFloorState.Receiving -> BusyAmber
        unavailable != null -> MaterialTheme.colorScheme.outline
        else -> OpenChatPalette.Online
    }

    val statusLineText = when {
        hintText != null -> hintText!!
        floorState is PttFloorState.Transmitting -> stringResource(R.string.ptt_tx_status)
        floorState is PttFloorState.Receiving -> {
            val base = stringResource(R.string.live_ptt_speaking, rxSpeakerLabel.orEmpty())
            if (rxHops != null && !encrypted) "$base · $rxHops" else base
        }
        unavailable == PttUnavailable.MUTED -> stringResource(R.string.ptt_muted_hint)
        unavailable == PttUnavailable.NO_PEERS -> stringResource(R.string.ptt_no_peers)
        unavailable == PttUnavailable.PEER_AWAY -> stringResource(R.string.ptt_peer_away)
        unavailable == PttUnavailable.HANDSHAKING -> stringResource(R.string.ptt_handshaking)
        encrypted -> stringResource(R.string.ptt_idle_private, dmLabel)
        else -> stringResource(R.string.ptt_idle_nearby, otherPeers.size)
    }

    val panelBg = OpenChatPalette.CardSurface
    val activeLevel = when (floorState) {
        is PttFloorState.Transmitting -> liveMicLevel
        is PttFloorState.Receiving -> liveRxLevel
        else -> 0f
    }
    val animatedRingScale by animateFloatAsState(
        targetValue = if (reducedMotion || floorState is PttFloorState.Idle) 1f else 1.04f + activeLevel * 0.16f,
        animationSpec = tween(90, easing = FastOutSlowInEasing),
        label = "ptt-ring-scale",
    )
    val discDesc = stringResource(R.string.ptt_hold_to_talk)
    val bottomPrompt = when (val st = floorState) {
        is PttFloorState.Transmitting -> {
            val elapsedSec = ((now - st.startedAt) / 1000L).coerceAtLeast(0L)
            val remainingSec = (PttFloorCoordinator.MAX_HOLD_MS / 1000L - elapsedSec).coerceAtLeast(0L)
            if (remainingSec <= 5L) stringResource(R.string.ptt_tx_remaining, remainingSec.toInt())
            else "${String.format(appLocale(), "%d:%02d", elapsedSec / 60, elapsedSec % 60)} · ${stringResource(R.string.ptt_release_to_end)}"
        }
        is PttFloorState.Receiving -> stringResource(R.string.ptt_rx_locked)
        PttFloorState.Idle -> when {
            unavailable == PttUnavailable.MUTED -> stringResource(R.string.ptt_muted_enable)
            else -> stringResource(R.string.ptt_hold_to_talk)
        }
    }

    var showListeners by remember(conversation) { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        shape = RoundedCornerShape(24.dp),
        color = panelBg,
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            if (isLight) Color(0xFF18140E).copy(alpha = 0.09f) else Color.White.copy(alpha = 0.10f),
        ),
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        CompositionLocalProvider(LocalOnElevatedSurface provides true) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Top grip bar
                Box(
                    Modifier
                        .width(30.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(OpenChatPalette.Outline.copy(alpha = 0.5f)),
                )

                // 1. Compact status strip + expandable listener list toggle
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(
                                if (floorState is PttFloorState.Transmitting) OpenChatPalette.Signal else statusDotColor,
                                CircleShape,
                            ),
                    )
                    Text(
                        statusLineText,
                        fontSize = 12.sp,
                        fontWeight = if (floorState !is PttFloorState.Idle || hintText != null) FontWeight.SemiBold else FontWeight.Medium,
                        color = when {
                            hintText != null -> BusyAmber
                            floorState is PttFloorState.Transmitting -> OpenChatPalette.Signal
                            floorState is PttFloorState.Receiving -> if (isLight) Color(0xFFB45309) else BusyAmber
                            else -> OpenChatPalette.Ink
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!encrypted && otherPeers.isNotEmpty()) {
                        Text(
                            stringResource(R.string.ptt_listeners, otherPeers.size) + if (showListeners) " ▴" else " ▾",
                            style = TabularMonoStyle,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = OpenChatPalette.Muted,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(role = Role.Button) { showListeners = !showListeners }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    } else {
                        Text(
                            "PTT",
                            style = TabularMonoStyle,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpenChatPalette.Muted,
                        )
                    }
                }

                AnimatedVisibility(visible = showListeners && !encrypted && otherPeers.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        otherPeers.take(12).forEach { p ->
                            Surface(
                                shape = CircleShape,
                                color = OpenChatPalette.Well,
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(5.dp)
                                            .background(
                                                if (p.direct) OpenChatPalette.Signal else OpenChatPalette.Muted,
                                                CircleShape,
                                            ),
                                    )
                                    Text(
                                        PeerLabel.mesh(p.nickname, p.peerIdHex).toString(),
                                        fontSize = 11.sp,
                                        color = OpenChatPalette.Ink,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. 18-bar recessed radio VU strip
                PttVuMeterStrip(
                    active = floorState is PttFloorState.Transmitting || floorState is PttFloorState.Receiving,
                    level = activeLevel,
                    transmitting = floorState is PttFloorState.Transmitting,
                    modifier = Modifier.fillMaxWidth().height(26.dp),
                )

                // 3. 3-control radio stage: [Speaker/Mute] — (Circular 3D Anti-Slip Knurled PTT) — [Text]
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FluidGlassCircleButton(
                            icon = if (liveVoiceOn) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                            contentDescription = stringResource(if (liveVoiceOn) R.string.live_ptt_mute else R.string.live_ptt_unmute),
                            onClick = { LiveVoicePrefs.setEnabled(ctx, !liveVoiceOn) },
                            size = 44.dp,
                            iconSize = 19.dp,
                            iconTint = if (liveVoiceOn) OpenChatPalette.Ink else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            stringResource(if (liveVoiceOn) R.string.ptt_speaker_label else R.string.ptt_muted_label),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (liveVoiceOn) OpenChatPalette.Muted else MaterialTheme.colorScheme.error,
                        )
                    }

                    Box(
                        Modifier.size(116.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!reducedMotion && (floorState is PttFloorState.Transmitting || floorState is PttFloorState.Receiving)) {
                            val ringColor = if (floorState is PttFloorState.Transmitting) OpenChatPalette.Signal else BusyAmber
                            Canvas(Modifier.size(116.dp)) {
                                val baseR = 49.dp.toPx()
                                drawCircle(
                                    color = ringColor.copy(alpha = 0.25f),
                                    radius = baseR * animatedRingScale,
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                            }
                        }

                        // Recessed outer collar
                        Box(
                            Modifier
                                .size(104.dp)
                                .clip(CircleShape)
                                .background(OpenChatPalette.Well, CircleShape)
                                .border(
                                    1.dp,
                                    if (floorState is PttFloorState.Transmitting) OpenChatPalette.Signal.copy(alpha = 0.35f)
                                    else OpenChatPalette.Line,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(92.dp)
                                    .clip(CircleShape)
                                    .semantics {
                                        contentDescription = discDesc
                                        role = Role.Button
                                    }
                                    .pointerInput(conversation) {
                                        detectTapGestures(
                                            onPress = {
                                                val curFloor = latestFloor
                                                val curUnavail = latestUnavailable
                                                if (curFloor is PttFloorState.Receiving) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    PttTones.playBusy(ctx)
                                                    hintText = latestBusyHint
                                                    return@detectTapGestures
                                                }
                                                if (curUnavail == PttUnavailable.MUTED) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    LiveVoicePrefs.setEnabled(ctx, true)
                                                    return@detectTapGestures
                                                }
                                                if (curUnavail != null) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    ChatRepository.controller?.preparePtt(conversation)
                                                    return@detectTapGestures
                                                }
                                                val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                                if (!granted) {
                                                    micPerm.launch(Manifest.permission.RECORD_AUDIO)
                                                    return@detectTapGestures
                                                }
                                                val pressedAt = System.currentTimeMillis()
                                                val result = ChatRepository.controller?.startPtt(conversation) ?: PttStartResult.MIC_FAILED
                                                when (result) {
                                                    PttStartResult.STARTED -> {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        val released = tryAwaitRelease()
                                                        val heldMs = System.currentTimeMillis() - pressedAt
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        if (!released) {
                                                            ChatRepository.controller?.cancelPtt()
                                                        } else {
                                                            val sent = ChatRepository.controller?.stopPtt() == true
                                                            if (!sent && heldMs < PttFloorCoordinator.MIN_HOLD_MS) {
                                                                hintText = tapHintStr
                                                            }
                                                        }
                                                    }
                                                    PttStartResult.BUSY -> {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        hintText = latestBusyHint
                                                    }
                                                    else -> {
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                }
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                // Convex circular cap with 3D honeycomb anti-slip micro-granules
                                CircularKnurledPttCap(
                                    transmitting = floorState is PttFloorState.Transmitting,
                                    receiving = floorState is PttFloorState.Receiving,
                                    disabled = unavailable != null && unavailable != PttUnavailable.MUTED,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                when (val st = floorState) {
                                    is PttFloorState.Receiving -> EmblemView(st.senderKey, 34.dp)
                                    PttFloorState.Idle -> if (unavailable == PttUnavailable.MUTED) {
                                        Icon(
                                            Icons.AutoMirrored.Outlined.VolumeOff,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.90f),
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FluidGlassCircleButton(
                            icon = Icons.Outlined.Keyboard,
                            contentDescription = stringResource(R.string.ptt_keyboard),
                            onClick = {
                                ChatRepository.controller?.cancelPtt()
                                onCloseToKeyboard()
                            },
                            size = 44.dp,
                            iconSize = 19.dp,
                            iconTint = OpenChatPalette.Ink,
                        )
                        Text(
                            stringResource(R.string.ptt_keyboard_label),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = OpenChatPalette.Muted,
                        )
                    }
                }

                // 4. Tactile action caption
                Text(
                    bottomPrompt,
                    fontSize = 11.5.sp,
                    fontWeight = if (floorState is PttFloorState.Transmitting) FontWeight.SemiBold else FontWeight.Medium,
                    color = when {
                        floorState is PttFloorState.Transmitting -> OpenChatPalette.Signal
                        floorState is PttFloorState.Receiving -> if (isLight) Color(0xFFB45309) else BusyAmber
                        else -> OpenChatPalette.Muted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PttVuMeterStrip(
    active: Boolean,
    level: Float,
    transmitting: Boolean,
    modifier: Modifier = Modifier,
) {
    val wellBg = OpenChatPalette.Well
    val idleBar = OpenChatPalette.Outline.copy(alpha = 0.45f)
    val flankBar = OpenChatPalette.Ink.copy(alpha = 0.42f)
    val signalBar = if (transmitting) OpenChatPalette.Signal else BusyAmber
    val profile = remember {
        floatArrayOf(0.32f, 0.48f, 0.72f, 0.56f, 0.88f, 0.96f, 0.78f, 0.62f, 0.94f, 0.84f, 0.54f, 0.72f, 0.64f, 0.44f, 0.56f, 0.36f, 0.46f, 0.28f)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(wellBg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val count = profile.size
            val barW = 3.5.dp.toPx()
            val totalGap = (size.width - count * barW).coerceAtLeast(0f)
            val gap = if (count > 1) totalGap / (count - 1) else 0f
            val boost = if (active) (0.38f + level.coerceIn(0f, 1f) * 0.62f) else 0.26f
            for (i in 0 until count) {
                val h = (size.height * profile[i] * boost).coerceAtLeast(3.dp.toPx())
                val x = i * (barW + gap)
                val y = (size.height - h) * 0.5f
                val c = when {
                    !active -> idleBar
                    i in 4..11 -> signalBar
                    else -> flankBar
                }
                drawRoundRect(
                    color = c,
                    topLeft = Offset(x, y),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW * 0.5f, barW * 0.5f),
                )
            }
        }
    }
}

/**
 * Tactile circular PTT cap featuring a convex radial bevel, machined inner groove ring,
 * and a staggered honeycomb matrix of 3D raised anti-slip micro-granules.
 */
@Composable
private fun CircularKnurledPttCap(
    transmitting: Boolean,
    receiving: Boolean,
    disabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val r = size.minDimension * 0.5f
        val c = center
        val gradientColors = when {
            transmitting -> listOf(Color(0xFFF26D3D), Color(0xFFD94B1C), Color(0xFFAE340D))
            receiving -> listOf(Color(0xFF4A3A22), Color(0xFF2B2318), Color(0xFF1A1611))
            disabled -> listOf(Color(0xFF56544F), Color(0xFF3E3D39), Color(0xFF2D2C29))
            else -> listOf(Color(0xFF3E4047), Color(0xFF252421), Color(0xFF161513))
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = gradientColors,
                center = Offset(c.x - r * 0.26f, c.y - r * 0.34f),
                radius = r * 1.45f,
            ),
            radius = r,
            center = c,
        )
        // Outer specular bevel highlight
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (transmitting) 0.42f else 0.22f),
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.32f),
                ),
                start = Offset(c.x - r, c.y - r),
                end = Offset(c.x + r, c.y + r),
            ),
            radius = r - 1.dp.toPx(),
            center = c,
            style = Stroke(width = 1.5.dp.toPx()),
        )
        // Machined inner groove
        val grooveR = r * 0.84f
        drawCircle(
            color = Color.Black.copy(alpha = 0.26f),
            radius = grooveR,
            center = c,
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.16f),
            radius = grooveR + 0.9.dp.toPx(),
            center = c,
            style = Stroke(width = 0.65.dp.toPx()),
        )

        // Staggered hexagonal honeycomb 3D anti-slip granules
        val maxStudR = r * 0.74f
        val dy = 6.2.dp.toPx()
        val dx = 6.8.dp.toPx()
        val studR = 1.5.dp.toPx()
        val shadowOffset = Offset(0.6.dp.toPx(), 0.8.dp.toPx())
        val specOffset = Offset(-0.35.dp.toPx(), -0.35.dp.toPx())
        val studAlpha = if (disabled) 0.18f else 0.32f
        val specAlpha = if (disabled) 0.24f else 0.55f
        var rowIdx = 0
        var y = c.y - maxStudR
        while (y <= c.y + maxStudR) {
            val shift = if (rowIdx % 2 == 1) dx * 0.5f else 0f
            var x = c.x - maxStudR + shift
            while (x <= c.x + maxStudR) {
                if (hypot(x - c.x, y - c.y) <= maxStudR) {
                    val p = Offset(x, y)
                    drawCircle(Color.Black.copy(alpha = 0.38f), studR * 1.06f, p + shadowOffset)
                    drawCircle(Color.White.copy(alpha = studAlpha), studR, p)
                    drawCircle(Color.White.copy(alpha = specAlpha), studR * 0.42f, p + specOffset)
                }
                x += dx
            }
            y += dy
            rowIdx++
        }

        // Tactile center medallion indicator
        if (!receiving) {
            val centerRingR = r * 0.22f
            drawCircle(Color.Black.copy(alpha = 0.22f), centerRingR, c)
            drawCircle(Color.White.copy(alpha = 0.32f), centerRingR, c, style = Stroke(width = 0.9.dp.toPx()))
            drawCircle(
                Color.White.copy(alpha = if (transmitting) 0.96f else if (disabled) 0.38f else 0.68f),
                3.2.dp.toPx(),
                c,
            )
        }
    }
}
