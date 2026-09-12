package viva.la.circle.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
    SWIPE_LEFT,
    SWIPE_RIGHT,
}

enum class GestureHapticSlot {
    TAP,
    LONG_PRESS,
    SWIPE_UP,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    BACK,
    RECENTS,
}

@Composable
fun GestureHandleCard(
    state: InterceptorServiceState,
    onEnabledChange: (Boolean) -> Unit,
    onOpacityChange: (Int) -> Unit,
    onColorChange: (Int) -> Unit,
    onHapticChange: (GestureHapticSlot, Boolean) -> Unit,
    onHideInFullscreenChange: (Boolean) -> Unit,
    onWidthChange: (Int) -> Unit,
    onHeightChange: (Int) -> Unit,
    onBottomOffsetChange: (Int) -> Unit,
    onSelectTapAction: (TargetAction, String?) -> Unit,
    onSelectLongPressAction: (TargetAction, String?) -> Unit,
    onSelectSwipeUpAction: (TargetAction, String?) -> Unit,
    onSelectSwipeLeftAction: (TargetAction, String?) -> Unit,
    onSelectSwipeRightAction: (TargetAction, String?) -> Unit,
    onOpenAppPicker: (GestureHandleSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    var opacitySlider by remember(state.gestureHandleOpacity) {
        mutableFloatStateOf(state.gestureHandleOpacity.toFloat())
    }
    var widthSlider by remember(state.gesturePillWidthDp) {
        mutableFloatStateOf(state.gesturePillWidthDp.toFloat())
    }
    var heightSlider by remember(state.gesturePillHeightDp) {
        mutableFloatStateOf(state.gesturePillHeightDp.toFloat())
    }
    var offsetSlider by remember(state.gestureBottomOffsetDp) {
        mutableFloatStateOf(state.gestureBottomOffsetDp.toFloat())
    }
    val warnMissingHome = GestureHandleConfig.missingHomeOnSwipe(state.gestureSwipeUpAction)
    val selectedColor = GestureHandleConfig.normalizeColorArgb(state.gesturePillColorArgb)

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
                    text = stringResource(R.string.gesture_handle_haptic),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.gesture_handle_haptic_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GestureHapticRow(
                    title = stringResource(R.string.gesture_handle_haptic_tap),
                    checked = state.gestureHapticTap,
                    onCheckedChange = { onHapticChange(GestureHapticSlot.TAP, it) },
                )
                GestureHapticRow(
                    title = stringResource(R.string.gesture_handle_haptic_long_press),
                    checked = state.gestureHapticLongPress,
                    onCheckedChange = { onHapticChange(GestureHapticSlot.LONG_PRESS, it) },
                )
                GestureHapticRow(
                    title = stringResource(R.string.gesture_handle_haptic_swipe_up),
                    checked = state.gestureHapticSwipeUp,
                    onCheckedChange = { onHapticChange(GestureHapticSlot.SWIPE_UP, it) },
                )
                GestureHapticRow(
                    title = stringResource(R.string.gesture_handle_haptic_swipe_left),
                    checked = state.gestureHapticSwipeLeft,
                    onCheckedChange = { onHapticChange(GestureHapticSlot.SWIPE_LEFT, it) },
                )
                GestureHapticRow(
                    title = stringResource(R.string.gesture_handle_haptic_swipe_right),
                    checked = state.gestureHapticSwipeRight,
                    onCheckedChange = { onHapticChange(GestureHapticSlot.SWIPE_RIGHT, it) },
                )
                GestureHapticRow(
                    title = stringResource(R.string.gesture_handle_haptic_back),
                    checked = state.gestureHapticBack,
                    onCheckedChange = { onHapticChange(GestureHapticSlot.BACK, it) },
                )
                GestureHapticRow(
                    title = stringResource(R.string.gesture_handle_haptic_recents),
                    checked = state.gestureHapticRecents,
                    onCheckedChange = { onHapticChange(GestureHapticSlot.RECENTS, it) },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.gesture_handle_hide_fullscreen),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(
                            text = stringResource(R.string.gesture_handle_hide_fullscreen_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.gestureHandleHideInFullscreen,
                        onCheckedChange = onHideInFullscreenChange,
                    )
                }

                GestureDpSlider(
                    label = stringResource(R.string.gesture_handle_opacity),
                    valueLabel = stringResource(
                        R.string.gesture_handle_opacity_value,
                        opacitySlider.toInt(),
                    ),
                    value = opacitySlider,
                    valueRange = GestureHandleConfig.MIN_OPACITY_PERCENT.toFloat()..
                        GestureHandleConfig.MAX_OPACITY_PERCENT.toFloat(),
                    onValueChange = { v ->
                        opacitySlider = v
                        onOpacityChange(v.toInt())
                    },
                )

                Text(
                    text = stringResource(R.string.gesture_handle_color),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GestureHandleConfig.PRESET_COLORS.forEach { colorArgb ->
                        val selected =
                            (selectedColor and 0x00FFFFFF) == (colorArgb and 0x00FFFFFF)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(colorArgb))
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape,
                                )
                                .clickable(role = Role.RadioButton) {
                                    onColorChange(colorArgb)
                                },
                        )
                    }
                }

                GestureDpSlider(
                    label = stringResource(R.string.gesture_handle_width),
                    valueLabel = stringResource(
                        R.string.gesture_handle_dp_value,
                        widthSlider.toInt(),
                    ),
                    value = widthSlider,
                    valueRange = GestureHandleConfig.MIN_WIDTH_DP.toFloat()..
                        GestureHandleConfig.MAX_WIDTH_DP.toFloat(),
                    onValueChange = { v ->
                        widthSlider = v
                        onWidthChange(v.toInt())
                    },
                )

                GestureDpSlider(
                    label = stringResource(R.string.gesture_handle_height),
                    valueLabel = stringResource(
                        R.string.gesture_handle_dp_value,
                        heightSlider.toInt(),
                    ),
                    value = heightSlider,
                    valueRange = GestureHandleConfig.MIN_HEIGHT_DP.toFloat()..
                        GestureHandleConfig.MAX_HEIGHT_DP.toFloat(),
                    onValueChange = { v ->
                        heightSlider = v
                        onHeightChange(v.toInt())
                    },
                )

                GestureDpSlider(
                    label = stringResource(R.string.gesture_handle_bottom_offset),
                    valueLabel = stringResource(
                        R.string.gesture_handle_dp_value,
                        offsetSlider.toInt(),
                    ),
                    value = offsetSlider,
                    valueRange = GestureHandleConfig.MIN_BOTTOM_OFFSET_DP.toFloat()..
                        GestureHandleConfig.MAX_BOTTOM_OFFSET_DP.toFloat(),
                    onValueChange = { v ->
                        offsetSlider = v
                        onBottomOffsetChange(v.toInt())
                    },
                )

                Text(
                    text = stringResource(R.string.gesture_handle_hit_zone_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

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

                Text(
                    text = stringResource(R.string.gesture_handle_swipe_left_action),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                ActionSelectorList(
                    selectedAction = state.gestureSwipeLeftAction,
                    selectedSpecificPkg = state.gestureSwipeLeftSpecificPackage,
                    onSelectAction = { action ->
                        onSelectSwipeLeftAction(action, state.gestureSwipeLeftSpecificPackage)
                    },
                    onOpenAppPicker = { onOpenAppPicker(GestureHandleSlot.SWIPE_LEFT) },
                    availableActions = TargetAction.gestureHandleEntries(),
                )

                Text(
                    text = stringResource(R.string.gesture_handle_swipe_right_action),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                ActionSelectorList(
                    selectedAction = state.gestureSwipeRightAction,
                    selectedSpecificPkg = state.gestureSwipeRightSpecificPackage,
                    onSelectAction = { action ->
                        onSelectSwipeRightAction(action, state.gestureSwipeRightSpecificPackage)
                    },
                    onOpenAppPicker = { onOpenAppPicker(GestureHandleSlot.SWIPE_RIGHT) },
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

@Composable
private fun GestureHapticRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun GestureDpSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = ((valueRange.endInclusive - valueRange.start).toInt() - 1)
                    .coerceAtLeast(0),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
