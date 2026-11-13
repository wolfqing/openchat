package org.openchat.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.AppLanguage

/** One button naming the current language; a menu lists the rest. Choosing one restarts the screen in that language. */
@Composable
fun LanguagePicker(onLanguage: (AppLanguage.Choice) -> Unit) {
    val current = AppLanguage.get(LocalContext.current)
    var open by remember { mutableStateOf(false) }
    val system = stringResource(R.string.lang_system)
    Box {
        OutlinedButton(
            onClick = { open = true },
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Outlined.Translate, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(6.dp))
            Text(current.label ?: system, fontSize = 13.sp)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = OpenChatPalette.Card,
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
fun OnboardingScreen(onStart: () -> Unit, onLanguage: (AppLanguage.Choice) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = OpenChatPalette.Screen) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = OpenChatPalette.Card,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.size(44.dp).clipToBounds(),
                ) {
                    Image(
                        painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().scale(1.35f),
                    )
                }
                LanguagePicker(onLanguage)
            }

            Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                    fontSize = 32.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.onboard_body),
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = CircleShape,
                    color = OpenChatPalette.Surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        stringResource(R.string.onboard_no_account),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                SafetyNote()
            }

            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onStart,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(stringResource(R.string.onboard_start), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    stringResource(R.string.onboard_next),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun SafetyNote() {
    val raw = stringResource(R.string.safety_body)
    val points = remember(raw) {
        raw.split('\n')
            .map { it.trim().removePrefix("·").removePrefix("•").trim() }
            .filter { it.isNotEmpty() }
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = OpenChatPalette.Surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.safety_title), fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
            points.forEach { point ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .size(5.dp)
                            .background(OpenChatPalette.Signal, CircleShape),
                    )
                    Text(
                        point,
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
