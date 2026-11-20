package org.openchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.AppLanguage
import org.openchat.android.app.AppTheme

/** One button naming the current language; a menu lists the rest. Choosing one restarts the screen in that language. */
@Composable
fun LanguagePicker(onLanguage: (AppLanguage.Choice) -> Unit) {
    val current = AppLanguage.get(LocalContext.current)
    var open by remember { mutableStateOf(false) }
    val system = stringResource(R.string.lang_system)
    Box {
        FluidGlassPillButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Icon(Icons.Outlined.Translate, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                current.label ?: system,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = OpenChatPalette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = OpenChatPalette.Surface,
        ) {
            AppLanguage.Choice.entries.forEach { c ->
                DropdownMenuItem(
                    text = {
                        Text(
                            c.label ?: system,
                            fontWeight = if (c == current) FontWeight.Bold else FontWeight.Normal,
                            color = if (c == current) OpenChatPalette.Signal else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { open = false; if (c != current) onLanguage(c) },
                )
            }
        }
    }
}

@Composable
private fun ThemeCycleButton() {
    val ctx = LocalContext.current
    val current by AppTheme.choiceFlow.collectAsState()
    val next = when (current) {
        AppTheme.Choice.SYSTEM -> AppTheme.Choice.LIGHT
        AppTheme.Choice.LIGHT -> AppTheme.Choice.DARK
        AppTheme.Choice.DARK -> AppTheme.Choice.SYSTEM
    }
    val icon = when (current) {
        AppTheme.Choice.SYSTEM -> Icons.Outlined.BrightnessAuto
        AppTheme.Choice.LIGHT -> Icons.Outlined.LightMode
        AppTheme.Choice.DARK -> Icons.Outlined.DarkMode
    }
    FluidGlassCircleButton(
        icon = icon,
        contentDescription = stringResource(R.string.theme_title),
        onClick = { AppTheme.set(ctx, next) },
        size = 38.dp,
        iconSize = 18.dp,
        shadowElevation = 4.dp,
    )
}

@Composable
fun OnboardingScreen(onStart: () -> Unit, onLanguage: (AppLanguage.Choice) -> Unit) {
    Box(Modifier.fillMaxSize().openChatCanvas()) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 22.dp, vertical = 14.dp),
        ) {
            val compact = maxHeight < 680.dp
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = OpenChatPalette.Control,
                        modifier = Modifier.size(if (compact) 40.dp else 44.dp).clipToBounds(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            tint = OpenChatPalette.Signal,
                            modifier = Modifier.fillMaxSize().scale(1.35f),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemeCycleButton()
                        LanguagePicker(onLanguage)
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = if (compact) 10.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp, Alignment.CenterVertically),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SignalPulseDot(live = true)
                        Text(
                            stringResource(R.string.app_name),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpenChatPalette.Signal,
                        )
                    }
                    Text(
                        stringResource(R.string.onboard_title),
                        fontSize = if (compact) 26.sp else 32.sp,
                        lineHeight = if (compact) 32.sp else 38.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.onboard_body),
                        fontSize = if (compact) 14.5.sp else 16.sp,
                        lineHeight = if (compact) 21.sp else 24.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = CircleShape,
                        color = OpenChatPalette.ChipPassive,
                    ) {
                        Text(
                            stringResource(R.string.onboard_no_account),
                            fontSize = if (compact) 13.sp else 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (compact) 6.dp else 7.dp),
                        )
                    }
                    SafetyNote(compact = compact)
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
                ) {
                    Button(
                        onClick = onStart,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(if (compact) 48.dp else 52.dp).pressScale(0.97f),
                    ) {
                        Text(stringResource(R.string.onboard_start), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        stringResource(R.string.onboard_next),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun SafetyNote(compact: Boolean = false) {
    val raw = stringResource(R.string.safety_body)
    val points = remember(raw) {
        raw.split('\n')
            .map { it.trim().removePrefix("·").removePrefix("•").trim() }
            .filter { it.isNotEmpty() }
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = OpenChatPalette.CardSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(if (compact) 13.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp),
        ) {
            Text(stringResource(R.string.safety_title), fontWeight = FontWeight.SemiBold, fontSize = if (compact) 14.sp else 14.5.sp)
            points.forEach { point ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .padding(top = if (compact) 7.dp else 8.dp)
                            .size(5.dp)
                            .background(OpenChatPalette.Signal, CircleShape),
                    )
                    Text(
                        point,
                        fontSize = if (compact) 13.sp else 13.5.sp,
                        lineHeight = if (compact) 18.5.sp else 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
