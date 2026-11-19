package org.openchat.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
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
internal val WALKIE_TALKIE_DRAWER_HEIGHT = 216.dp

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
    val accent = if (encrypted) OpenChatPalette.Lock else OpenChatPalette.Signal
    val accentSoft = if (encrypted) OpenChatPalette.LockSoft else OpenChatPalette.SignalSoft

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
        floorState is PttFloorState.Transmitting -> accent
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

    val panelBg = if (isLight) Color(0xFFFDFEFF) else Color(0xFF101619)
    val panelBorder = when (floorState) {
        is PttFloorState.Transmitting -> accent.copy(alpha = 0.55f)
        is PttFloorState.Receiving -> BusyAmber.copy(alpha = 0.50f)
        else -> OpenChatPalette.Line
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .height(WALKIE_TALKIE_DRAWER_HEIGHT),
        shape = RoundedCornerShape(22.dp),
        color = panelBg,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1. Top status & control bar
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(8.5.dp)
                        .background(statusDotColor, CircleShape),
                )
                Text(
                    statusLineText,
                    fontSize = 12.5.sp,
                    fontWeight = if (floorState !is PttFloorState.Idle || hintText != null) FontWeight.SemiBold else FontWeight.Medium,
                    color = when {
                        hintText != null -> BusyAmber
                        floorState is PttFloorState.Transmitting -> accent
                        floorState is PttFloorState.Receiving -> if (isLight) Color(0xFFB45309) else BusyAmber
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                FluidGlassCircleButton(
                    icon = if (liveVoiceOn) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                    contentDescription = stringResource(if (liveVoiceOn) R.string.live_ptt_mute else R.string.live_ptt_unmute),
                    onClick = { LiveVoicePrefs.setEnabled(ctx, !liveVoiceOn) },
                    size = 34.dp,
                    iconSize = 17.dp,
                    iconTint = if (liveVoiceOn) OpenChatPalette.Ink else MaterialTheme.colorScheme.error,
                )
                FluidGlassPillButton(
                    onClick = {
                        ChatRepository.controller?.cancelPtt()
                        onCloseToKeyboard()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Keyboard,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = OpenChatPalette.Ink,
                    )
                    Text(
                        stringResource(R.string.ptt_keyboard),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpenChatPalette.Ink,
                    )
                }
            }

            // 2. Center 116dp Half-Duplex PTT Disc
            val activeLevel = when (floorState) {
                is PttFloorState.Transmitting -> liveMicLevel
                is PttFloorState.Receiving -> liveRxLevel
                else -> 0f
            }
            val animatedRingScale by animateFloatAsState(
                targetValue = if (reducedMotion || floorState is PttFloorState.Idle) 1f else 1.05f + activeLevel * 0.20f,
                animationSpec = tween(90, easing = FastOutSlowInEasing),
                label = "ptt-ring-scale",
            )

            val discFill = when {
                floorState is PttFloorState.Transmitting -> accent
                floorState is PttFloorState.Receiving -> BusyAmber.copy(alpha = if (isLight) 0.12f else 0.16f)
                unavailable != null -> if (isLight) Color(0xFFF1F4F9) else Color(0xFF171E22)
                else -> accentSoft
            }
            val discBorder = when {
                floorState is PttFloorState.Transmitting -> accent
                floorState is PttFloorState.Receiving -> BusyAmber.copy(alpha = 0.75f)
                unavailable != null -> OpenChatPalette.Line
                else -> accent.copy(alpha = if (isLight) 0.55f else 0.70f)
            }
            val discDesc = stringResource(R.string.ptt_hold_to_talk)

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (!reducedMotion && (floorState is PttFloorState.Transmitting || floorState is PttFloorState.Receiving)) {
                    val ringColor = if (floorState is PttFloorState.Transmitting) accent else BusyAmber
                    Canvas(Modifier.size(144.dp)) {
                        val baseR = 58.dp.toPx()
                        drawCircle(
                            color = ringColor.copy(alpha = 0.24f),
                            radius = baseR * animatedRingScale,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                        drawCircle(
                            color = ringColor.copy(alpha = 0.11f),
                            radius = baseR * (1f + (animatedRingScale - 1f) * 1.65f),
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }

                Box(
                    Modifier
                        .size(116.dp)
                        .clip(CircleShape)
                        .background(discFill, CircleShape)
                        .border(2.dp, discBorder, CircleShape)
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
                    when (val st = floorState) {
                        is PttFloorState.Transmitting -> {
                            val elapsedSec = ((now - st.startedAt) / 1000L).coerceAtLeast(0L)
                            val remainingSec = (PttFloorCoordinator.MAX_HOLD_MS / 1000L - elapsedSec).coerceAtLeast(0L)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) {
                                LiveEqualizerBars(
                                    active = true,
                                    level = liveMicLevel,
                                    barCount = 6,
                                    color = Color.White,
                                    modifier = Modifier.size(width = 34.dp, height = 18.dp),
                                )
                                Text(
                                    String.format(appLocale(), "%d:%02d", elapsedSec / 60, elapsedSec % 60),
                                    style = TabularMonoStyle,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                                Text(
                                    if (remainingSec <= 5L) stringResource(R.string.ptt_tx_remaining, remainingSec.toInt())
                                    else stringResource(R.string.ptt_release_to_end),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White.copy(alpha = 0.90f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        is PttFloorState.Receiving -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) {
                                EmblemView(st.senderKey, 36.dp)
                                LiveEqualizerBars(
                                    active = true,
                                    level = liveRxLevel,
                                    barCount = 5,
                                    color = BusyAmber,
                                    modifier = Modifier.size(width = 30.dp, height = 14.dp),
                                )
                                Text(
                                    stringResource(R.string.ptt_rx_locked),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isLight) Color(0xFFB45309) else BusyAmber,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        PttFloorState.Idle -> {
                            if (unavailable == PttUnavailable.MUTED) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.VolumeOff,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Text(
                                        stringResource(R.string.ptt_muted_enable),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = OpenChatPalette.Ink,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                    )
                                }
                            } else if (unavailable != null) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                ) {
                                    Icon(
                                        WalkieTalkieIcon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(26.dp),
                                    )
                                    Text(
                                        stringResource(R.string.ptt_hold_to_talk),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                    )
                                }
                            } else {
                                val iconTint = if (isLight) {
                                    if (encrypted) Color(0xFF0E56D8) else Color(0xFFD93F07)
                                } else accent
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                ) {
                                    Icon(
                                        WalkieTalkieIcon,
                                        contentDescription = null,
                                        tint = iconTint,
                                        modifier = Modifier.size(28.dp),
                                    )
                                    Text(
                                        stringResource(R.string.ptt_hold_to_talk),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = OpenChatPalette.Ink,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
        }
    }
}
