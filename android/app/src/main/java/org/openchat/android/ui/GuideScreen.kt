package org.openchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R

const val GUIDE = "guide"

private data class GuideItem(
    val icon: ImageVector,
    val tint: Color,
    val badgeBg: Color,
    val title: String,
    val body: String,
)

private data class CommandGuideItem(
    val syntax: String,
    val description: String,
)

/**
 * Structured in-app Usage Guide ("How OpenChat Works"), presenting every core feature,
 * privacy mechanism, and slash command with clean vector icons and concise explanations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onBack: () -> Unit, onPanic: () -> Unit) {
    BackHandler(onBack = onBack)
    val horizontalSafe = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    val isLight = OpenChatPalette.isLight
    val signal = OpenChatPalette.Signal
    val signalSoft = OpenChatPalette.SignalSoft
    val lock = OpenChatPalette.Lock
    val lockSoft = OpenChatPalette.LockSoft
    val online = OpenChatPalette.Online
    val onlineSoft = online.copy(alpha = if (isLight) 0.14f else 0.20f)
    val danger = MaterialTheme.colorScheme.error
    val dangerSoft = danger.copy(alpha = if (isLight) 0.12f else 0.20f)

    Box(Modifier.fillMaxSize().openChatCanvas()) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = OpenChatPalette.Ink,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Column(Modifier.windowInsetsPadding(horizontalSafe)) {
                    TopAppBar(
                        colors = chromeTopBarColors(),
                        navigationIcon = { GlassBackButton(onBack) },
                        title = {
                            PanicTitle(stringResource(R.string.guide_title), onPanic)
                        },
                    )
                }
            },
        ) { padding ->
            val topClearance = padding.calculateTopPadding()
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(horizontalSafe)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(
                        PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp + topClearance,
                            bottom = 28.dp,
                        ),
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Hero card with OpenChat vector wordmark logo & overview
                FlatGuideCard {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isLight) Color(0xFFEDF2F8) else Color(0xFF1C2428),
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                            modifier = Modifier.size(46.dp).clipToBounds(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = null,
                                tint = signal,
                                modifier = Modifier.fillMaxSize().scale(1.42f),
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.app_name),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = OpenChatPalette.Ink,
                            )
                            Text(
                                stringResource(R.string.guide_subtitle),
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Section 1: Core Features
                GuideSectionHeader(stringResource(R.string.guide_sec_features))
                val features = listOf(
                    GuideItem(
                        icon = Icons.Outlined.Sensors,
                        tint = signal,
                        badgeBg = signalSoft,
                        title = stringResource(R.string.guide_mesh_title),
                        body = stringResource(R.string.guide_mesh_body),
                    ),
                    GuideItem(
                        icon = Icons.Outlined.Lock,
                        tint = lock,
                        badgeBg = lockSoft,
                        title = stringResource(R.string.guide_e2e_title),
                        body = stringResource(R.string.guide_e2e_body),
                    ),
                    GuideItem(
                        icon = WalkieTalkieIcon,
                        tint = signal,
                        badgeBg = signalSoft,
                        title = stringResource(R.string.guide_voice_title),
                        body = stringResource(R.string.guide_voice_body),
                    ),
                    GuideItem(
                        icon = Icons.Outlined.Public,
                        tint = signal,
                        badgeBg = signalSoft,
                        title = stringResource(R.string.guide_globe_title),
                        body = stringResource(R.string.guide_globe_body),
                    ),
                    GuideItem(
                        icon = Icons.Outlined.PushPin,
                        tint = signal,
                        badgeBg = signalSoft,
                        title = stringResource(R.string.guide_board_title),
                        body = stringResource(R.string.guide_board_body),
                    ),
                )
                FlatGuideCard {
                    Column {
                        features.forEachIndexed { idx, item ->
                            if (idx > 0) Hairline(Modifier.padding(start = 64.dp))
                            GuideRow(item)
                        }
                    }
                }

                // Section 2: Identity, Privacy & Safety
                GuideSectionHeader(stringResource(R.string.guide_sec_privacy))
                val privacyItems = listOf(
                    GuideItem(
                        icon = Icons.Outlined.PersonOutline,
                        tint = lock,
                        badgeBg = lockSoft,
                        title = stringResource(R.string.guide_id_title),
                        body = stringResource(R.string.guide_id_body),
                    ),
                    GuideItem(
                        icon = Icons.Outlined.QrCode2,
                        tint = online,
                        badgeBg = onlineSoft,
                        title = stringResource(R.string.guide_verify_title),
                        body = stringResource(R.string.guide_verify_body),
                    ),
                    GuideItem(
                        icon = Icons.Outlined.Bolt,
                        tint = danger,
                        badgeBg = dangerSoft,
                        title = stringResource(R.string.guide_panic_title),
                        body = stringResource(R.string.guide_panic_body),
                    ),
                )
                FlatGuideCard {
                    Column {
                        privacyItems.forEachIndexed { idx, item ->
                            if (idx > 0) Hairline(Modifier.padding(start = 64.dp))
                            GuideRow(item)
                        }
                    }
                }

                // Section 3: Slash Commands
                GuideSectionHeader(stringResource(R.string.guide_sec_commands))
                val commands = listOf(
                    CommandGuideItem("/who", stringResource(R.string.guide_cmd_who)),
                    CommandGuideItem("/msg @name", stringResource(R.string.guide_cmd_msg)),
                    CommandGuideItem("/ping · /trace", stringResource(R.string.guide_cmd_diag)),
                    CommandGuideItem("/fav · /block", stringResource(R.string.guide_cmd_social)),
                    CommandGuideItem("/group · /drop · /clear", stringResource(R.string.guide_cmd_room)),
                )
                FlatGuideCard {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isLight) Color(0xFFEDF2F8) else Color(0xFF1C2428),
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Outlined.Terminal,
                                        contentDescription = null,
                                        tint = OpenChatPalette.Ink,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Text(
                                stringResource(R.string.guide_cmd_intro),
                                fontSize = 13.sp,
                                lineHeight = 18.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        commands.forEach { cmd ->
                            Hairline(Modifier.padding(horizontal = 16.dp))
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    cmd.syntax,
                                    style = TabularMonoStyle,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = signal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    cmd.description,
                                    fontSize = 13.sp,
                                    lineHeight = 18.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun GuideSectionHeader(title: String) {
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 0.dp),
    )
}

@Composable
private fun FlatGuideCard(content: @Composable () -> Unit) {
    val isLight = OpenChatPalette.isLight
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isLight) Color.White else Color(0xFF13181B),
        contentColor = OpenChatPalette.Ink,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun GuideRow(item: GuideItem) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = item.badgeBg,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.padding(top = 1.dp).size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = item.tint,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpenChatPalette.Ink,
            )
            Text(
                item.body,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
