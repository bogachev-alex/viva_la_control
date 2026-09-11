package viva.la.circle.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import viva.la.circle.R
import viva.la.circle.gesture.GestureHandleConfig
import viva.la.circle.model.TargetAction
import viva.la.circle.service.InterceptorServiceState
import viva.la.circle.ui.theme.AppIcons

enum class GestureHandleSlot {
    TAP,
    LONG_PRESS,
    SWIPE_UP,
}

@Composable
fun GestureHandleCard(
    state: InterceptorServiceState,
    onEnabledChange: (Boolean) -> Unit,
    onOpacityChange: (Int) -> Unit,
    onSelectTapAction: (TargetAction, String?) -> Unit,
    onSelectLongPressAction: (TargetAction, String?) -> Unit,
    onSelectSwipeUpAction: (TargetAction, String?) -> Unit,
    onOpenAppPicker: (GestureHandleSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    var opacitySlider by remember(state.gestureHandleOpacity) {
        mutableFloatStateOf(state.gestureHandleOpacity.toFloat())
    }
    val warnMissingHome = GestureHandleConfig.missingHomeOnSwipe(state.gestureSwipeUpAction)

    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.gesture_handle_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.gestureHandleEnabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            Text(
                text = stringResource(R.string.gesture_handle_enable_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.gesture_handle_hide_system_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Medium,
            )

            if (state.gestureHandleEnabled) {
                Text(
                    text = stringResource(R.string.gesture_handle_opacity),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = opacitySlider,
                        onValueChange = { opacitySlider = it },
                        onValueChangeFinished = { onOpacityChange(opacitySlider.toInt()) },
                        valueRange = GestureHandleConfig.MIN_OPACITY_PERCENT.toFloat()..
                            GestureHandleConfig.MAX_OPACITY_PERCENT.toFloat(),
                        steps = 99,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            R.string.gesture_handle_opacity_value,
                            opacitySlider.toInt(),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                Text(
                    text = stringResource(R.string.gesture_handle_fixed_gestures),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = stringResource(R.string.gesture_handle_tap_action),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                ActionSelectorList(
                    selectedAction = state.gestureTapAction,
                    selectedSpecificPkg = state.gestureTapSpecificPackage,
                    onSelectAction = { action ->
                        onSelectTapAction(action, state.gestureTapSpecificPackage)
                    },
                    onOpenAppPicker = { onOpenAppPicker(GestureHandleSlot.TAP) },
                    availableActions = TargetAction.gestureHandleEntries(),
                )

                Text(
                    text = stringResource(R.string.gesture_handle_long_press_action),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                ActionSelectorList(
                    selectedAction = state.gestureLongPressAction,
                    selectedSpecificPkg = state.gestureLongPressSpecificPackage,
                    onSelectAction = { action ->
                        onSelectLongPressAction(action, state.gestureLongPressSpecificPackage)
                    },
                    onOpenAppPicker = { onOpenAppPicker(GestureHandleSlot.LONG_PRESS) },
                    availableActions = TargetAction.gestureHandleEntries(),
                )

                Text(
                    text = stringResource(R.string.gesture_handle_swipe_up_action),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                ActionSelectorList(
                    selectedAction = state.gestureSwipeUpAction,
                    selectedSpecificPkg = state.gestureSwipeUpSpecificPackage,
                    onSelectAction = { action ->
                        onSelectSwipeUpAction(action, state.gestureSwipeUpSpecificPackage)
                    },
                    onOpenAppPicker = { onOpenAppPicker(GestureHandleSlot.SWIPE_UP) },
                    availableActions = TargetAction.gestureHandleEntries(),
                )

                if (warnMissingHome) {
                    Text(
                        text = stringResource(R.string.gesture_handle_home_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
