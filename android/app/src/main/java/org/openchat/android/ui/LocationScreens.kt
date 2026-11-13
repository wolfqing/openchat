package org.openchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.Conversation
import org.openchat.android.app.PeerLabel
import org.openchat.android.nostr.Geohash
import org.openchat.android.ui.globe.GlobePickerDialog
import org.openchat.android.ui.globe.GlobeView
import kotlin.math.roundToInt

@Composable
internal fun LocationPane(onRequestLocation: () -> Unit, onOpen: (String) -> Unit) {
    val geo by ChatRepository.geo.collectAsState()
    val unread by ChatRepository.unread.collectAsState()
    var manual by rememberSaveable { mutableStateOf("") }
    var globe by rememberSaveable { mutableStateOf(false) }
    val here = geo.locationGeohash

    if (globe) {
        val start = manual.takeIf { Geohash.isValid(it) && it.length >= 2 } ?: geo.joined.sorted().firstOrNull() ?: here?.take(2)
        GlobePickerDialog(start, onDismiss = { globe = false }, onSelect = { cell ->
            globe = false
            ChatRepository.controller?.joinGeo(cell, teleported = here == null || !here.startsWith(cell))
            onOpen(cell)
        })
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.geo_intro),
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 1. 3D Globe Picker Hero Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = OpenChatPalette.Card,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { globe = true },
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = OpenChatPalette.SignalSoft,
                    border = BorderStroke(1.dp, OpenChatPalette.Signal.copy(alpha = 0.5f)),
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Public, contentDescription = null, tint = OpenChatPalette.Signal, modifier = Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.globe_open), fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp)
                    Text(stringResource(R.string.globe_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 2. Joined Channels Quick-Switch Chips (kept near the top so active channels are one tap away)
        if (geo.joined.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.geo_joined), fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    geo.joined.sorted().forEach { g ->
                        val count = unread[Conversation.Geo(g).key] ?: 0
                        val online = geo.participants[g] ?: 0
                        Surface(
                            shape = CircleShape,
                            color = OpenChatPalette.Card,
                            border = BorderStroke(1.dp, if (count > 0) OpenChatPalette.Signal else MaterialTheme.colorScheme.outline),
                            modifier = Modifier.clip(CircleShape).clickable { onOpen(g) },
                        ) {
                            Row(
                                Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("#$g", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = OpenChatPalette.Ink)
                                if (online > 0) {
                                    Text("· $online", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = OpenChatPalette.Online)
                                }
                                UnreadBadge(count)
                            }
                        }
                    }
                }
            }
        }

        // 3. Approximate Location Levels Card
        if (here == null) {
            SettingsGroupCard {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onRequestLocation).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.geo_use_location), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            SettingsGroupCard {
                Geohash.Level.entries.forEachIndexed { idx, level ->
                    if (idx > 0) Hairline(Modifier.padding(start = 16.dp))
                    val cell = here.take(level.precision)
                    val n = geo.participants[cell] ?: 0
                    val span = approxSpanText(GlobeView.cellWidthMeters(cell))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { ChatRepository.controller?.joinGeo(cell, teleported = false); onOpen(cell) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(levelLabel(level)), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("#$cell · $span", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (n > 0) {
                            Surface(
                                shape = CircleShape,
                                color = OpenChatPalette.Online.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, OpenChatPalette.Online.copy(alpha = 0.4f)),
                            ) {
                                Text(
                                    plural(R.plurals.geo_online, n),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = OpenChatPalette.Online,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                )
                            }
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
                Hairline()
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onRequestLocation).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.geo_refresh), fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 4. Visit Another Area Card
        SettingsGroupCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.geo_elsewhere), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it.lowercase().filter { c -> c.isLetterOrDigit() }.take(8) },
                        placeholder = { Text(stringResource(R.string.geo_hint)) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Button(
                        enabled = Geohash.isValid(manual) && manual.length >= 2,
                        onClick = { ChatRepository.controller?.joinGeo(manual, teleported = true); onOpen(manual) },
                    ) { Text(stringResource(R.string.geo_enter)) }
                }
                Text(stringResource(R.string.geo_teleport_note), fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 5. Local 24h Notes Card (only when location is active)
        if (here != null) {
            NotesSection(here)
        }
    }
}

private fun approxSpanText(meters: Double): String =
    if (meters >= 1000) "~${(meters / 1000).roundToInt()} km" else "~${(meters / 10).roundToInt() * 10} m"

/**
 * Plain-text invite to a location channel (§21.5): readable without the app, and a
 * `bitchat://geohash/` link both apps open.
 */
@Composable
private fun ShareChannelButton(geohash: String) {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    var confirm by remember { mutableStateOf(false) }
    fun share() {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, resources.getString(R.string.share_channel_text, geohash))
        ctx.startActivity(android.content.Intent.createChooser(send, null))
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = OpenChatPalette.Surface,
            title = { Text(stringResource(R.string.share_channel_warn_title)) },
            text = { Text(stringResource(R.string.share_channel_warn_body, geohash)) },
            confirmButton = { TextButton(onClick = { confirm = false; share() }) { Text(stringResource(R.string.share_anyway)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    IconButton(onClick = { if (geohash.length >= 6) confirm = true else share() }) {
        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.share_channel))
    }
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
    SettingsGroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.notes_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(stringResource(R.string.notes_hint), fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 500) draft = it },
                    placeholder = { Text(stringResource(R.string.notes_placeholder)) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                )
                FilledTonalButton(
                    enabled = draft.isNotBlank(),
                    onClick = { if (ChatRepository.controller?.dropNote(draft.trim()) == true) draft = "" },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send), modifier = Modifier.size(18.dp))
                }
            }
            if (geo.notes.isEmpty()) {
                Text(stringResource(R.string.notes_empty), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val locale = appLocale()
                geo.notes.take(20).forEachIndexed { idx, n ->
                    if (idx > 0) Hairline()
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EmblemView(n.pubkey, 22.dp)
                            Text(
                                buildAnnotatedString {
                                    append(labelText(PeerLabel.nostr(n.nickname, n.pubkey)))
                                },
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(stampText(n.createdAt, locale), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(withMentions(n.text), fontSize = 14.5.sp, lineHeight = 21.sp)
                    }
                }
            }
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
    Scaffold(
        containerColor = OpenChatPalette.Screen,
        topBar = {
            Column {
                TopAppBar(
                    colors = chromeTopBarColors(),
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                    title = {
                        Column {
                            PanicTitle("#$geohash", onPanic)
                            val n = geo.participants[geohash] ?: 0
                            Text(
                                if (geo.relaysConnected == 0) stringResource(R.string.geo_connecting) else plural(R.plurals.geo_recent, n),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        ShareChannelButton(geohash)
                        TextButton(onClick = { ChatRepository.controller?.leaveGeo(geohash); onBack() }) {
                            Text(stringResource(R.string.geo_leave), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                )
                Hairline()
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
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
