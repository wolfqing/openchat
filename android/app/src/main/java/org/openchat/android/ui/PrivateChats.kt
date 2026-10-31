package org.openchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.onClick
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.Conversation
import org.openchat.android.app.PeerLabel
import org.openchat.android.app.Social

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
                    PanicTitle(labelText(label, verified = social.isVerifiedPeer(peerHex), vouched = social.isVouchedPeer(peerHex)), onPanic)
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
                            Text("\u2605\uFE0E " + stringResource(R.string.group_creator), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
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
internal fun PrivateList(onOpen: (String) -> Unit) {
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
                            append(labelText(PeerLabel.mesh(peer?.nickname ?: names[hex], hex), verified = social.isVerifiedPeer(hex), vouched = social.isVouchedPeer(hex)))
                            if (fav?.mine == true) withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(" \u2605\uFE0E") }
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
