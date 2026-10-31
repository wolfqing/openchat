package org.openchat.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
        OutlinedButton(onClick = { open = true }) {
            Text(current.label ?: system)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppLanguage.Choice.entries.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.label ?: system, fontWeight = if (c == current) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; if (c != current) onLanguage(c) },
                )
            }
        }
    }
}

@Composable
fun OnboardingScreen(onStart: () -> Unit, onLanguage: (AppLanguage.Choice) -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) { LanguagePicker(onLanguage) }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.onboard_title), fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.onboard_body),
                fontSize = 17.sp, lineHeight = 26.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.onboard_no_account), fontSize = 17.sp, lineHeight = 26.sp)
            Spacer(Modifier.height(24.dp))
            SafetyNote()
            Spacer(Modifier.height(32.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(stringResource(R.string.onboard_start), fontSize = 18.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.onboard_next), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SafetyNote() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.safety_title), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.safety_body),
                fontSize = 14.sp, lineHeight = 21.sp,
            )
        }
    }
}
