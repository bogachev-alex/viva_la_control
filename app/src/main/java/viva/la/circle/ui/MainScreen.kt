package viva.la.circle.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import viva.la.circle.R
import viva.la.circle.engine.ActionExecutionEngine
import viva.la.circle.engine.AppInfo
import viva.la.circle.engine.AssistantAppInfo
import viva.la.circle.engine.CircleToSearch
import viva.la.circle.engine.HuaweiPowerManagement
import viva.la.circle.model.BlueLMActionConfig
import viva.la.circle.model.TargetAction
import viva.la.circle.model.VolumeShortAction
import viva.la.circle.remap.CtsReadiness
import viva.la.circle.remap.RemapConfig
import viva.la.circle.service.DiagEvent
import viva.la.circle.service.InterceptorServiceState
import viva.la.circle.service.InterceptorStateRepository
import viva.la.circle.ui.theme.AppIcons
import viva.la.circle.ui.theme.Bluelm_interceptorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: InterceptorServiceState,
    onOpenSettingsClick: () -> Unit,
    onTestAssistantClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectBlueLMAction: (TargetAction, String?) -> Unit = { _, _ -> },
    onSelectCameraAction: (TargetAction, String?) -> Unit = { _, _ -> },
    onSkipCameraAppChange: (Boolean) -> Unit = {},
    onGestureHandleEnabledChange: (Boolean) -> Unit = {},
    onGestureHandleOpacityChange: (Int) -> Unit = {},
    onGestureHandleHapticChange: (GestureHapticSlot, Boolean) -> Unit = { _, _ -> },
    onGestureHandleHideInFullscreenChange: (Boolean) -> Unit = {},
    onGesturePillColorChange: (Int) -> Unit = {},
    onGesturePillWidthChange: (Int) -> Unit = {},
    onGesturePillHeightChange: (Int) -> Unit = {},
    onGestureBottomOffsetChange: (Int) -> Unit = {},
    onSelectGestureTapAction: (TargetAction, String?) -> Unit = { _, _ -> },
    onSelectGestureLongPressAction: (TargetAction, String?) -> Unit = { _, _ -> },
    onSelectGestureSwipeUpAction: (TargetAction, String?) -> Unit = { _, _ -> },
    onSelectGestureSwipeLeftAction: (TargetAction, String?) -> Unit = { _, _ -> },
    onSelectGestureSwipeRightAction: (TargetAction, String?) -> Unit = { _, _ -> },
    onVolumeSkipTracksChange: (Boolean) -> Unit = {},
    onVolumeShortRemapChange: (Boolean) -> Unit = {},
    onVolumeLongPressMsChange: (Long) -> Unit = {},
    onVolumeHapticChange: (Boolean) -> Unit = {},
    onVolumeUpShortChange: (VolumeShortAction) -> Unit = {},
    onVolumeDownShortChange: (VolumeShortAction) -> Unit = {},
    onDiagnosticsEnabledChange: (Boolean) -> Unit = {},
    onCaptureModeChange: (Boolean) -> Unit = {},
    onUseCapturedKey: (Int) -> Unit = {},
    onRemoveCapturedKey: (Int) -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
    onClearDiagnostics: () -> Unit = {},
    onAcknowledgeHuaweiAppLaunch: () -> Unit = {},
    diagEvents: List<DiagEvent> = emptyList(),
) {
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var showAppPickerForBlueLM by remember { mutableStateOf(false) }
    var showAppPickerForCamera by remember { mutableStateOf(false) }
    var showAppPickerForGesture by remember { mutableStateOf<GestureHandleSlot?>(null) }
    var showAppPickerForVolumeUp by remember { mutableStateOf(false) }
    var showAppPickerForVolumeDown by remember { mutableStateOf(false) }
    val blueLMConfigured = BlueLMActionConfig.isConfigured(state.blueLMAction)

    // System back leaves a section and returns to the menu instead of closing the app.
    BackHandler(enabled = section != null) { section = null }
    // Each screen starts at the top; a scroll offset from the menu must not carry into a section.
    val scrollState = remember(section) { ScrollState(initial = 0) }

    Scaffold(
        topBar = {
            val current = section
            if (current == null) {
                HomeTopBar()
            } else {
                SectionTopBar(
                    title = stringResource(current.titleRes),
                    onBack = { section = null },
                )
            }
        },
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (section) {
                null -> {
                    StatusBannerCard(
                        state = state,
                        onOpenSettingsClick = onOpenSettingsClick,
                    )

                    if (HuaweiPowerManagement.isHuaweiDevice() &&
                        RemapConfig.needsHuaweiAppLaunchCard(
                            isHuaweiDevice = true,
                            acknowledged = state.huaweiAppLaunchAcknowledged,
                        )
                    ) {
                        HuaweiBatteryWhitelistCard(onAlreadyConfigured = onAcknowledgeHuaweiAppLaunch)
                    }

                    Text(
                        text = stringResource(R.string.menu_sections_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SettingsMenuCard(
                        state = state,
                        onOpenSection = { section = it },
                    )
                }

                SettingsSection.BLUELM -> BlueLMActionCard(
                    state = state,
                    onSelectBlueLMAction = onSelectBlueLMAction,
                    onOpenAppPickerForBlueLM = { showAppPickerForBlueLM = true },
                    onTestSelectedAction = {
                        if (blueLMConfigured) onTestAssistantClick()
                    },
                    testEnabled = blueLMConfigured,
                )

                SettingsSection.CAMERA -> CameraActionCard(
                    state = state,
                    onSelectCameraAction = onSelectCameraAction,
                    onOpenAppPickerForCamera = { showAppPickerForCamera = true },
                    onSkipCameraAppChange = onSkipCameraAppChange,
                    onCaptureModeChange = onCaptureModeChange,
                    onRemoveCapturedKey = onRemoveCapturedKey,
                )

                SettingsSection.GESTURE_HANDLE -> GestureHandleCard(
                    state = state,
                    onEnabledChange = onGestureHandleEnabledChange,
                    onOpacityChange = onGestureHandleOpacityChange,
                    onColorChange = onGesturePillColorChange,
                    onHapticChange = onGestureHandleHapticChange,
                    onHideInFullscreenChange = onGestureHandleHideInFullscreenChange,
                    onWidthChange = onGesturePillWidthChange,
                    onHeightChange = onGesturePillHeightChange,
                    onBottomOffsetChange = onGestureBottomOffsetChange,
                    onSelectTapAction = onSelectGestureTapAction,
                    onSelectLongPressAction = onSelectGestureLongPressAction,
                    onSelectSwipeUpAction = onSelectGestureSwipeUpAction,
                    onSelectSwipeLeftAction = onSelectGestureSwipeLeftAction,
                    onSelectSwipeRightAction = onSelectGestureSwipeRightAction,
                    onOpenAppPicker = { slot -> showAppPickerForGesture = slot },
                )

                SettingsSection.VOLUME_KEYS -> VolumeKeysCard(
                    state = state,
                    onSkipTracksChange = onVolumeSkipTracksChange,
                    onShortRemapChange = onVolumeShortRemapChange,
                    onLongPressMsChange = onVolumeLongPressMsChange,
                    onHapticChange = onVolumeHapticChange,
                    onVolumeUpShortChange = onVolumeUpShortChange,
                    onVolumeDownShortChange = onVolumeDownShortChange,
                    onOpenAppPickerForVolumeUp = { showAppPickerForVolumeUp = true },
                    onOpenAppPickerForVolumeDown = { showAppPickerForVolumeDown = true },
                )

                SettingsSection.ASSISTANTS -> InstalledAssistantsCard(
                    onSelectAsTargetApp = { pkgName ->
                        onSelectBlueLMAction(TargetAction.SPECIFIC_APP, pkgName)
                    },
                )

                SettingsSection.DIAGNOSTICS -> {
                    DiagnosticsCard(
                        state = state,
                        events = diagEvents,
                        onDiagnosticsEnabledChange = onDiagnosticsEnabledChange,
                        onCaptureModeChange = onCaptureModeChange,
                        onUseCapturedKey = onUseCapturedKey,
                        onCopyDiagnostics = onCopyDiagnostics,
                        onClearDiagnostics = onClearDiagnostics,
                    )
                    StatisticsCard(state = state)
                }

                SettingsSection.HELP -> OnboardingSection()
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        if (showAppPickerForBlueLM) {
            AppPickerDialog(
                onDismiss = { showAppPickerForBlueLM = false },
                onSelectApp = { app ->
                    onSelectBlueLMAction(TargetAction.SPECIFIC_APP, app.packageName)
                    showAppPickerForBlueLM = false
                }
            )
        }

        if (showAppPickerForCamera) {
            AppPickerDialog(
                onDismiss = { showAppPickerForCamera = false },
                onSelectApp = { app ->
                    onSelectCameraAction(TargetAction.SPECIFIC_APP, app.packageName)
                    showAppPickerForCamera = false
                }
            )
        }

        showAppPickerForGesture?.let { slot ->
            AppPickerDialog(
                onDismiss = { showAppPickerForGesture = null },
                onSelectApp = { app ->
                    when (slot) {
                        GestureHandleSlot.TAP ->
                            onSelectGestureTapAction(TargetAction.SPECIFIC_APP, app.packageName)
                        GestureHandleSlot.LONG_PRESS ->
                            onSelectGestureLongPressAction(TargetAction.SPECIFIC_APP, app.packageName)
                        GestureHandleSlot.SWIPE_UP ->
                            onSelectGestureSwipeUpAction(TargetAction.SPECIFIC_APP, app.packageName)
                        GestureHandleSlot.SWIPE_LEFT ->
                            onSelectGestureSwipeLeftAction(TargetAction.SPECIFIC_APP, app.packageName)
                        GestureHandleSlot.SWIPE_RIGHT ->
                            onSelectGestureSwipeRightAction(TargetAction.SPECIFIC_APP, app.packageName)
                    }
                    showAppPickerForGesture = null
                },
            )
        }

        if (showAppPickerForVolumeUp) {
            AppPickerDialog(
                onDismiss = { showAppPickerForVolumeUp = false },
                onSelectApp = { app ->
                    onVolumeUpShortChange(
                        VolumeShortAction.Remap(TargetAction.SPECIFIC_APP, app.packageName),
                    )
                    showAppPickerForVolumeUp = false
                },
            )
        }

        if (showAppPickerForVolumeDown) {
            AppPickerDialog(
                onDismiss = { showAppPickerForVolumeDown = false },
                onSelectApp = { app ->
                    onVolumeDownShortChange(
                        VolumeShortAction.Remap(TargetAction.SPECIFIC_APP, app.packageName),
                    )
                    showAppPickerForVolumeDown = false
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar() {
    CenterAlignedTopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionTopBar(
    title: String,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = AppIcons.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
fun StatusBannerCard(
    state: InterceptorServiceState,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = state.isEnabledInSettings
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }

    val contentColor = if (isActive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    val icon = if (isActive) AppIcons.CheckCircle else AppIcons.Warning
    val statusTitle = stringResource(
        if (isActive) R.string.service_active else R.string.service_inactive,
    )
    val statusDescription = stringResource(
        if (isActive) R.string.service_active_description else R.string.service_inactive_description,
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(contentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                    )
                    Text(
                        text = stringResource(
                            when {
                                state.isRunning -> R.string.process_running
                                isActive -> R.string.waiting_for_trigger
                                else -> R.string.action_required
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                    )
                }
            }

            Text(
                text = statusDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )

            Button(
                onClick = onOpenSettingsClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    contentColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = AppIcons.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (isActive) R.string.manage_accessibility else R.string.enable_accessibility,
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
fun BlueLMActionCard(
    state: InterceptorServiceState,
    onSelectBlueLMAction: (TargetAction, String?) -> Unit,
    onOpenAppPickerForBlueLM: () -> Unit,
    onTestSelectedAction: () -> Unit,
    testEnabled: Boolean,
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.target_action_settings),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = stringResource(R.string.bluelm_trigger_action),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )

            if (!testEnabled) {
                Text(
                    text = stringResource(R.string.pick_action_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Medium,
                )
            }

            ActionSelectorList(
                selectedAction = state.blueLMAction,
                selectedSpecificPkg = state.blueLMSpecificPackage,
                onSelectAction = { action ->
                    onSelectBlueLMAction(action, state.blueLMSpecificPackage)
                },
                onOpenAppPicker = onOpenAppPickerForBlueLM,
            )

            WillLaunchSummary(
                action = state.blueLMAction,
                specificPackage = state.blueLMSpecificPackage,
            )

            Button(
                onClick = onTestSelectedAction,
                enabled = testEnabled,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = AppIcons.Launch,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.test_selected_action),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
fun CameraActionCard(
    state: InterceptorServiceState,
    onSelectCameraAction: (TargetAction, String?) -> Unit,
    onOpenAppPickerForCamera: () -> Unit,
    onSkipCameraAppChange: (Boolean) -> Unit,
    onCaptureModeChange: (Boolean) -> Unit,
    onRemoveCapturedKey: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.camera_trigger_action),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )

            ActionSelectorList(
                selectedAction = state.cameraAction,
                selectedSpecificPkg = state.cameraSpecificPackage,
                onSelectAction = { action ->
                    onSelectCameraAction(action, state.cameraSpecificPackage)
                },
                onOpenAppPicker = onOpenAppPickerForCamera,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dont_intercept_in_camera),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.dont_intercept_in_camera_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.skipCameraApp,
                    onCheckedChange = onSkipCameraAppChange,
                )
            }

            if (state.cameraKeyCodes.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.learned_camera_keys),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                state.cameraKeyCodes.sorted().forEach { code ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "$code ${keyCodeLabel(code)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRemoveCapturedKey(code) }) {
                            Text(stringResource(R.string.remove))
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { onCaptureModeChange(!state.captureMode) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        if (state.captureMode) R.string.stop_key_capture else R.string.capture_next_key,
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (state.captureMode) {
                Text(
                    text = stringResource(R.string.capture_armed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}



@Composable
fun ActionSelectorList(
    selectedAction: TargetAction?,
    selectedSpecificPkg: String?,
    onSelectAction: (TargetAction) -> Unit,
    onOpenAppPicker: () -> Unit,
    availableActions: List<TargetAction> = TargetAction.triggerEntries(),
) {
    val context = LocalContext.current
    // Name the app "Default Assistant" actually resolves to. Without this the option reads as
    // "the assistant I chose" when it really means "whatever the OS is set to" — which is how a
    // stored Specific App pick can sit next to a launch that opens the system default.
    val systemDefaultLabel = remember {
        ActionExecutionEngine.resolveSystemDefaultAssistant(context).first
            ?.let { pkg -> appLabelFor(context, pkg) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        availableActions.forEach { action ->
            val isSelected = selectedAction == action
            val icon = getActionIcon(action)

            OutlinedCard(
                onClick = { onSelectAction(action) },
                shape = RoundedCornerShape(12.dp),
                border = if (isSelected) {
                    CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.primary), width = 2.dp)
                } else {
                    CardDefaults.outlinedCardBorder()
                },
                colors = if (isSelected) {
                    CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                } else {
                    CardDefaults.outlinedCardColors()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelectAction(action) },
                                modifier = Modifier.requiredSize(20.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(action.titleRes),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                text = if (action == TargetAction.DEFAULT_ASSISTANT && systemDefaultLabel != null) {
                                    stringResource(R.string.currently_system_default, systemDefaultLabel)
                                } else {
                                    stringResource(action.descriptionRes)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (action == TargetAction.SPECIFIC_APP && isSelected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 64.dp),
                        ) {
                            Text(
                                text = if (!selectedSpecificPkg.isNullOrEmpty()) {
                                    stringResource(
                                        R.string.app_selected,
                                        appLabelFor(context, selectedSpecificPkg),
                                    )
                                } else {
                                    stringResource(R.string.no_app_selected)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = onOpenAppPicker,
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    text = stringResource(
                                        if (!selectedSpecificPkg.isNullOrEmpty()) {
                                            R.string.change_app
                                        } else {
                                            R.string.select_app
                                        },
                                    ),
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }

                    if (action == TargetAction.CIRCLE_TO_SEARCH && isSelected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        CircleToSearchGate(modifier = Modifier.padding(start = 64.dp))
                    }

                    if (action == TargetAction.HWCTS && isSelected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HwctsGate(modifier = Modifier.padding(start = 64.dp))
                    }

                    if (action == TargetAction.DEFAULT_ASSISTANT && isSelected) {
                        val loopPkg = remember {
                            RemapConfig.defaultAssistantLoopPackage(
                                action = TargetAction.DEFAULT_ASSISTANT,
                                systemDefaultPackage =
                                    ActionExecutionEngine.resolveSystemDefaultAssistant(context).first,
                                ownPackage = context.packageName,
                            )
                        }
                        if (loopPkg != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.default_assistant_loop_warning, loopPkg),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 64.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InstalledAssistantsCard(
    onSelectAsTargetApp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val installedAssistants by produceState(initialValue = emptyList<AssistantAppInfo>()) {
        value = withContext(Dispatchers.IO) {
            ActionExecutionEngine.getInstalledAssistants(context)
        }
    }

    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.detected_voice_assistants),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = stringResource(R.string.detected_voice_assistants_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (installedAssistants.isEmpty()) {
                Text(
                    text = stringResource(R.string.assistants_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    installedAssistants.forEach { assistant ->
                        AssistantStatusItem(
                            assistant = assistant,
                            onSelectAsTarget = { onSelectAsTargetApp(assistant.packageName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AssistantStatusItem(
    assistant: AssistantAppInfo,
    onSelectAsTarget: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PackageIcon(
                packageName = assistant.packageName,
                contentDescription = assistant.label,
                size = 32.dp,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = assistant.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = assistant.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (assistant.isDefault) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.badge_default),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            if (assistant.isInstalled) {
                OutlinedButton(
                    onClick = onSelectAsTarget,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.use_as_target),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.badge_not_installed),
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun AppPickerDialog(
    onDismiss: () -> Unit,
    onSelectApp: (AppInfo) -> Unit,
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val allApps by produceState<List<AppInfo>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            ActionExecutionEngine.getInstalledLaunchableApps(context)
        }
    }
    val filteredApps = remember(searchQuery, allApps) {
        val apps = allApps.orEmpty()
        if (searchQuery.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.select_specific_app),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.search_installed_apps)) },
                    leadingIcon = {
                        Icon(imageVector = AppIcons.Search, contentDescription = null)
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                when {
                    allApps == null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    }
                    filteredApps.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.no_apps_matching_search),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            items(filteredApps, key = { it.packageName }) { app ->
                                AppPickerItem(app = app, onClick = { onSelectApp(app) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun AppPickerItem(
    app: AppInfo,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PackageIcon(
                packageName = app.packageName,
                contentDescription = app.label,
                size = 32.dp,
                fallback = AppIcons.Apps,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
fun DiagnosticsCard(
    state: InterceptorServiceState,
    events: List<DiagEvent>,
    onDiagnosticsEnabledChange: (Boolean) -> Unit,
    onCaptureModeChange: (Boolean) -> Unit,
    onUseCapturedKey: (Int) -> Unit,
    onCopyDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.diagnostics),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.diagnostics_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.diagnosticsEnabled,
                    onCheckedChange = onDiagnosticsEnabledChange,
                )
            }

            val resolution = remember { ActionExecutionEngine.assistantResolutionReport(context) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = stringResource(R.string.assistant_resolution),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                resolution.forEach { (label, value) ->
                    Text(
                        text = "$label = $value",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                    )
                }
            }

            if (state.diagnosticsEnabled || state.captureMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onCaptureModeChange(!state.captureMode) }) {
                        Text(
                            stringResource(
                                if (state.captureMode) R.string.stop_capture else R.string.capture_keys,
                            ),
                        )
                    }
                    TextButton(onClick = onCopyDiagnostics) { Text(stringResource(R.string.copy)) }
                    TextButton(onClick = onClearDiagnostics) { Text(stringResource(R.string.clear)) }
                }

                val visible = events.takeLast(40).reversed()
                if (visible.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (state.captureMode) R.string.waiting_for_key else R.string.no_diag_events,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        visible.forEach { event ->
                            val keyCode = parseKeyCodeFromDetail(event.detail)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surface,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .padding(8.dp),
                            ) {
                                Text(
                                    text = "${formatDiagTime(event.timeMs)}  ${event.kind}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = event.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                )
                                if (event.kind == "KEY" && keyCode != null) {
                                    TextButton(onClick = { onUseCapturedKey(keyCode) }) {
                                        Text(stringResource(R.string.use_this_key, keyCode))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun keyCodeLabel(code: Int): String {
    return try {
        android.view.KeyEvent.keyCodeToString(code)
    } catch (_: Exception) {
        "KEYCODE_$code"
    }
}

fun parseKeyCodeFromDetail(detail: String): Int? {
    return detail.substringBefore(' ').trim().toIntOrNull()
}

fun formatDiagTime(timestampMs: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    return sdf.format(Date(timestampMs))
}

fun getActionIcon(action: TargetAction): ImageVector {
    return when (action) {
        TargetAction.NONE -> AppIcons.Block
        TargetAction.ASSISTANT_CHOOSER -> AppIcons.TouchApp
        TargetAction.DEFAULT_ASSISTANT -> AppIcons.Mic
        TargetAction.CIRCLE_TO_SEARCH -> AppIcons.Search
        TargetAction.HWCTS -> AppIcons.Shield
        TargetAction.SPECIFIC_APP -> AppIcons.Apps
        TargetAction.FLASHLIGHT -> AppIcons.FlashOn
        TargetAction.SCREENSHOT -> AppIcons.Screenshot
        TargetAction.MUTE_TOGGLE -> AppIcons.VolumeOff
        TargetAction.HOME -> AppIcons.Launch
        TargetAction.BACK -> AppIcons.ArrowBack
    }
}

@Composable
fun StatisticsCard(
    state: InterceptorServiceState,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = AppIcons.Analytics,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.interception_statistics),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatItem(
                    label = stringResource(R.string.total_intercepts),
                    value = state.interceptedCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = stringResource(R.string.service_state),
                    value = stringResource(
                        if (state.isRunning) R.string.running else R.string.stopped,
                    ),
                    valueColor = if (state.isRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatItem(
                    label = stringResource(R.string.last_intercept_time),
                    value = formatTimestamp(
                        state.lastInterceptedTime,
                        neverLabel = stringResource(R.string.never),
                    ),
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = stringResource(R.string.last_intercepted_package),
                    value = state.lastInterceptedPackage ?: stringResource(R.string.none_yet),
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = stringResource(R.string.stats_ephemeral_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
            )
        }
    }
}

@Composable
fun OnboardingSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.setup_how_it_works),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )

        // Card A: Why Accessibility Permission is Needed
        InstructionCard(
            title = stringResource(R.string.onboarding_why_title),
            icon = AppIcons.HelpOutline,
        ) {
            Text(
                text = stringResource(R.string.onboarding_why_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Card B: Step-by-Step Enablement Guide
        InstructionCard(
            title = stringResource(R.string.onboarding_setup_title),
            icon = AppIcons.List,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StepItem(
                    stepNumber = "1",
                    text = stringResource(R.string.onboarding_step_1),
                )
                StepItem(
                    stepNumber = "2",
                    text = stringResource(R.string.onboarding_step_2),
                )
                StepItem(
                    stepNumber = "3",
                    text = stringResource(R.string.onboarding_step_3),
                )
                StepItem(
                    stepNumber = "4",
                    text = stringResource(R.string.onboarding_step_4),
                )
            }
        }

        // Card C: How Automatic Interception Works
        InstructionCard(
            title = stringResource(R.string.onboarding_how_title),
            icon = AppIcons.AutoAwesome,
        ) {
            Text(
                text = stringResource(R.string.onboarding_how_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun InstructionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            content()
        }
    }
}

@Composable
fun StepItem(
    stepNumber: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stepNumber,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

fun formatTimestamp(timestampMs: Long, neverLabel: String = "Never"): String {
    if (timestampMs <= 0L) return neverLabel
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestampMs))
}

@Preview(showBackground = true, name = "MainScreen Light Mode")
@Composable
fun MainScreenLightPreview() {
    Bluelm_interceptorTheme(darkTheme = false) {
        MainScreen(
            state = InterceptorServiceState(
                isRunning = true,
                isEnabledInSettings = true,
                lastInterceptedTime = System.currentTimeMillis(),
                interceptedCount = 5,
                lastInterceptedPackage = "com.vivo.agent",
                blueLMAction = TargetAction.ASSISTANT_CHOOSER,
                cameraAction = TargetAction.DEFAULT_ASSISTANT,
            ),
            onOpenSettingsClick = {},
            onTestAssistantClick = {},
        )
    }
}

@Preview(showBackground = true, name = "MainScreen Dark Mode")
@Composable
fun MainScreenDarkPreview() {
    Bluelm_interceptorTheme(darkTheme = true) {
        MainScreen(
            state = InterceptorServiceState(
                isRunning = true,
                isEnabledInSettings = true,
                lastInterceptedTime = System.currentTimeMillis() - 3600000,
                interceptedCount = 12,
                lastInterceptedPackage = "com.vivo.vpa",
                blueLMAction = TargetAction.SPECIFIC_APP,
                blueLMSpecificPackage = "com.openai.chatgpt",
                cameraAction = TargetAction.FLASHLIGHT,
            ),
            onOpenSettingsClick = {},
            onTestAssistantClick = {},
        )
    }
}

@Composable
fun HuaweiBatteryWhitelistCard(
    onAlreadyConfigured: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.huawei_allow_background),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = stringResource(R.string.huawei_allow_background_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    if (!HuaweiPowerManagement.openAppLaunchSettings(context)) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.huawei_open_app_launch_toast),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(stringResource(R.string.huawei_open_app_launch), fontSize = 12.sp)
            }
            TextButton(onClick = onAlreadyConfigured) {
                Text(stringResource(R.string.huawei_already_configured), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun HwctsGate(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val installed by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) {
            ActionExecutionEngine.isHwctsInstalled(context)
        }
    }
    val accessibilityOn by produceState(initialValue = false, installed) {
        value = withContext(Dispatchers.IO) {
            ActionExecutionEngine.hwctsAccessibilityEnabled(
                ActionExecutionEngine.enabledAccessibilityServices(context),
            )
        }
    }
    val gate = RemapConfig.hwctsGate(installed = installed, accessibilityEnabled = accessibilityOn)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (gate) {
            RemapConfig.HwctsGate.NOT_INSTALLED -> {
                Text(
                    text = stringResource(R.string.hwcts_not_installed),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.hwcts_not_installed_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RemapConfig.HwctsGate.ACCESSIBILITY_OFF -> {
                Text(
                    text = stringResource(R.string.hwcts_a11y_off),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.hwcts_a11y_off_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RemapConfig.HwctsGate.READY -> {
                Text(
                    text = stringResource(R.string.hwcts_ready),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Human-readable app name for a package, falling back to the package name itself. */
fun appLabelFor(context: android.content.Context, packageName: String): String {
    return try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }
}

@Composable
fun CircleToSearchGate(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var probeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                probeTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val readiness by produceState<CircleToSearch.Readiness?>(initialValue = null, probeTick) {
        value = withContext(Dispatchers.IO) { CircleToSearch.probe(context) }
    }
    val settingsIntent = remember(readiness) {
        CircleToSearch.assistantSettingsIntent(context.packageManager)
    }
    val gate = RemapConfig.ctsGate(
        readiness = readiness?.let {
            CtsReadiness(
                usable = it.usable,
                googleInstalled = it.googleInstalled,
                googleIsAssistant = it.googleIsAssistant,
                contextualSearchKey = it.contextualSearchKey,
                blockerText = it.localizedBlocker(context),
            )
        },
        hasAssistantSettingsIntent = settingsIntent != null,
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val current = gate) {
            RemapConfig.CtsGate.Checking -> {
                Text(
                    text = stringResource(R.string.cts_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RemapConfig.CtsGate.ReadyGoogle -> {
                Text(
                    text = stringResource(R.string.cts_ready_google),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.cts_battery_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RemapConfig.CtsGate.ReadyContextual -> {
                Text(
                    text = stringResource(R.string.cts_ready_contextual),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.cts_battery_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is RemapConfig.CtsGate.Blocked -> {
                Text(
                    text = current.blockerText ?: stringResource(R.string.cts_not_ready),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (!current.googleInstalled) {
                    Text(
                        text = stringResource(R.string.cts_install_google),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (current.showOpenAssistantSettings) {
                    OutlinedButton(
                        onClick = { startCircleToSearchIntent(context, settingsIntent) },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.cts_open_assistant_settings), fontSize = 12.sp)
                    }
                } else if (current.showAssistantSettingsPath) {
                    Text(
                        text = stringResource(R.string.cts_assistant_settings_path),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.cts_battery_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

fun startCircleToSearchIntent(context: android.content.Context, intent: Intent?) {
    if (intent == null) return
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(
            context,
            context.getString(R.string.could_not_open, e.message ?: ""),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

/**
 * States the concrete outcome of the current selection via [RemapConfig.firePreview].
 */
@Composable
fun WillLaunchSummary(action: TargetAction?, specificPackage: String?) {
    val context = LocalContext.current
    val systemDefault = remember(action) {
        if (action == TargetAction.DEFAULT_ASSISTANT) {
            ActionExecutionEngine.resolveSystemDefaultAssistant(context)
        } else {
            null to ""
        }
    }
    val hwctsInstalled = remember(action) {
        if (action == TargetAction.HWCTS) {
            ActionExecutionEngine.isHwctsInstalled(context)
        } else {
            true
        }
    }
    val preview = RemapConfig.firePreview(
        action = action,
        specificPackage = specificPackage,
        systemDefaultPackage = systemDefault.first,
        ownPackage = context.packageName,
        hwctsInstalled = hwctsInstalled,
        appLabel = { pkg -> appLabelFor(context, pkg) },
    )
    val summary = when (preview) {
        RemapConfig.FirePreview.Unset -> null
        RemapConfig.FirePreview.PassThrough -> stringResource(R.string.will_launch_none)
        RemapConfig.FirePreview.Chooser -> stringResource(R.string.will_launch_chooser)
        RemapConfig.FirePreview.DefaultUnset -> stringResource(R.string.will_launch_default_unset)
        is RemapConfig.FirePreview.DefaultBlocked ->
            stringResource(R.string.will_launch_default_blocked, preview.packageName)
        is RemapConfig.FirePreview.DefaultApp ->
            stringResource(R.string.will_launch_default_app, preview.label)
        RemapConfig.FirePreview.CircleToSearch -> stringResource(R.string.will_launch_cts)
        is RemapConfig.FirePreview.Hwcts -> stringResource(
            if (!preview.installed) R.string.will_launch_hwcts_missing else R.string.will_launch_hwcts,
        )
        RemapConfig.FirePreview.SpecificUnset -> stringResource(R.string.will_launch_specific_unset)
        is RemapConfig.FirePreview.SpecificApp -> preview.label
        RemapConfig.FirePreview.Flashlight -> stringResource(R.string.will_launch_flashlight)
        RemapConfig.FirePreview.Screenshot -> stringResource(R.string.will_launch_screenshot)
        RemapConfig.FirePreview.Mute -> stringResource(R.string.will_launch_mute)
        RemapConfig.FirePreview.Home -> stringResource(R.string.will_launch_home)
        RemapConfig.FirePreview.Back -> stringResource(R.string.will_launch_back)
    }

    if (summary == null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(10.dp),
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.will_launch_unset),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.will_launch, summary),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
