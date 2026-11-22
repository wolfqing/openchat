package org.openchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
    var activeSegment by rememberSaveable { mutableIntStateOf(0) } // 0 = area channels, 1 = 24h notes
    var scaleIdx by rememberSaveable { mutableIntStateOf(1) } // 0 = neighborhood, 1 = city, 2 = province, 3 = region
    val here = geo.locationGeohash

    val scaleLevels = remember {
        listOf(
            Geohash.Level.NEIGHBORHOOD,
            Geohash.Level.CITY,
            Geohash.Level.PROVINCE,
            Geohash.Level.REGION,
        )
    }
    val selectedLevel = scaleLevels[scaleIdx.coerceIn(0, scaleLevels.lastIndex)]
    val heroCell = here?.take(selectedLevel.precision) ?: defaultPreset.geohash
    val heroSpan = approxSpanText(GlobeView.cellWidthMeters(heroCell))
    val heroPreset = LocationPresets.presetFor(heroCell) ?: if (here == null) defaultPreset else null
    val heroOnline = geo.participants[heroCell] ?: 0

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
                .padding(start = 16.dp, end = 16.dp, top = 8.dp + topClearance, bottom = 18.dp + maxOf(bottomClearance, imeBottom)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 0. 2-Segment Toggle: Area Channels | 24h Place Notes
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
                        0 to stringResource(R.string.loc_tab_channels),
                        1 to stringResource(R.string.loc_tab_notes, geo.notes.size),
                    ).forEach { (idx, label) ->
                        val active = activeSegment == idx
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (active) OpenChatPalette.Surface else Color.Transparent)
                                .clickable(role = Role.Tab) { activeSegment = idx }
                                .padding(vertical = 7.dp, horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontSize = 12.5.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (active) OpenChatPalette.Ink else OpenChatPalette.Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (geo.tor == org.openchat.android.app.TorState.STARTING && geo.torStalled) {
                TorStalledBanner(onConnectDirect = { ChatRepository.controller?.setTor(false) })
            }

            if (activeSegment == 1) {
                // 24h Place Notes Tab
                if (here != null) {
                    NotesSection(here)
                } else {
                    SettingsGroupCard {
                        Column(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Outlined.MyLocation,
                                contentDescription = null,
                                tint = OpenChatPalette.Signal,
                                modifier = Modifier.size(28.dp),
                            )
                            Text(
                                stringResource(R.string.geo_use_location),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OpenChatPalette.Ink,
                            )
                            Text(
                                stringResource(R.string.notes_hint),
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp,
                                color = OpenChatPalette.Muted,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Button(
                                onClick = onRequestLocation,
                                shape = CircleShape,
                            ) {
                                Text(stringResource(R.string.geo_use_location), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                return@Column
            }

            // 1. Dark Atmospheric Globe Viewport Hero Card
            AtmosphericGlobeHeroCard(
                cell = heroCell,
                regionLabel = heroPreset?.let { stringResource(it.labelRes) } ?: stringResource(levelLabel(selectedLevel)),
                spanText = heroSpan,
                onlineCount = heroOnline,
                hasGps = here != null,
                onEnterChannel = { openCell(heroCell) },
                onOpenGlobe = { globe = true },
            )

            // 2. 4-Step Coverage Scale Rail (approximate, privacy-preserving)
            SettingsGroupCard {
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.loc_scale_title),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpenChatPalette.Ink,
                        )
                        Text(
                            heroSpan,
                            style = TabularMonoStyle,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpenChatPalette.Muted,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = OpenChatPalette.Well,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            scaleLevels.forEachIndexed { idx, lvl ->
                                val active = scaleIdx == idx
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (active) OpenChatPalette.Surface else Color.Transparent)
                                        .clickable(role = Role.RadioButton) {
                                            scaleIdx = idx
                                            if (here == null) onRequestLocation()
                                        }
                                        .padding(vertical = 7.dp, horizontal = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        stringResource(levelLabel(lvl)),
                                        fontSize = 12.sp,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (active) OpenChatPalette.Signal else OpenChatPalette.Muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = onRequestLocation)
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Icon(
                            if (here == null) Icons.Outlined.MyLocation else Icons.Outlined.Refresh,
                            contentDescription = null,
                            tint = OpenChatPalette.Signal,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            stringResource(if (here == null) R.string.geo_use_location_hint else R.string.geo_refresh),
                            fontSize = 12.sp,
                            color = OpenChatPalette.Muted,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = OpenChatPalette.Muted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // 3. Bookmarked & Joined Quick-Switch Pills
            val quickPills = (geo.bookmarks + geo.joined.sorted()).distinct()
            if (quickPills.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    quickPills.forEach { g ->
                        val count = unread[Conversation.Geo(g).key] ?: 0
                        val online = geo.participants[g] ?: 0
                        val isBookmarked = g in geo.bookmarks
                        val preset = LocationPresets.presetFor(g)
                        Surface(
                            shape = CircleShape,
                            color = OpenChatPalette.CardSurface,
                            border = BorderStroke(
                                0.5.dp,
                                if (count > 0) OpenChatPalette.Signal else OpenChatPalette.Line,
                            ),
                            modifier = Modifier
                                .pressScale(0.96f)
                                .clip(CircleShape)
                                .clickable(role = Role.Button) { openCell(g) },
                        ) {
                            Row(
                                Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                if (isBookmarked) {
                                    Icon(
                                        Icons.Filled.Star,
                                        contentDescription = null,
                                        tint = OpenChatPalette.Signal,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                                Text(
                                    "#$g",
                                    style = TabularMonoStyle,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.5.sp,
                                    color = OpenChatPalette.Ink,
                                )
                                if (preset != null) {
                                    Text(
                                        stringResource(preset.labelRes),
                                        fontSize = 12.sp,
                                        color = OpenChatPalette.Muted,
                                        maxLines = 1,
                                    )
                                }
                                if (online > 0) {
                                    Text("· $online", style = TabularMonoStyle, fontSize = 11.5.sp, color = OpenChatPalette.Signal)
                                }
                                UnreadBadge(count)
                            }
                        }
                    }
                }
            }

            // 4. Quick Join Popular Regional Channels (2-column grid)
            val presets = if (showMorePresets) LocationPresets.ALL else LocationPresets.FEATURED
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.geo_quick_join),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = OpenChatPalette.Muted,
                    )
                    Text(
                        stringResource(if (showMorePresets) R.string.geo_quick_less else R.string.geo_quick_more),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpenChatPalette.Signal,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { showMorePresets = !showMorePresets }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
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

            // 5. Visit Another Area Card (manual #geohash teleport)
            SettingsGroupCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.geo_elsewhere), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = manual,
                            onValueChange = { manual = LocationPresets.sanitizeInput(it) },
                            placeholder = { Text(stringResource(R.string.geo_hint), fontSize = 13.5.sp) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        Button(
                            enabled = Geohash.isValid(manual) && manual.length >= 2,
                            shape = RoundedCornerShape(12.dp),
                            onClick = { openCell(manual) },
                        ) { Text(stringResource(R.string.geo_enter), fontWeight = FontWeight.SemiBold) }
                    }
                    Text(
                        withInlineVectorBadges(stringResource(R.string.geo_teleport_note)),
                        inlineContent = rememberLabelInlineIcons(),
                        fontSize = 11.5.sp,
                        lineHeight = 16.5.sp,
                        color = OpenChatPalette.Muted,
                    )
                }
            }
        }
    }
}

/** Dark atmospheric instrument hero card displaying the active geohash cell & wireframe globe picker. */
@Composable
private fun AtmosphericGlobeHeroCard(
    cell: String,
    regionLabel: String,
    spanText: String,
    onlineCount: Int,
    hasGps: Boolean,
    onEnterChannel: () -> Unit,
    onOpenGlobe: () -> Unit,
) {
    val signal = OpenChatPalette.Signal
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF1A1D26), Color(0xFF101218)),
                ),
            )
            .clickable(role = Role.Button, onClick = onOpenGlobe)
            .padding(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(if (hasGps) R.string.loc_hero_active_cell else R.string.geo_default_badge),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF969FAF),
                )
                Text(
                    "#$cell",
                    style = TabularMonoStyle,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = signal,
                    maxLines = 1,
                )
                Text(
                    "$regionLabel · $spanText",
                    fontSize = 12.5.sp,
                    color = Color(0xFFDCE1EB),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(6.dp).background(signal, CircleShape))
                    Text(
                        if (onlineCount > 0) plural(R.plurals.geo_online, onlineCount)
                        else stringResource(R.string.loc_hero_globe_hint),
                        fontSize = 11.5.sp,
                        color = Color(0xFFB5BECE),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Procedural atmospheric wireframe globe preview (tap opens 3D GlobePickerDialog)
                Canvas(
                    Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onOpenGlobe),
                ) {
                    val c = center
                    val r = size.minDimension / 2f - 2.dp.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            0.00f to signal.copy(alpha = 0.28f),
                            0.62f to Color(0xFF212634),
                            1.00f to Color(0xFF0B0D12),
                            center = Offset(c.x - r * 0.22f, c.y - r * 0.22f),
                            radius = r * 1.3f,
                        ),
                        radius = r,
                        center = c,
                    )
                    drawCircle(signal.copy(alpha = 0.45f), r, c, style = Stroke(1.dp.toPx()))
                    // Latitude & longitude ellipses
                    drawOval(
                        color = Color.White.copy(alpha = 0.14f),
                        topLeft = Offset(c.x - r * 0.46f, c.y - r),
                        size = Size(r * 0.92f, r * 2f),
                        style = Stroke(0.8.dp.toPx()),
                    )
                    drawOval(
                        color = Color.White.copy(alpha = 0.14f),
                        topLeft = Offset(c.x - r, c.y - r * 0.36f),
                        size = Size(r * 2f, r * 0.72f),
                        style = Stroke(0.8.dp.toPx()),
                    )
                    // Target cell reticle
                    val boxW = r * 0.48f
                    drawRoundRect(
                        color = signal,
                        topLeft = Offset(c.x - boxW / 2f, c.y - boxW / 2f),
                        size = Size(boxW, boxW),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                        style = Stroke(1.2.dp.toPx()),
                    )
                    drawCircle(signal, 2.5.dp.toPx(), c)
                }

                Surface(
                    shape = CircleShape,
                    color = signal,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .pressScale(0.95f)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onEnterChannel),
                ) {
                    Text(
                        stringResource(R.string.loc_enter_channel),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
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
    val ctx = LocalContext.current
    val geo by ChatRepository.geo.collectAsState()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = OpenChatPalette.CardSurface,
        border = BorderStroke(0.5.dp, OpenChatPalette.Line),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            if (geo.torDetail.isNotEmpty()) {
                Text(
                    geo.torDetail,
                    style = TabularMonoStyle,
                    fontSize = 11.sp,
                    color = OpenChatPalette.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = OpenChatPalette.Well,
                    modifier = Modifier
                        .pressScale(0.95f)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { ChatRepository.controller?.retryTor() },
                ) {
                    Text(
                        stringResource(R.string.retry),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpenChatPalette.Ink,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = OpenChatPalette.Well,
                    modifier = Modifier
                        .pressScale(0.95f)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { org.openchat.android.app.DiagLog.shareReport(ctx) },
                ) {
                    Text(
                        stringResource(R.string.diag_share_btn),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpenChatPalette.Ink,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = OpenChatPalette.PrimaryAction,
                    modifier = Modifier
                        .pressScale(0.95f)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onConnectDirect),
                ) {
                    Text(
                        stringResource(R.string.tor_use_direct),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpenChatPalette.OnPrimaryAction,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
