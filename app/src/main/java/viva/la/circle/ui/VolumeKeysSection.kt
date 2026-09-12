package viva.la.circle.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import viva.la.circle.R
import viva.la.circle.media.MediaPlaybackGate
import viva.la.circle.model.TargetAction
import viva.la.circle.model.VolumeShortAction
import viva.la.circle.remap.VolumeKeyPolicy
import viva.la.circle.service.InterceptorServiceState
import viva.la.circle.ui.theme.AppIcons

@Composable
fun VolumeKeysCard(
    state: InterceptorServiceState,
    onSkipTracksChange: (Boolean) -> Unit,
    onShortRemapChange: (Boolean) -> Unit,
    onLongPressMsChange: (Long) -> Unit,
    onHapticChange: (Boolean) -> Unit,
    onVolumeUpShortChange: (VolumeShortAction) -> Unit,
    onVolumeDownShortChange: (VolumeShortAction) -> Unit,
    onOpenAppPickerForVolumeUp: () -> Unit,
    onOpenAppPickerForVolumeDown: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var nlsEnabled by remember {
        mutableStateOf(MediaPlaybackGate.isNotificationListenerEnabled(context))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                nlsEnabled = MediaPlaybackGate.isNotificationListenerEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.VolumeOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.volume_keys_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            PreferenceSwitchRow(
                title = stringResource(R.string.volume_skip_tracks),
                hint = stringResource(R.string.volume_skip_tracks_hint),
                checked = state.volumeSkipTracksEnabled,
                onCheckedChange = onSkipTracksChange,
            )

            if (state.volumeSkipTracksEnabled) {
                VolumeLongPressAdbCard(active = state.volumeLongPressListenerActive)
            }

            PreferenceSwitchRow(
                title = stringResource(R.string.volume_short_remap),
                hint = stringResource(R.string.volume_short_remap_hint),
                checked = state.volumeShortRemapEnabled,
                onCheckedChange = onShortRemapChange,
            )

            if (state.volumeSkipTracksEnabled || state.volumeShortRemapEnabled) {
                Text(
                    text = stringResource(R.string.volume_long_press_duration),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VolumeKeyPolicy.TIMEOUT_PRESETS_MS.forEach { ms ->
                        val label = when (ms) {
                            300L -> stringResource(R.string.volume_timeout_300)
                            700L -> stringResource(R.string.volume_timeout_700)
                            else -> stringResource(R.string.volume_timeout_500)
                        }
                        FilterChip(
                            selected = state.volumeLongPressMs == ms,
                            onClick = { onLongPressMsChange(ms) },
                            label = { Text(label) },
                        )
                    }
                }

                PreferenceSwitchRow(
                    title = stringResource(R.string.volume_haptic),
                    hint = stringResource(R.string.volume_haptic_hint),
                    checked = state.volumeHapticEnabled,
                    onCheckedChange = onHapticChange,
                )
            }

            if (state.volumeSkipTracksEnabled) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.volume_nls_banner_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.volume_nls_banner_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(
                                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                                    )
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.could_not_open,
                                            e.message ?: "?",
                                        ),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(
                                    if (nlsEnabled) {
                                        R.string.volume_nls_enabled
                                    } else {
                                        R.string.volume_nls_enable
                                    },
                                ),
                            )
                        }
                    }
                }
            }

            if (state.volumeShortRemapEnabled) {
                VolumeShortActionPicker(
                    title = stringResource(R.string.volume_up_short),
                    selected = state.volumeUpShortAction,
                    onSelect = onVolumeUpShortChange,
                    onOpenAppPicker = onOpenAppPickerForVolumeUp,
                )
                VolumeShortActionPicker(
                    title = stringResource(R.string.volume_down_short),
                    selected = state.volumeDownShortAction,
                    onSelect = onVolumeDownShortChange,
                    onOpenAppPicker = onOpenAppPickerForVolumeDown,
                )
            }
        }
    }
}

@Composable
private fun PreferenceSwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun VolumeShortActionPicker(
    title: String,
    selected: VolumeShortAction,
    onSelect: (VolumeShortAction) -> Unit,
    onOpenAppPicker: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )

        VolumeShortOptionRow(
            selected = selected is VolumeShortAction.Volume,
            title = stringResource(R.string.volume_short_volume),
            description = stringResource(R.string.volume_short_volume_description),
            icon = AppIcons.VolumeOff,
            onClick = { onSelect(VolumeShortAction.Volume) },
        )

        VolumeShortAction.ALLOWED_REMAPS.forEach { action ->
            val isSelected = selected is VolumeShortAction.Remap && selected.action == action
            val pkg = (selected as? VolumeShortAction.Remap)?.specificPackage
            VolumeShortOptionRow(
                selected = isSelected,
                title = stringResource(action.titleRes),
                description = stringResource(action.descriptionRes),
                icon = getActionIcon(action),
                onClick = {
                    onSelect(
                        VolumeShortAction.Remap(
                            action = action,
                            specificPackage = if (action == TargetAction.SPECIFIC_APP) pkg else null,
                        ),
                    )
                },
            )
            if (isSelected && action == TargetAction.SPECIFIC_APP) {
                val label = pkg?.let { appLabelFor(context, it) }
                Text(
                    text = if (label != null) {
                        stringResource(R.string.app_selected, label)
                    } else {
                        stringResource(R.string.no_app_selected)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenAppPicker) {
                    Text(
                        text = stringResource(
                            if (pkg == null) R.string.select_app else R.string.change_app,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeShortOptionRow(
    selected: Boolean,
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(20.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VolumeLongPressAdbCard(active: Boolean) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val command =
        "adb shell pm grant ${context.packageName} " +
            "android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER"

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (active) {
                Text(
                    text = stringResource(R.string.volume_syslongpress_active),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                return@Column
            }
            Text(
                text = stringResource(R.string.volume_syslongpress_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.volume_syslongpress_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(command))
                    Toast.makeText(
                        context,
                        context.getString(R.string.volume_syslongpress_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            ) {
                Text(text = stringResource(R.string.volume_syslongpress_copy))
            }
        }
    }
}
