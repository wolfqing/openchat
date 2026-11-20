package org.openchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.Conversation
import org.openchat.android.app.LocationPresets
import org.openchat.android.app.PeerLabel
import org.openchat.android.nostr.Geohash
import org.openchat.android.ui.globe.GlobePickerDialog
import org.openchat.android.ui.globe.GlobeView
import java.util.TimeZone
import kotlin.math.roundToInt

@Composable
internal fun LocationPane(onRequestLocation: () -> Unit, onOpen: (String) -> Unit, topClearance: Dp = 0.dp) {
    val geo by ChatRepository.geo.collectAsState()
    val unread by ChatRepository.unread.collectAsState()
    val bottomClearance = LocalBottomBarClearance.current
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val locale = appLocale()
    val defaultPreset = remember(locale) { LocationPresets.defaultPreset(locale, TimeZone.getDefault().id) }
    var manual by rememberSaveable { mutableStateOf("") }
    var globe by rememberSaveable { mutableStateOf(false) }
    var showMorePresets by rememberSaveable { mutableStateOf(false) }
    val here = geo.locationGeohash

    fun openCell(cell: String) {
        ChatRepository.controller?.joinGeo(cell, teleported = here == null || !here.startsWith(cell))
        onOpen(cell)
    }

    if (globe) {
        val start = manual.takeIf { Geohash.isValid(it) && it.length >= 2 }
            ?: geo.joined.sorted().firstOrNull()
            ?: here?.take(2)
            ?: defaultPreset.geohash
        GlobePickerDialog(start, onDismiss = { globe = false }, onSelect = { cell ->
            globe = false
            openCell(cell)
        })
    }

    FluidGlassSource {
        Column(
            Modifier
                .fillMaxSize()
                .openChatCanvas()
                .fluidGlassSource()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 10.dp + topClearance, bottom = 18.dp + maxOf(bottomClearance, imeBottom)),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.geo_intro),
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (geo.tor == org.openchat.android.app.TorState.STARTING && geo.torStalled) {
                TorStalledBanner(onConnectDirect = { ChatRepository.controller?.setTor(false) })
            }

            // 0. Bookmarked Channels (persisted even after leaving a channel)
            if (geo.bookmarks.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.geo_bookmarks),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        geo.bookmarks.forEach { g ->
                            val count = unread[Conversation.Geo(g).key] ?: 0
                            val online = geo.participants[g] ?: 0
                            val preset = LocationPresets.presetFor(g)
                            FluidGlassPillButton(
                                onClick = { openCell(g) },
                                borderAccent = if (count > 0) OpenChatPalette.Signal else null,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = OpenChatPalette.Signal,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    "#$g",
                                    style = TabularMonoStyle,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.5.sp,
                                    color = OpenChatPalette.Ink,
                                )
                                if (preset != null) {
                                    Text(
                                        stringResource(preset.labelRes),
                                        fontSize = 12.5.sp,
                                        color = OpenChatPalette.Muted,
                                        maxLines = 1,
                                    )
                                }
                                if (online > 0) {
                                    Text("· $online", style = TabularMonoStyle, fontSize = 12.sp, color = OpenChatPalette.Online)
                                }
                                UnreadBadge(count)
                            }
                        }
                    }
                }
            }

            // 1. Joined Channels Quick-Switch Chips (kept at the top for 1-tap switching)
            if (geo.joined.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.geo_joined),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        geo.joined.sorted().forEach { g ->
                            val count = unread[Conversation.Geo(g).key] ?: 0
                            val online = geo.participants[g] ?: 0
                            val preset = LocationPresets.presetFor(g)
                            FluidGlassPillButton(
                                onClick = { onOpen(g) },
                                borderAccent = if (count > 0) OpenChatPalette.Signal else null,
                                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
                            ) {
                                Text(
                                    "#$g",
                                    style = TabularMonoStyle,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.5.sp,
                                    color = OpenChatPalette.Ink,
                                )
                                if (preset != null) {
                                    Text(
                                        stringResource(preset.labelRes),
                                        fontSize = 12.5.sp,
                                        color = OpenChatPalette.Muted,
                                        maxLines = 1,
                                    )
                                }
                                if (online > 0) {
                                    Text("· $online", style = TabularMonoStyle, fontSize = 12.sp, color = OpenChatPalette.Online)
                                }
                                UnreadBadge(count)
                            }
                        }
                    }
                }
            }

            // 2. Default Channel (no GPS needed) or Approximate Location Levels
            if (here == null) {
                val defaultCell = defaultPreset.geohash
                val defaultOnline = geo.participants[defaultCell] ?: 0
                val defaultSpan = approxSpanText(GlobeView.cellWidthMeters(defaultCell))
                SettingsGroupCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { openCell(defaultCell) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Text(
                                    stringResource(defaultPreset.labelRes),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = OpenChatPalette.SignalSoft,
                                ) {
                                    Text(
                                        stringResource(R.string.geo_default_badge),
                                        fontSize = 10.5.sp,
                                        lineHeight = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = OpenChatPalette.Signal,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                "#$defaultCell · ${stringResource(R.string.geo_level_region)} · $defaultSpan",
                                style = TabularMonoStyle,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (defaultOnline > 0) {
                            OnlineCountPill(defaultOnline)
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Hairline(Modifier.padding(start = 16.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onRequestLocation)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.MyLocation,
                            contentDescription = null,
                            tint = OpenChatPalette.Signal,
                            modifier = Modifier.size(19.dp),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(stringResource(R.string.geo_use_location), fontWeight = FontWeight.Medium, fontSize = 14.5.sp)
                            Text(
                                stringResource(R.string.geo_use_location_hint),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            } else {
                // Broadest (Region) first so the liveliest room is immediately at the top.
                val levels = remember { Geohash.Level.entries.reversed() }
                SettingsGroupCard {
                    levels.forEachIndexed { idx, level ->
                        if (idx > 0) Hairline(Modifier.padding(start = 16.dp))
                        val cell = here.take(level.precision)
                        val n = geo.participants[cell] ?: 0
                        val span = approxSpanText(GlobeView.cellWidthMeters(cell))
                        val preset = if (level == Geohash.Level.REGION) LocationPresets.presetFor(cell) else null
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { ChatRepository.controller?.joinGeo(cell, teleported = false); onOpen(cell) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    if (preset != null) "${stringResource(levelLabel(level))} · ${stringResource(preset.labelRes)}" else stringResource(levelLabel(level)),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text("#$cell · $span", style = TabularMonoStyle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (n > 0) {
                                OnlineCountPill(n)
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

            // 3. Quick Join Popular Regional Channels (2-column grid, 1 tap to join & enter)
            val presets = if (showMorePresets) LocationPresets.ALL else LocationPresets.FEATURED
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.geo_quick_join),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { showMorePresets = !showMorePresets },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            stringResource(if (showMorePresets) R.string.geo_quick_less else R.string.geo_quick_more),
                            fontSize = 12.5.sp,
                            color = OpenChatPalette.Signal,
                        )
                    }
                }
                presets.chunked(2).forEach { pair ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        pair.forEach { preset ->
                            val isJoined = preset.geohash in geo.joined
                            val isBookmarked = preset.geohash in geo.bookmarks
                            val online = geo.participants[preset.geohash] ?: 0
                            QuickJoinTile(
                                preset = preset,
                                joined = isJoined,
                                bookmarked = isBookmarked,
                                online = online,
                                onClick = { openCell(preset.geohash) },
                                onLongClick = { ChatRepository.controller?.toggleGeoBookmark(preset.geohash) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            // 4. 3D Globe Picker Hero Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = OpenChatPalette.CardSurface,
                modifier = Modifier.fillMaxWidth().pressScale(0.98f).clip(RoundedCornerShape(16.dp)).clickable { globe = true },
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = OpenChatPalette.SignalSoft,
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

            // 5. Visit Another Area Card (manual #geohash)
            SettingsGroupCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.geo_elsewhere), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = manual,
                            onValueChange = { manual = LocationPresets.sanitizeInput(it) },
                            placeholder = { Text(stringResource(R.string.geo_hint)) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        Button(
                            enabled = Geohash.isValid(manual) && manual.length >= 2,
                            shape = RoundedCornerShape(12.dp),
                            onClick = { openCell(manual) },
                        ) { Text(stringResource(R.string.geo_enter)) }
                    }
                    Text(
                        withInlineVectorBadges(stringResource(R.string.geo_teleport_note)),
                        inlineContent = rememberLabelInlineIcons(),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 6. Local 24h Notes Card (only when location is active)
            if (here != null) {
                NotesSection(here)
            }
        }
    }
}

@Composable
private fun OnlineCountPill(count: Int) {
    Surface(
        shape = CircleShape,
        color = OpenChatPalette.Online.copy(alpha = 0.14f),
    ) {
        Text(
            plural(R.plurals.geo_online, count),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = OpenChatPalette.Online,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickJoinTile(
    preset: LocationPresets.GeoPreset,
    joined: Boolean,
    bookmarked: Boolean,
    online: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = OpenChatPalette.CardSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
            .pressScale(0.97f)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(
            Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "#${preset.geohash}",
                    style = TabularMonoStyle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.5.sp,
                    color = if (joined) OpenChatPalette.Signal else OpenChatPalette.Ink,
                    maxLines = 1,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (bookmarked) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = OpenChatPalette.Signal,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    when {
                        online > 0 -> Text(
                            "· $online",
                            style = TabularMonoStyle,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpenChatPalette.Online,
                        )
                        joined -> Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = OpenChatPalette.Signal,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Text(
                stringResource(preset.labelRes),
                fontSize = 12.5.sp,
                color = OpenChatPalette.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    val inlineIcons = rememberLabelInlineIcons()
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
                                inlineContent = inlineIcons,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(stampText(n.createdAt, locale), fontSize = 11.sp, style = TabularMonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val geo by ChatRepository.geo.collectAsState()
    var person by remember { mutableStateOf<PersonRef?>(null) }
    var confirmShare by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    person?.let { PersonSheet(it, onDismiss = { person = null }, onMessage = { key -> ChatRepository.requestOpen(key) }) }
    fun shareChannel() {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, resources.getString(R.string.share_channel_text, geohash))
        ctx.startActivity(android.content.Intent.createChooser(send, null))
    }
    if (confirmShare) {
        AlertDialog(
            onDismissRequest = { confirmShare = false },
            containerColor = OpenChatPalette.Surface,
            title = { Text(stringResource(R.string.share_channel_warn_title)) },
            text = { Text(stringResource(R.string.share_channel_warn_body, geohash)) },
            confirmButton = { TextButton(onClick = { confirmShare = false; shareChannel() }) { Text(stringResource(R.string.share_anyway)) } },
            dismissButton = { TextButton(onClick = { confirmShare = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    FluidGlassHost {
        Box(Modifier.fillMaxSize().openChatCanvas()) {
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = OpenChatPalette.Ink,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    ProgressiveBlurTopBar {
                        Column {
                            TopAppBar(
                                colors = chromeTopBarColors(),
                                navigationIcon = { GlassBackButton(onBack) },
                                title = {
                                    val preset = remember(geohash) { LocationPresets.presetFor(geohash) }
                                    val titleText = if (preset != null) "#$geohash · ${stringResource(preset.labelRes)}" else "#$geohash"
                                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        PanicTitle(titleText, onPanic)
                                        val n = geo.participants[geohash] ?: 0
                                        val channelOnline = geohash in geo.connectedChannels || (geo.relaysConnected > 0 && geohash !in geo.joined)
                                        val torStalled = !channelOnline && geo.tor == org.openchat.android.app.TorState.STARTING && geo.torStalled
                                        val statusLine = when {
                                            channelOnline && geohash.length >= 6 && n == 0 -> stringResource(R.string.geo_count_hidden)
                                            channelOnline -> plural(R.plurals.geo_recent, n)
                                            torStalled -> stringResource(R.string.tor_stalled_title)
                                            geo.tor == org.openchat.android.app.TorState.STARTING && geo.torProgress > 0 ->
                                                stringResource(R.string.tor_starting_pct, geo.torProgress)
                                            geo.tor == org.openchat.android.app.TorState.STARTING -> stringResource(R.string.tor_starting)
                                            else -> stringResource(R.string.geo_connecting)
                                        }
                                        Text(
                                            statusLine,
                                            fontSize = 11.5.sp,
                                            lineHeight = 15.sp,
                                            color = if (torStalled) OpenChatPalette.Signal else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                actions = {
                                    val isBookmarked = geohash in geo.bookmarks
                                    GlassHeaderActionCluster(
                                        modifier = Modifier.padding(end = 12.dp),
                                        items = listOf(
                                            GlassActionItem(
                                                icon = if (isBookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                                contentDescription = stringResource(
                                                    if (isBookmarked) R.string.geo_bookmark_remove else R.string.geo_bookmark_add,
                                                ),
                                                onClick = { ChatRepository.controller?.toggleGeoBookmark(geohash) },
                                                iconTint = if (isBookmarked) OpenChatPalette.Signal else null,
                                            ),
                                            GlassActionItem(
                                                icon = Icons.Outlined.Share,
                                                contentDescription = stringResource(R.string.share_channel),
                                                onClick = { if (geohash.length >= 6) confirmShare = true else shareChannel() },
                                            ),
                                            GlassActionItem(
                                                icon = Icons.AutoMirrored.Outlined.Logout,
                                                contentDescription = stringResource(R.string.geo_leave),
                                                onClick = { ChatRepository.controller?.leaveGeo(geohash); onBack() },
                                            ),
                                        ),
                                    )
                                },
                            )
                            val channelOnline = geohash in geo.connectedChannels || (geo.relaysConnected > 0 && geohash !in geo.joined)
                            if (!channelOnline && geo.tor == org.openchat.android.app.TorState.STARTING && geo.torStalled) {
                                Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                                    TorStalledBanner(onConnectDirect = { ChatRepository.controller?.setTor(false) })
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                Box(
                    Modifier
                        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
                        .fillMaxSize(),
                ) {
                    val roomEmptyText = if (geohash.length >= 6) {
                        stringResource(R.string.geo_empty, geohash) + "\n\n" + stringResource(R.string.geo_opsec_fine, geohash)
                    } else {
                        stringResource(R.string.geo_empty, geohash)
                    }
                    ChatPane(
                        conversation = Conversation.Geo(geohash).key,
                        emptyText = roomEmptyText,
                        placeholder = stringResource(R.string.geo_placeholder, geohash),
                        onSend = { ChatRepository.controller?.sendGeo(geohash, it) },
                        onSenderClick = { m -> person = PersonRef.Geo(m.senderKey, m.senderName, geohash) },
                        topClearance = padding.calculateTopPadding(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TorStalledBanner(onConnectDirect: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = OpenChatPalette.CardSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.tor_stalled_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp,
                    color = OpenChatPalette.Signal,
                )
                Text(
                    stringResource(R.string.tor_stalled_body),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onConnectDirect,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    stringResource(R.string.tor_use_direct),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}
