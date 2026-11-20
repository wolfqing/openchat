package org.openchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.PeerLabel
import org.openchat.android.app.Social
import org.openchat.android.mesh.MeshPeer

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
 * Bottom sheet opened by tapping the top signal capsule (`Direct X · Reach Y`):
 * lists everyone currently reachable over the Bluetooth mesh with hop distance, recency,
 * trust badges, and one-tap private messaging or profile inspection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NearbyPeersSheet(
    onDismiss: () -> Unit,
    onSelectPerson: (PersonRef.Mesh) -> Unit,
    onMessage: (String) -> Unit,
) {
    val peers by ChatRepository.peers.collectAsState()
    val mesh by ChatRepository.mesh.collectAsState()
    val social by Social.state.collectAsState()
    val now = rememberNow()
    val locale = appLocale()
    val inlineIcons = rememberLabelInlineIcons()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val sorted = remember(peers, mesh.myPeerHex) { sortNearbyPeers(peers, mesh.myPeerHex) }
    val directCount = remember(sorted) { sorted.count { it.direct } }

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
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.nearby_peers_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = OpenChatPalette.Ink,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        SignalPulseDot(live = sorted.isNotEmpty())
                        Text(
                            stringResource(R.string.nearby_peers_summary, directCount, sorted.size),
                            style = TabularMonoStyle,
                            fontSize = 12.sp,
                            color = OpenChatPalette.Muted,
                        )
                    }
                }

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
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = wellBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            itemsIndexed(sorted, key = { _, p -> p.peerIdHex }) { idx, peer ->
                                if (idx > 0) Hairline(Modifier.padding(start = 66.dp))
                                val hex = peer.peerIdHex
                                val blocked = social.isBlockedPeer(hex)
                                val fav = social.favoriteOf(hex)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectPerson(PersonRef.Mesh(hex, peer.nickname)) }
                                        .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(Modifier.size(42.dp)) {
                                        EmblemView(hex, 40.dp)
                                        Box(
                                            Modifier
                                                .align(Alignment.BottomEnd)
                                                .size(11.dp)
                                                .background(
                                                    if (peer.direct) OpenChatPalette.Online else OpenChatPalette.Signal,
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
                                                starred = fav?.mine == true,
                                            ),
                                            inlineContent = inlineIcons,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = OpenChatPalette.Ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (blocked) {
                                            Text(
                                                stringResource(R.string.peer_blocked_short),
                                                style = TabularMonoStyle,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.error,
                                                maxLines = 1,
                                            )
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            ) {
                                                val hopLabel = when {
                                                    peer.direct || peer.hops == 1 -> stringResource(R.string.hops_direct)
                                                    peer.hops != null -> hopsText(peer.hops)
                                                    else -> stringResource(R.string.dm_relayed)
                                                }
                                                val hopColor = when {
                                                    peer.direct -> OpenChatPalette.Online
                                                    peer.hops != null -> OpenChatPalette.Signal
                                                    else -> OpenChatPalette.Muted
                                                }
                                                Text(
                                                    hopLabel,
                                                    style = TabularMonoStyle,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = hopColor,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false),
                                                )
                                                Text(
                                                    "·",
                                                    style = TabularMonoStyle,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.outline,
                                                )
                                                Text(
                                                    agoText(peer.lastSeen, now, locale),
                                                    style = TabularMonoStyle,
                                                    fontSize = 11.5.sp,
                                                    color = OpenChatPalette.Muted,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }
                                    if (!blocked) {
                                        FluidGlassCircleButton(
                                            icon = Icons.Outlined.ChatBubbleOutline,
                                            contentDescription = stringResource(R.string.peer_message),
                                            onClick = { onMessage(hex) },
                                            size = 44.dp,
                                            iconSize = 18.dp,
                                            iconTint = OpenChatPalette.Lock,
                                            tintColor = OpenChatPalette.LockSoft,
                                        )
                                    }
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
