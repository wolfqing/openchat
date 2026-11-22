package org.openchat.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.Conversation
import org.openchat.android.app.PeerLabel
import org.openchat.android.app.Social
import org.openchat.android.app.SocialState
import org.openchat.android.mesh.MeshPeer
import java.util.Locale

/**
 * Orders reachable mesh peers for the nearby sheet: direct neighbours first, then closer
 * multi-hop relays, then most recently heard, with stable alphabetical / peer-ID tie-breakers.
 */
internal fun sortNearbyPeers(peers: List<MeshPeer>, myPeerHex: String): List<MeshPeer> =
    peers
        .filter { it.peerIdHex != myPeerHex }
        .sortedWith(
            compareByDescending<MeshPeer> { it.direct }
                .thenBy { it.hops ?: Int.MAX_VALUE }
                .thenByDescending { it.lastSeen }
                .thenBy { it.nickname.lowercase() }
                .thenBy { it.peerIdHex },
        )

/**
 * Unified Nearby Peers & Live 3-Ring Mesh Topology sheet (V2 Braun-instrument layout):
 * - Top 2-segment switch between Grouped Peer Directory and Inline Live Mesh Topology
 * - Built-in Bluetooth radio & patrol self-check bar
 * - Strict 1-hop Direct vs Multi-hop Relayed grouping with expandable quick-action tray
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NearbyPeersSheet(
    onDismiss: () -> Unit,
    onSelectPerson: (PersonRef.Mesh) -> Unit,
    onMessage: (String) -> Unit,
    onOpenTopology: () -> Unit,
) {
    val peers by ChatRepository.peers.collectAsState()
    val mesh by ChatRepository.mesh.collectAsState()
    val names by ChatRepository.names.collectAsState()
    val social by Social.state.collectAsState()
    val now = rememberNow()
    val locale = appLocale()
    val inlineIcons = rememberLabelInlineIcons()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val sorted = remember(peers, mesh.myPeerHex) { sortNearbyPeers(peers, mesh.myPeerHex) }
    val directPeers = remember(sorted) { sorted.filter { it.direct } }
    val relayedPeers = remember(sorted) { sorted.filterNot { it.direct } }
    val directCount = directPeers.size
    val relayedCount = relayedPeers.size
    val peerByHex = remember(peers) { peers.associateBy { it.peerIdHex } }

    var activeTab by rememberSaveable { mutableIntStateOf(0) } // 0 = peer directory, 1 = inline topology
    var showDiag by remember { mutableStateOf(false) }
    var expandedPeerHex by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OpenChatPalette.Surface,
        contentColor = OpenChatPalette.Ink,
    ) {
        CompositionLocalProvider(LocalOnElevatedSurface provides true) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 1. Header row: Title + concise "3 直连 / 8 中继" badge
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.nearby_peers_title),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = OpenChatPalette.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Surface(
                        shape = CircleShape,
                        color = OpenChatPalette.Well,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .background(
                                        if (sorted.isNotEmpty()) OpenChatPalette.Signal else OpenChatPalette.Muted,
                                        CircleShape,
                                    ),
                            )
                            Text(
                                "$directCount ${stringResource(R.string.band_direct)} / $relayedCount ${stringResource(R.string.relay_step_relay)}",
                                style = TabularMonoStyle,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OpenChatPalette.Ink,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // 2. 2-segment switch rail: Peer Directory | Live Topology
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = OpenChatPalette.Well,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        listOf(
                            0 to stringResource(R.string.peers_tab_list, sorted.size),
                            1 to stringResource(R.string.peers_tab_topology),
                        ).forEach { (idx, label) ->
                            val selected = activeTab == idx
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (selected) OpenChatPalette.Surface else Color.Transparent)
                                    .clickable(role = Role.Tab) { activeTab = idx }
                                    .padding(vertical = 7.dp, horizontal = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) OpenChatPalette.Ink else OpenChatPalette.Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                if (activeTab == 1) {
                    // Inline 3-ring live topology preview + expand to interactive fullscreen
                    InlineTopologyPreview(
                        myPeerHex = mesh.myPeerHex.ifEmpty { "self" },
                        myNickname = mesh.myNickname,
                        peers = peers,
                        onExpandFull = onOpenTopology,
                    )
                } else {
                    // 3. Bluetooth radio & background patrol self-check strip
                    val radioReady = mesh.bluetoothOn && mesh.running && !mesh.storageLocked
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (radioReady) OpenChatPalette.Well else OpenChatPalette.SignalSoft,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { showDiag = !showDiag },
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(6.5.dp)
                                        .background(
                                            if (radioReady) OpenChatPalette.Signal else MaterialTheme.colorScheme.error,
                                            CircleShape,
                                        ),
                                )
                                Text(
                                    stringResource(if (radioReady) R.string.peers_diag_ok else R.string.peers_diag_warn),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = OpenChatPalette.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    stringResource(R.string.peers_diag_check) + if (showDiag) " ▴" else " ▾",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OpenChatPalette.Signal,
                                    maxLines = 1,
                                )
                            }
                            AnimatedVisibility(visible = showDiag) {
                                Column(
                                    Modifier.padding(top = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Hairline()
                                    DiagLine("Bluetooth", mesh.bluetoothOn)
                                    DiagLine("Mesh patrol", mesh.running)
                                    DiagLine("Active links (${mesh.links})", mesh.links > 0 || sorted.isNotEmpty())
                                }
                            }
                        }
                    }

                    // 4. Grouped Direct & Multi-hop peer lists
                    if (sorted.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = OpenChatPalette.Well,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.nearby_peers_empty),
                                fontSize = 13.5.sp,
                                lineHeight = 20.sp,
                                color = OpenChatPalette.Muted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                            )
                        }
                    } else {
                        val wellBg = OpenChatPalette.Well
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (directPeers.isNotEmpty()) {
                                item(key = "section-direct") {
                                    PeerSectionGroup(
                                        title = stringResource(R.string.peers_direct_hint),
                                        count = directPeers.size,
                                        isRelayedGroup = false,
                                        peers = directPeers,
                                        peerByHex = peerByHex,
                                        names = names,
                                        wellBg = wellBg,
                                        social = social,
                                        now = now,
                                        locale = locale,
                                        inlineIcons = inlineIcons,
                                        expandedPeerHex = expandedPeerHex,
                                        onToggleExpand = { hex -> expandedPeerHex = if (expandedPeerHex == hex) null else hex },
                                        onSelectPerson = onSelectPerson,
                                        onMessage = onMessage,
                                    )
                                }
                            }
                            if (relayedPeers.isNotEmpty()) {
                                item(key = "section-relayed") {
                                    PeerSectionGroup(
                                        title = stringResource(R.string.peers_relayed_hint),
                                        count = relayedPeers.size,
                                        isRelayedGroup = true,
                                        peers = relayedPeers,
                                        peerByHex = peerByHex,
                                        names = names,
                                        wellBg = wellBg,
                                        social = social,
                                        now = now,
                                        locale = locale,
                                        inlineIcons = inlineIcons,
                                        expandedPeerHex = expandedPeerHex,
                                        onToggleExpand = { hex -> expandedPeerHex = if (expandedPeerHex == hex) null else hex },
                                        onSelectPerson = onSelectPerson,
                                        onMessage = onMessage,
                                    )
                                }
                            }
                        }
                    }
                }

                TonalActionButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Text(stringResource(R.string.close), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun DiagLine(label: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 11.5.sp, color = OpenChatPalette.Muted)
        Text(
            if (ok) "OK" else "—",
            style = TabularMonoStyle,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (ok) OpenChatPalette.Ink else OpenChatPalette.Signal,
        )
    }
}

@Composable
private fun PeerSectionGroup(
    title: String,
    count: Int,
    isRelayedGroup: Boolean,
    peers: List<MeshPeer>,
    peerByHex: Map<String, MeshPeer>,
    names: Map<String, String>,
    wellBg: Color,
    social: SocialState,
    now: Long,
    locale: Locale,
    inlineIcons: Map<String, InlineTextContent>,
    expandedPeerHex: String?,
    onToggleExpand: (String) -> Unit,
    onSelectPerson: (PersonRef.Mesh) -> Unit,
    onMessage: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isRelayedGroup) OpenChatPalette.Signal else OpenChatPalette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$count",
                style = TabularMonoStyle,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpenChatPalette.Muted,
            )
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = wellBg,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                peers.forEachIndexed { idx, peer ->
                    if (idx > 0) Hairline(Modifier.padding(start = 64.dp, end = 12.dp))
                    val hex = peer.peerIdHex
                    val blocked = social.isBlockedPeer(hex)
                    val fav = social.favoriteOf(hex)
                    val isFav = fav?.mine == true
                    val expanded = expandedPeerHex == hex
                    val viaPeerLabel = peer.heardViaPeerHex?.let { viaHex ->
                        val viaPeer = peerByHex[viaHex]
                        PeerLabel.mesh(viaPeer?.nickname ?: names[viaHex], viaHex).toString()
                    }
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isRelayedGroup) onToggleExpand(hex)
                                    else onSelectPerson(PersonRef.Mesh(hex, peer.nickname))
                                }
                                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                        ) {
                            Box(Modifier.size(40.dp)) {
                                EmblemView(hex, 38.dp)
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(10.dp)
                                        .background(
                                            if (peer.direct) OpenChatPalette.PrimaryAction else OpenChatPalette.Signal,
                                            CircleShape,
                                        )
                                        .border(2.dp, wellBg, CircleShape),
                                )
                            }
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    labelText(
                                        PeerLabel.mesh(peer.nickname, hex),
                                        verified = social.isVerifiedPeer(hex),
                                        vouched = social.isVouchedPeer(hex),
                                        starred = isFav,
                                    ),
                                    inlineContent = inlineIcons,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OpenChatPalette.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (blocked) {
                                    Text(
                                        stringResource(R.string.peer_blocked_short),
                                        style = TabularMonoStyle,
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 1,
                                    )
                                } else {
                                    val sub = if (isRelayedGroup && viaPeerLabel != null) {
                                        stringResource(R.string.peers_via, viaPeerLabel) + " · " + agoText(peer.lastSeen, now, locale)
                                    } else {
                                        val hopStr = when {
                                            peer.direct || peer.hops == 1 -> stringResource(R.string.hops_direct)
                                            peer.hops != null -> hopsText(peer.hops)
                                            else -> stringResource(R.string.dm_relayed)
                                        }
                                        "$hopStr · ${agoText(peer.lastSeen, now, locale)}"
                                    }
                                    Text(
                                        sub,
                                        style = TabularMonoStyle,
                                        fontSize = 11.5.sp,
                                        color = OpenChatPalette.Muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (!blocked) {
                                if (isRelayedGroup) {
                                    Surface(
                                        shape = CircleShape,
                                        color = OpenChatPalette.SignalSoft,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                    ) {
                                        Text(
                                            peer.hops?.let { hopsText(it) } ?: stringResource(R.string.relay_step_relay),
                                            style = TabularMonoStyle,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = OpenChatPalette.Signal,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                } else {
                                    Surface(
                                        shape = CircleShape,
                                        color = OpenChatPalette.Surface,
                                        border = BorderStroke(0.5.dp, OpenChatPalette.Line),
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                        modifier = Modifier
                                            .pressScale(0.95f)
                                            .clip(CircleShape)
                                            .clickable(role = Role.Button) { onMessage(hex) },
                                    ) {
                                        Text(
                                            stringResource(R.string.peers_msg_pill),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = OpenChatPalette.Ink,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                        )
                                    }
                                }
                            }
                        }

                        // Expandable 4-action quick tray on multi-hop peers
                        AnimatedVisibility(visible = expanded && !blocked) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(start = 62.dp, end = 12.dp, bottom = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                PeerQuickChip(
                                    label = stringResource(R.string.peers_action_msg),
                                    primary = true,
                                    onClick = { onMessage(hex) },
                                )
                                PeerQuickChip(
                                    label = stringResource(R.string.peers_action_trace),
                                    primary = false,
                                    onClick = {
                                        val path = ChatRepository.controller?.estimatePath(hex)
                                        val targetLabel = PeerLabel.mesh(peer.nickname, hex).toString()
                                        val text = if (path == null) {
                                            "Trace $targetLabel: —"
                                        } else {
                                            (listOf("You") + path + targetLabel).joinToString(" → ") + " (${path.size + 1})"
                                        }
                                        ChatRepository.addLocal(Conversation.Mesh.key, text)
                                    },
                                )
                                PeerQuickChip(
                                    label = stringResource(R.string.peers_action_verify),
                                    primary = false,
                                    onClick = { onSelectPerson(PersonRef.Mesh(hex, peer.nickname)) },
                                )
                                PeerQuickChip(
                                    label = stringResource(if (isFav) R.string.peers_action_unfav else R.string.peers_action_fav),
                                    primary = false,
                                    onClick = { ChatRepository.controller?.setFavorite(hex, peer.nickname, !isFav) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerQuickChip(label: String, primary: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (primary) OpenChatPalette.PrimaryAction else OpenChatPalette.Surface,
        border = if (primary) null else BorderStroke(0.5.dp, OpenChatPalette.Line),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .pressScale(0.95f)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Text(
            label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) OpenChatPalette.OnPrimaryAction else OpenChatPalette.Ink,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** Compact inline 3-ring radial mesh topology dish inside the unified Nearby Peers sheet. */
