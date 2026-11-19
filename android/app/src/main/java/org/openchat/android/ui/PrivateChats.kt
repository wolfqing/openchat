package org.openchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.ChatMessage
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.Conversation
import org.openchat.android.app.DeliveryState
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
    val convKey = Conversation.Private(peerHex).key
    val readSent = remember { HashSet<String>() }
    var sheet by remember { mutableStateOf(false) }
    val social by Social.state.collectAsState()
    val pttOpenSet by ChatRepository.pttOpen.collectAsState()
    val pttOpen = convKey in pttOpenSet
    val liveSpeakers by ChatRepository.liveSpeakers.collectAsState()
    val dmSpeaker = remember(liveSpeakers, convKey) {
        liveSpeakers.values.firstOrNull { it.conversation == convKey }
            ?.let { PeerLabel.mesh(it.senderName, it.senderKey).toString() }
    }

    BackHandler(onBack = onBack)
    if (sheet) PersonSheet(PersonRef.Mesh(peerHex, peer?.nickname ?: names[peerHex]), onDismiss = { sheet = false }, onMessage = {})

    FluidGlassHost {
        BoxWithConstraints(Modifier.fillMaxSize().openChatCanvas()) {
            val compactBar = maxWidth < 400.dp
            val ultraNarrowBar = maxWidth < 340.dp
            val pttAccent = if (dmSpeaker != null) OpenChatPalette.Signal else OpenChatPalette.Lock
            val showTopBand = peer != null || dmSpeaker != null
            val mesh by ChatRepository.mesh.collectAsState()
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = OpenChatPalette.Ink,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    Column(
                        Modifier.windowInsetsPadding(
                            WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
                        ),
                    ) {
                        TopAppBar(
                            colors = chromeTopBarColors(),
                            navigationIcon = { GlassBackButton(onBack) },
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable { sheet = true }) {
                                        EmblemView(peerHex, 32.dp)
                                    }
                                    Column(Modifier.weight(1f, fill = false)) {
                                        PanicTitle(labelText(label, verified = social.isVerifiedPeer(peerHex), vouched = social.isVouchedPeer(peerHex)), onPanic)
                                        val hops = peer?.hops
                                        Text(
                                            when {
                                                social.isBlockedPeer(peerHex) -> stringResource(R.string.peer_blocked_short)
                                                peer != null && (peer.direct || hops == 1) -> stringResource(R.string.dm_status_direct)
                                                peer != null && hops != null -> plural(R.plurals.dm_status_relayed, hops - 1)
                                                peer != null -> stringResource(R.string.dm_status_here)
                                                social.favoriteOf(peerHex)?.let { it.mine && it.theyFavorited && it.theirNpub != null } == true -> stringResource(R.string.dm_status_internet)
                                                else -> stringResource(R.string.dm_status_away)
                                            },
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            actions = {
                                AudiencePill(encrypted = true, compact = compactBar)
                                if (ultraNarrowBar) {
                                    FluidGlassCircleButton(
                                        icon = WalkieTalkieIcon,
                                        contentDescription = stringResource(R.string.ptt_button),
                                        onClick = { ChatRepository.togglePttOpen(convKey) },
                                        modifier = Modifier.padding(start = 6.dp),
                                        size = 32.dp,
                                        iconSize = 15.dp,
                                        iconTint = if (pttOpen) Color.White else pttAccent,
                                        tintColor = when {
                                            pttOpen -> pttAccent
                                            dmSpeaker != null -> OpenChatPalette.SignalSoft
                                            else -> null
                                        },
                                        borderAccent = if (pttOpen || dmSpeaker != null) pttAccent else null,
                                    )
                                } else {
                                    FluidGlassPillButton(
                                        onClick = { ChatRepository.togglePttOpen(convKey) },
                                        modifier = Modifier.padding(start = 6.dp),
                                        tintColor = when {
                                            pttOpen -> pttAccent
                                            dmSpeaker != null -> OpenChatPalette.SignalSoft
                                            else -> null
                                        },
                                        borderAccent = if (pttOpen || dmSpeaker != null) pttAccent else null,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                                    ) {
                                        Icon(
                                            imageVector = WalkieTalkieIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = if (pttOpen) Color.White else pttAccent,
                                        )
                                        Text(
                                            stringResource(R.string.ptt_button),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (pttOpen) Color.White else OpenChatPalette.Ink,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                IconButton(onClick = { sheet = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.peer_more)) }
                            },
                        )
                        if (showTopBand) {
                            RouteBand(
                                name = name,
                                hops = if (peer?.direct == true) 1 else peer?.hops,
                                direct = peers.count { it.direct && it.peerIdHex != mesh.myPeerHex },
                                lastHeard = peer?.lastSeen ?: System.currentTimeMillis(),
                                liveSpeakerLabel = dmSpeaker,
                                onSpeakerClick = { ChatRepository.setPttOpen(convKey, true) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(
                    Modifier
                        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
                        .fillMaxSize(),
                ) {
                    ChatPane(
                        conversation = convKey,
                        emptyText = stringResource(R.string.dm_empty, name),
                        placeholder = stringResource(R.string.dm_placeholder, name),
                        onSend = { ChatRepository.controller?.sendPrivate(peerHex, it) },
                        onVisible = { list ->
                            val unreadIds = list.filter { !it.mine && readSent.add(it.id) }.map { it.id }
                            if (unreadIds.isNotEmpty()) ChatRepository.controller?.markRead(peerHex, unreadIds)
                        },
                        topClearance = padding.calculateTopPadding(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun NewGroupDialog(onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OpenChatPalette.Surface,
        title = { Text(stringResource(R.string.group_new), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(org.openchat.android.core.Group.MAX_NAME) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text(stringResource(R.string.group_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.group_new_note), fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = { ChatRepository.controller?.createGroup(name)?.let(onCreated) },
            ) { Text(stringResource(R.string.group_create)) }
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
    var person by remember { mutableStateOf<PersonRef?>(null) }
    val mesh by ChatRepository.mesh.collectAsState()
    BackHandler(onBack = onBack)
    if (g == null) { LaunchedEffect(Unit) { onBack() }; return }
    if (members) GroupMembersSheet(g, onDismiss = { members = false }, onLeave = { members = false; ChatRepository.controller?.groupLeave(idHex); onBack() })
    person?.let { p -> PersonSheet(p, onDismiss = { person = null }, onMessage = { key -> ChatRepository.requestOpen(Conversation.Private(key).key) }) }
    FluidGlassHost {
        BoxWithConstraints(Modifier.fillMaxSize().openChatCanvas()) {
            val compactBar = maxWidth < 390.dp
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = OpenChatPalette.Ink,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        colors = chromeTopBarColors(),
                        navigationIcon = { GlassBackButton(onBack) },
                        title = {
                            Column {
                                PanicTitle("#" + g.name, onPanic)
                                Text(
                                    stringResource(R.string.group_status, plural(R.plurals.group_members, g.members.size)),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            AudiencePill(encrypted = true, compact = compactBar, modifier = Modifier.padding(end = 6.dp))
                            FluidGlassPillButton(
                                onClick = { members = true },
                                modifier = Modifier.padding(end = 10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    stringResource(R.string.group_members_title),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OpenChatPalette.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                Box(
                    Modifier
                        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
                        .fillMaxSize(),
                ) {
                    ChatPane(
                        conversation = Conversation.Group(idHex).key,
                        emptyText = stringResource(R.string.group_empty),
                        placeholder = stringResource(R.string.group_placeholder, g.name),
                        onSend = { ChatRepository.controller?.sendGroup(idHex, it) },
                        onSenderClick = { m -> if (m.senderKey != mesh.myPeerHex) person = PersonRef.Mesh(m.senderKey, m.senderName) },
                        topClearance = padding.calculateTopPadding(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupMembersSheet(g: org.openchat.android.app.GroupInfo, onDismiss: () -> Unit, onLeave: () -> Unit) {
    val peers by ChatRepository.peers.collectAsState()
    val memberIds = g.members.map { it.fingerprint.take(16) }.toSet()
    val invitable = peers.filter { it.peerIdHex !in memberIds }
    val inlineIcons = rememberLabelInlineIcons()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OpenChatPalette.Surface,
        contentColor = OpenChatPalette.Ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GroupAvatar(size = 38)
                Column(Modifier.weight(1f)) {
                    Text("#" + g.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.group_status, plural(R.plurals.group_members, g.members.size)),
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val isLight = OpenChatPalette.isLight
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isLight) Color(0xFFF1F5FA) else Color(0xFF171E21),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        g.members.forEachIndexed { idx, m ->
                            if (idx > 0) Hairline(Modifier.padding(start = 52.dp))
                            val hex = m.fingerprint.take(16)
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                EmblemView(hex, 28.dp)
                                Text(
                                    buildAnnotatedString {
                                        append(labelText(PeerLabel.mesh(m.nickname, hex)))
                                        if (m.fingerprint == g.myFingerprint) {
                                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                                append(" · " + stringResource(R.string.group_you))
                                            }
                                        }
                                    },
                                    Modifier.weight(1f),
                                    inlineContent = inlineIcons,
                                    fontSize = 14.sp,
                                )
                                if (m.fingerprint == g.creatorFingerprint) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Filled.Star, contentDescription = null, tint = OpenChatPalette.Signal, modifier = Modifier.size(13.dp))
                                        Text(stringResource(R.string.group_creator), fontSize = 12.sp, color = OpenChatPalette.Signal, fontWeight = FontWeight.Medium)
                                    }
                                } else if (g.iAmCreator) {
                                    TextButton(onClick = { ChatRepository.controller?.groupRemove(g.idHex, m.fingerprint) }) {
                                        Text(stringResource(R.string.group_remove), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }

                if (g.iAmCreator) {
                    Text(stringResource(R.string.group_invite_title), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (invitable.isEmpty()) {
                        Text(stringResource(R.string.group_none_nearby), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isLight) Color(0xFFF1F5FA) else Color(0xFF171E21),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                invitable.forEachIndexed { idx, p ->
                                    if (idx > 0) Hairline(Modifier.padding(start = 52.dp))
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        EmblemView(p.peerIdHex, 28.dp)
                                        Text(labelText(PeerLabel.mesh(p.nickname, p.peerIdHex)), Modifier.weight(1f), inlineContent = inlineIcons, fontSize = 14.sp)
                                        FilledTonalButton(
                                            enabled = g.members.size < org.openchat.android.core.Group.MAX_MEMBERS,
                                            onClick = { ChatRepository.controller?.groupInvite(g.idHex, p.peerIdHex) },
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                        ) {
                                            Text(stringResource(R.string.group_invite), fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(stringResource(R.string.group_creator_manages), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TonalActionButton(
                    onClick = onLeave,
                    destructive = true,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                ) { Text(stringResource(R.string.group_leave), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                TonalActionButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(stringResource(R.string.close), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Private chat with someone met in a location channel, over Nostr under my persona for that cell. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeoPrivateChatScreen(c: Conversation.GeoPrivate, onBack: () -> Unit, onPanic: () -> Unit) {
    val names by ChatRepository.names.collectAsState()
    val label = PeerLabel.nostr(names[c.pubkey], c.pubkey)
    val readSent = remember { HashSet<String>() }
    var sheet by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LaunchedEffect(c) { ChatRepository.controller?.joinGeo(c.geohash, teleported = ChatRepository.geo.value.locationGeohash?.startsWith(c.geohash) != true) }
    if (sheet) PersonSheet(PersonRef.Geo(c.pubkey, names[c.pubkey], c.geohash), onDismiss = { sheet = false }, onMessage = {})
    FluidGlassHost {
        BoxWithConstraints(Modifier.fillMaxSize().openChatCanvas()) {
            val compactBar = maxWidth < 380.dp
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = OpenChatPalette.Ink,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        colors = chromeTopBarColors(),
                        navigationIcon = { GlassBackButton(onBack) },
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable { sheet = true }) {
                                    EmblemView(c.pubkey, 32.dp)
                                }
                                Column(Modifier.weight(1f, fill = false)) {
                                    PanicTitle(labelText(label), onPanic)
                                    Text(
                                        stringResource(R.string.geo_dm_status, c.geohash),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        actions = {
                            AudiencePill(encrypted = true, compact = compactBar)
                            IconButton(onClick = { sheet = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.peer_more)) }
                        },
                    )
                },
            ) { padding ->
                Box(
                    Modifier
                        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
                        .fillMaxSize(),
                ) {
                    ChatPane(
                        conversation = c.key,
                        emptyText = stringResource(R.string.geo_dm_empty, label.toString(), c.geohash),
                        placeholder = stringResource(R.string.dm_placeholder, label.toString()),
                        onSend = { ChatRepository.controller?.sendGeoPrivate(c.geohash, c.pubkey, it) },
                        onVisible = { list ->
                            val unreadIds = list.filter { !it.mine && !it.system && readSent.add(it.id) }.map { it.id }
                            if (unreadIds.isNotEmpty()) ChatRepository.controller?.markRead(c.key, unreadIds)
                        },
                        topClearance = padding.calculateTopPadding(),
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupAvatar(size: Int = 42) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = OpenChatPalette.LockSoft,
        modifier = Modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Group, contentDescription = null, tint = OpenChatPalette.Lock, modifier = Modifier.size((size * 0.5f).dp))
        }
    }
}

@Composable
private fun MessagePreviewText(last: ChatMessage?, fallback: String) {
    val text = when {
        last == null -> fallback
        last.text.isNotBlank() -> last.text
        last.attachment != null && last.audio -> stringResource(R.string.media_voice)
        last.attachment != null -> stringResource(R.string.media_photo)
        else -> fallback
    }
    val failed = last?.state == DeliveryState.FAILED && last.mine
    val tone = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (last != null && last.mine) {
            val icon = when (last.state) {
                DeliveryState.FAILED -> Icons.Outlined.ErrorOutline
                DeliveryState.READ, DeliveryState.DELIVERED -> Icons.Filled.Check
                else -> Icons.Outlined.Schedule
            }
            Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(13.dp))
        }
        Text(
            text,
            fontSize = 13.5.sp,
            color = tone,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun PrivateList(onOpen: (String) -> Unit, onNewGroup: () -> Unit = {}, topClearance: Dp = 0.dp) {
    val peers by ChatRepository.peers.collectAsState()
    val all by ChatRepository.messages.collectAsState()
    val names by ChatRepository.names.collectAsState()
    val unread by ChatRepository.unread.collectAsState()
    val me by ChatRepository.mesh.collectAsState()
    val social by Social.state.collectAsState()
    val locale = appLocale()
    val inlineIcons = rememberLabelInlineIcons()
    val bottomClearance = LocalBottomBarClearance.current

    val withHistory = all.keys.filter { it.startsWith("dm:") }.map { it.removePrefix("dm:") }
    val ids = remember(peers, withHistory, unread, all, me.myPeerHex) {
        (peers.map { it.peerIdHex } + withHistory)
            .distinct()
            .filter { it != me.myPeerHex }
            .sortedWith(
                compareByDescending<String> { (unread["dm:$it"] ?: 0) > 0 }
                    .thenByDescending { all["dm:$it"]?.lastOrNull()?.timestamp ?: 0L }
                    .thenByDescending { hex -> peers.any { it.peerIdHex == hex } },
            )
    }
    val geoChats = remember(all, unread) {
        all.keys.filter { it.startsWith("gdm:") }
            .sortedWith(
                compareByDescending<String> { (unread[it] ?: 0) > 0 }
                    .thenByDescending { all[it]?.lastOrNull()?.timestamp ?: 0L },
            )
    }
    val groups by ChatRepository.groups.collectAsState()
    val groupList = remember(groups, all, unread) {
        groups.values.sortedWith(
            compareByDescending<org.openchat.android.app.GroupInfo> { (unread["grp:${it.idHex}"] ?: 0) > 0 }
                .thenByDescending { all["grp:${it.idHex}"]?.lastOrNull()?.timestamp ?: 0L },
        )
    }

    if (ids.isEmpty() && geoChats.isEmpty() && groupList.isEmpty()) {
        FluidGlassSource {
            Column(
                Modifier
                    .fillMaxSize()
                    .openChatCanvas()
                    .fluidGlassSource()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 28.dp, end = 28.dp, top = 16.dp + topClearance, bottom = 16.dp + bottomClearance),
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = CircleShape,
                    color = OpenChatPalette.LockSoft,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = OpenChatPalette.Lock, modifier = Modifier.size(24.dp))
                    }
                }
                Text(
                    stringResource(R.string.dm_list_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.5.sp,
                    lineHeight = 22.sp,
                )
                FluidGlassPillButton(
                    onClick = onNewGroup,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = OpenChatPalette.Signal)
                    Text(stringResource(R.string.group_new), fontWeight = FontWeight.SemiBold, color = OpenChatPalette.Ink)
                }
            }
        }
        return
    }

    FluidGlassSource {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .openChatCanvas()
                .fluidGlassSource(),
            contentPadding = PaddingValues(top = 4.dp + topClearance, bottom = bottomClearance + 14.dp),
        ) {
            items(groupList, key = { "grp:" + it.idHex }) { g ->
                val key = Conversation.Group(g.idHex).key
                val last = all[key]?.lastOrNull { !it.system }
                val n = unread[key] ?: 0
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(key) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GroupAvatar(size = 42)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("#" + g.name, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            last?.let {
                                Text(stampText(it.timestamp, locale), fontSize = 11.5.sp, style = TabularMonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                MessagePreviewText(last, plural(R.plurals.group_members, g.members.size))
                            }
                            UnreadBadge(n)
                        }
                    }
                }
                Hairline(Modifier.padding(start = 70.dp))
            }

            items(geoChats, key = { it }) { key ->
                val c = Conversation.GeoPrivate.parse(key)!!
                val last = all[key]?.lastOrNull { !it.system }
                val n = unread[key] ?: 0
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(key) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EmblemView(c.pubkey, 42.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                buildAnnotatedString {
                                    append(labelText(PeerLabel.nostr(names[c.pubkey], c.pubkey)))
                                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Normal, color = OpenChatPalette.Lock)) {
                                        append("  #" + c.geohash)
                                    }
                                },
                                inlineContent = inlineIcons,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            last?.let {
                                Text(stampText(it.timestamp, locale), fontSize = 11.5.sp, style = TabularMonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                MessagePreviewText(last, stringResource(R.string.geo_dm_status, c.geohash))
                            }
                            UnreadBadge(n)
                        }
                    }
                }
                Hairline(Modifier.padding(start = 70.dp))
            }

            items(ids, key = { it }) { hex ->
                val peer = peers.firstOrNull { it.peerIdHex == hex }
                val last = all["dm:$hex"]?.lastOrNull { !it.system }
                val n = unread["dm:$hex"] ?: 0
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(hex) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(44.dp)) {
                        EmblemView(hex, 42.dp)
                        if (peer != null) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(12.dp)
                                    .background(
                                        if (peer.direct) OpenChatPalette.Online else OpenChatPalette.Signal,
                                        CircleShape,
                                    )
                                    .border(2.dp, OpenChatPalette.Screen, CircleShape),
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        val fav = social.favoriteOf(hex)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                labelText(
                                    PeerLabel.mesh(peer?.nickname ?: names[hex], hex),
                                    verified = social.isVerifiedPeer(hex),
                                    vouched = social.isVouchedPeer(hex),
                                    starred = fav?.mine == true,
                                ),
                                inlineContent = inlineIcons,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.5.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            last?.let {
                                Text(stampText(it.timestamp, locale), fontSize = 11.5.sp, style = TabularMonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                val fallback = stringResource(
                                    when {
                                        peer?.direct == true -> R.string.dm_direct
                                        peer != null -> R.string.dm_relayed
                                        else -> R.string.dm_away
                                    },
                                )
                                MessagePreviewText(last, fallback)
                            }
                            UnreadBadge(n)
                        }
                    }
                }
                Hairline(Modifier.padding(start = 72.dp))
            }
        }
    }
}
