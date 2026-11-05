package dev.n2k02.localadb

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Accessible, status-based accents shared by Home, Apps and Adb. */
internal enum class DashboardTone(val accent: Color) {
    GOOD(Color(0xFF57E3AB)),
    INFO(Color(0xFF87C5FF)),
    WARN(Color(0xFFFFD186)),
    ERROR(Color(0xFFFF8E98)),
    NEUTRAL(Color(0xFFBAC7DB))
}

/** A single shared visual system; no oversized page headers or nested screens. */
@Composable
internal fun ModernPanel(
    modifier: Modifier = Modifier,
    tone: DashboardTone = DashboardTone.INFO,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(23.dp)
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(tone.accent.copy(alpha = 0.17f), surface, surface)
                )
            )
            .border(BorderStroke(1.dp, tone.accent.copy(alpha = 0.39f)), shape)
            .padding(if (compact) 10.dp else 15.dp),
    ) { content() }
}

@Composable
internal fun ModernStatusChip(
    text: String,
    tone: DashboardTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = when (tone) {
        DashboardTone.GOOD -> Icons.Rounded.CheckCircle
        DashboardTone.WARN -> Icons.Rounded.Schedule
        DashboardTone.ERROR -> Icons.Rounded.ErrorOutline
        DashboardTone.INFO -> Icons.Rounded.Info
        DashboardTone.NEUTRAL -> null
    },
) {
    val shape = RoundedCornerShape(50)
    Surface(
        modifier = modifier,
        shape = shape,
        color = tone.accent.copy(alpha = 0.15f),
        contentColor = tone.accent,
        border = BorderStroke(1.dp, tone.accent.copy(alpha = 0.50f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ModernSectionHeader(
    title: String,
    icon: ImageVector,
    tone: DashboardTone,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    status: String? = null,
    statusTone: DashboardTone = tone,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(15.dp),
            color = tone.accent.copy(alpha = 0.17f),
            modifier = Modifier.size(if (compact) 36.dp else 45.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tone.accent,
                    modifier = Modifier.size(if (compact) 20.dp else 25.dp))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        if (status != null) ModernStatusChip(status, statusTone)
    }
}

@Composable
internal fun ModernMetric(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: DashboardTone = DashboardTone.INFO,
    compact: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, tone.accent.copy(alpha = 0.30f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = if (compact) 6.dp else 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = tone.accent,
                modifier = Modifier.size(if (compact) 15.dp else 19.dp))
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Slim metrics are Home-only; Apps and ADB keep their existing sizing. */
@Composable
internal fun HomeMetric(
    label: String, value: String, icon: ImageVector, modifier: Modifier,
    tone: DashboardTone = DashboardTone.INFO,
) = ModernMetric(label, value, icon, modifier, tone, compact = true)