@Composable
private fun InlineTopologyPreview(
    myPeerHex: String,
    myNickname: String,
    peers: List<MeshPeer>,
    onExpandFull: () -> Unit,
) {
    val graph = remember(myPeerHex, myNickname, peers) { buildTopologyGraph(myPeerHex, myNickname, peers) }
    val ringColor = OpenChatPalette.Line
    val signal = OpenChatPalette.Signal
    val ink = OpenChatPalette.Ink
    val muted = OpenChatPalette.Muted
    val cardBg = OpenChatPalette.Well
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = muted)
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardBg,
        border = BorderStroke(0.5.dp, OpenChatPalette.Line),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth().padding(12.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(250.dp),
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val maxR = minOf(size.width, size.height) * 0.44f
                val rings = maxOf(2, graph.maxRing)
                val unit = maxR / rings

                for (r in 1..rings) {
                    drawCircle(ringColor, r * unit, Offset(cx, cy), style = Stroke(1.dp.toPx()))
                }
                val posOf = graph.nodes.associate { n ->
                    n.peerHex to Offset(cx + n.x * unit, cy + n.y * unit)
                }
                for (e in graph.edges) {
                    val a = posOf[e.fromHex] ?: continue
                    val b = posOf[e.toHex] ?: continue
                    drawLine(
                        color = if (e.kind == TopologyEdgeKind.DIRECT) ink.copy(alpha = 0.45f) else signal.copy(alpha = 0.55f),
                        start = a,
                        end = b,
                        strokeWidth = if (e.kind == TopologyEdgeKind.DIRECT) 1.6.dp.toPx() else 1.2.dp.toPx(),
                        pathEffect = if (e.kind == TopologyEdgeKind.INFERRED) dash else null,
                    )
                }
                for (n in graph.nodes) {
                    val p = posOf[n.peerHex] ?: continue
                    val r = if (n.isSelf) 9.dp.toPx() else if (n.ring == 1) 7.dp.toPx() else 6.dp.toPx()
                    drawCircle(
                        color = when {
                            n.isSelf -> signal
                            n.ring == 1 -> ink
                            else -> signal.copy(alpha = 0.85f)
                        },
                        radius = r,
                        center = p,
                    )
                    val tag = if (n.isSelf) "ME" else n.nickname.take(6).ifEmpty { n.peerHex.take(4) }
                    val m = measurer.measure(tag, labelStyle)
                    drawText(m, topLeft = Offset(p.x - m.size.width / 2f, p.y + r + 3.dp.toPx()))
                }
            }
            Surface(
                shape = CircleShape,
                color = OpenChatPalette.Surface,
                border = BorderStroke(0.5.dp, OpenChatPalette.Line),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .pressScale(0.95f)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onExpandFull),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Outlined.OpenInFull, contentDescription = null, tint = OpenChatPalette.Ink, modifier = Modifier.size(13.dp))
                    Text(
                        stringResource(R.string.topology_title),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpenChatPalette.Ink,
                    )
                }
            }
        }
    }
}
