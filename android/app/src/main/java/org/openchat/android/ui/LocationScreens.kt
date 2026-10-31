package org.openchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.Conversation
import org.openchat.android.app.PeerLabel
import org.openchat.android.nostr.Geohash

@Composable
internal fun LocationPane(onRequestLocation: () -> Unit, onOpen: (String) -> Unit) {
    val geo by ChatRepository.geo.collectAsState()
    var manual by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.geo_intro), lineHeight = 22.sp)
        Spacer(Modifier.height(16.dp))
        val here = geo.locationGeohash
        if (here == null) {
            FilledTonalButton(onClick = onRequestLocation) { Text(stringResource(R.string.geo_use_location)) }
        } else {
            Geohash.Level.entries.forEach { level ->
                val cell = here.take(level.precision)
                val n = geo.participants[cell] ?: 0
                OutlinedButton(
                    onClick = { ChatRepository.controller?.joinGeo(cell, teleported = false); onOpen(cell) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text("${stringResource(levelLabel(level))} · #$cell" + if (n > 0) " · " + plural(R.plurals.geo_online, n) else "", Modifier.weight(1f))
                }
            }
            TextButton(onClick = onRequestLocation) { Text(stringResource(R.string.geo_refresh)) }
            NotesSection(here)
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.geo_elsewhere), fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(manual, { manual = it.lowercase().filter { c -> c.isLetterOrDigit() }.take(8) }, placeholder = { Text(stringResource(R.string.geo_hint)) }, modifier = Modifier.weight(1f), singleLine = true)
            Spacer(Modifier.size(8.dp))
            Button(enabled = Geohash.isValid(manual) && manual.length >= 2, onClick = {
                ChatRepository.controller?.joinGeo(manual, teleported = true); onOpen(manual)
            }) { Text(stringResource(R.string.geo_enter)) }
        }
        Text(stringResource(R.string.geo_teleport_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (geo.joined.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.geo_joined), fontWeight = FontWeight.SemiBold)
            geo.joined.sorted().forEach { g -> TextButton(onClick = { onOpen(g) }) { Text("#$g") } }
        }
    }
}

/**
 * Plain-text invite to a location channel (§21.5): readable without the app, and a
 * `bitchat://geohash/` link both apps open. A fine-grained cell asks first, since
 * whoever carries the text learns which small area you care about.
 */
@Composable
private fun ShareChannelButton(geohash: String) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    fun share() {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, ctx.getString(R.string.share_channel_text, geohash))
        ctx.startActivity(android.content.Intent.createChooser(send, null))
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.share_channel_warn_title)) },
            text = { Text(stringResource(R.string.share_channel_warn_body, geohash)) },
            confirmButton = { TextButton(onClick = { confirm = false; share() }) { Text(stringResource(R.string.share_anyway)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    TextButton(onClick = { if (geohash.length >= 6) confirm = true else share() }) { Text(stringResource(R.string.share_channel)) }
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
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.notes_title), fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.notes_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(draft, { if (it.length <= 500) draft = it }, placeholder = { Text(stringResource(R.string.notes_placeholder)) }, modifier = Modifier.weight(1f), maxLines = 3)
        IconButton(enabled = draft.isNotBlank(), onClick = { if (ChatRepository.controller?.dropNote(draft.trim()) == true) draft = "" }) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send), tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (geo.notes.isEmpty()) {
        Text(stringResource(R.string.notes_empty), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
    }
    geo.notes.take(50).forEach { n ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                buildAnnotatedString {
                    append(labelText(PeerLabel.nostr(n.nickname, n.pubkey)))
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) { append("  " + stampText(n.createdAt)) }
                },
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(withMentions(n.text), fontSize = 15.sp)
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
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = {
                Column {
                    PanicTitle("#$geohash", onPanic)
                    val n = geo.participants[geohash] ?: 0
                    Text(if (geo.relaysConnected == 0) stringResource(R.string.geo_connecting) else plural(R.plurals.geo_recent, n), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                ShareChannelButton(geohash)
                TextButton(onClick = { ChatRepository.controller?.leaveGeo(geohash); onBack() }) { Text(stringResource(R.string.geo_leave)) }
            },
        )
    }) { padding ->
        Box(Modifier.padding(padding)) {
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
