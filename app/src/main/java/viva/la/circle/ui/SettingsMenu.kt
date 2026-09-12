package viva.la.circle.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import viva.la.circle.R
import viva.la.circle.model.TargetAction
import viva.la.circle.service.InterceptorServiceState
import viva.la.circle.ui.theme.AppIcons

/**
 * Top-level sections of the app. The home screen lists them as a menu; each opens its own
 * screen so a single setting (e.g. the Shutter TargetAction) is one tap away instead of a
 * long scroll through every card.
 */
enum class SettingsSection(@param:StringRes val titleRes: Int) {
    BLUELM(R.string.target_action_settings),
    CAMERA(R.string.camera_trigger_action),
    GESTURE_HANDLE(R.string.gesture_handle_title),
    VOLUME_KEYS(R.string.volume_keys_section),
    ASSISTANTS(R.string.detected_voice_assistants),
    DIAGNOSTICS(R.string.diagnostics),
    HELP(R.string.setup_how_it_works),
    ;

    val icon: ImageVector
        get() = when (this) {
            BLUELM -> AppIcons.Tune
            CAMERA -> AppIcons.PhotoCamera
            GESTURE_HANDLE -> AppIcons.TouchApp
            VOLUME_KEYS -> AppIcons.VolumeOff
            ASSISTANTS -> AppIcons.SmartToy
            DIAGNOSTICS -> AppIcons.Analytics
            HELP -> AppIcons.HelpOutline
        }
}

@Composable
fun SettingsMenuCard(
    state: InterceptorServiceState,
    onOpenSection: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SettingsSection.entries.forEachIndexed { index, section ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 68.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
                SettingsMenuRow(
                    section = section,
                    subtitle = sectionSubtitle(section, state),
                    onClick = { onOpenSection(section) },
                )
            }
        }
    }
}

@Composable
private fun SettingsMenuRow(
    section: SettingsSection,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = section.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(section.titleRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Icon(
            imageVector = AppIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One-line summary of the section's current value, so the menu doubles as an overview. */
@Composable
private fun sectionSubtitle(section: SettingsSection, state: InterceptorServiceState): String? {
    val context = LocalContext.current

    @Composable
    fun actionLabel(action: TargetAction?, pkg: String?): String = when {
        action == null -> stringResource(R.string.menu_not_set)
        action == TargetAction.SPECIFIC_APP && pkg != null -> appLabelFor(context, pkg)
        else -> stringResource(action.titleRes)
    }
    val on = stringResource(R.string.menu_on)
    val off = stringResource(R.string.menu_off)
    val skipLabel = stringResource(R.string.menu_volume_skip)
    val remapLabel = stringResource(R.string.menu_volume_remap)
    return when (section) {
        SettingsSection.BLUELM -> actionLabel(state.blueLMAction, state.blueLMSpecificPackage)
        SettingsSection.CAMERA -> actionLabel(state.cameraAction, state.cameraSpecificPackage)
        SettingsSection.GESTURE_HANDLE -> if (state.gestureHandleEnabled) on else off
        SettingsSection.VOLUME_KEYS -> {
            val parts = listOfNotNull(
                skipLabel.takeIf { state.volumeSkipTracksEnabled },
                remapLabel.takeIf { state.volumeShortRemapEnabled },
            )
            if (parts.isEmpty()) off else parts.joinToString(" · ")
        }
        SettingsSection.ASSISTANTS -> stringResource(R.string.menu_assistants_hint)
        SettingsSection.DIAGNOSTICS ->
            stringResource(R.string.menu_diagnostics_hint, state.interceptedCount)
        SettingsSection.HELP -> stringResource(R.string.menu_help_hint)
    }
}
