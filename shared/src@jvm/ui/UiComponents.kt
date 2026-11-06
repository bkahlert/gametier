package com.bkahlert.gametier.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.nio.file.Path

@Composable
internal fun GameTierTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFCBB5F4), onPrimary = Color(0xFF332051),
        primaryContainer = Color(0xFF3B2C51), onPrimaryContainer = Color(0xFFEADDFB),
        background = Color(0xFF1B1B20), onBackground = Color(0xFFE6E3EC),
        surface = Color(0xFF222228), onSurface = Color(0xFFE6E3EC),
        surfaceVariant = Color(0xFF2C2C34), onSurfaceVariant = Color(0xFFBDBBC7),
        outline = Color(0xFF77737F), outlineVariant = Color(0xFF414049),
    ) else lightColorScheme(
        primary = Color(0xFF6946B3), onPrimary = Color.White,
        primaryContainer = Color(0xFFF1ECFA), onPrimaryContainer = Color(0xFF442773),
        background = Color(0xFFF8F9FB), onBackground = Color(0xFF24232A),
        surface = Color.White, onSurface = Color(0xFF24232A),
        surfaceVariant = Color(0xFFF2F3F6), onSurfaceVariant = Color(0xFF656775),
        outline = Color(0xFF8C8995), outlineVariant = Color(0xFFE5E5EB),
    )
    MaterialTheme(colorScheme = colors, shapes = MaterialTheme.shapes.copy(
        small = RoundedCornerShape(5.dp), medium = RoundedCornerShape(7.dp), large = RoundedCornerShape(9.dp)), content = content)
}

@Composable
internal fun PathText(path: Path, modifier: Modifier = Modifier, compact: Boolean = false) =
    SelectableText(path.toString(), modifier, compact)

@Composable
internal fun SelectableText(value: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    SelectionContainer(modifier) {
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun StatusLabel(label: String, tone: StatusTone = StatusTone.NORMAL, busy: Boolean = false) {
    val color = when (tone) {
        StatusTone.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.SUCCESS -> if (isSystemInDarkTheme()) Color(0xFF8FD2B2) else Color(0xFF247259)
        StatusTone.WARNING -> if (isSystemInDarkTheme()) Color(0xFFF1C779) else Color(0xFF916017)
        StatusTone.ACTIVE -> MaterialTheme.colorScheme.primary
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
        else Text(if (tone == StatusTone.WARNING) "⚠" else "●", color = color, style = MaterialTheme.typography.labelSmall)
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun Notice(title: String, body: String, onDetails: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⚠", color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onDetails != null) TextButton(onClick = onDetails) { Text("Details") }
        }
    }
}
