package viva.la.circle.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import viva.la.circle.engine.ActionExecutionEngine
import viva.la.circle.engine.AppInfo
import viva.la.circle.engine.AssistantAppInfo
import viva.la.circle.engine.CircleToSearch
import viva.la.circle.model.TargetAction
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
    onDismissDelayChange: (Int) -> Unit = {},
    onDiagnosticsEnabledChange: (Boolean) -> Unit = {},
    onCaptureModeChange: (Boolean) -> Unit = {},
    onUseCapturedKey: (Int) -> Unit = {},
    onRemoveCapturedKey: (Int) -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
    onClearDiagnostics: () -> Unit = {},
    diagEvents: List<DiagEvent> = emptyList(),
) {
    val scrollState = rememberScrollState()
    var showAppPickerForBlueLM by remember { mutableStateOf(false) }
    var showAppPickerForCamera by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
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
                            text = "Viva la Circle",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Accessibility Service Status Banner
            StatusBannerCard(
                state = state,
                onOpenSettingsClick = onOpenSettingsClick,
            )

            // 2. Target Action Configuration Section
            TargetActionConfigCard(
                state = state,
                onSelectBlueLMAction = onSelectBlueLMAction,
                onOpenAppPickerForBlueLM = { showAppPickerForBlueLM = true },
                onSelectCameraAction = onSelectCameraAction,
                onOpenAppPickerForCamera = { showAppPickerForCamera = true },
                onSkipCameraAppChange = onSkipCameraAppChange,
                onDismissDelayChange = onDismissDelayChange,
                onCaptureModeChange = onCaptureModeChange,
                onRemoveCapturedKey = onRemoveCapturedKey,
                onTestSelectedAction = onTestAssistantClick,
            )

            DiagnosticsCard(
                state = state,
                events = diagEvents,
                onDiagnosticsEnabledChange = onDiagnosticsEnabledChange,
                onCaptureModeChange = onCaptureModeChange,
                onUseCapturedKey = onUseCapturedKey,
                onCopyDiagnostics = onCopyDiagnostics,
                onClearDiagnostics = onClearDiagnostics,
            )

            // 3. Detected Voice Assistants List
            InstalledAssistantsCard(
                onSelectAsTargetApp = { pkgName ->
                    onSelectBlueLMAction(TargetAction.SPECIFIC_APP, pkgName)
                }
            )

            // 4. Service Statistics Card
            StatisticsCard(state = state)

            // 5. Onboarding & Usage Instructions Section
            OnboardingSection()

            Spacer(modifier = Modifier.height(16.dp))
        }

        // App Picker Dialogs
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
    }
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
    val statusTitle = if (isActive) "Service Active" else "Service Inactive"
    val statusDescription = if (isActive) {
        "Viva la Circle is enabled in System Settings and ready to launch configured action when triggers occur."
    } else {
        "Accessibility Service permission is required to detect Vivo BlueLM launch events and redirect them to your chosen assistant."
    }

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
                        text = if (state.isRunning) "Process Running" else if (isActive) "Waiting for trigger" else "Action Required",
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor.copy(alpha = 0.8f),
                    )
                }

                Surface(
                    color = if (isActive) Color(0xFF2E7D32) else Color(0xFFC62828),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = if (isActive) "ACTIVE" else "DISABLED",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Text(
                text = statusDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.9f),
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
                    text = if (isActive) "Manage Accessibility Settings" else "Enable in Accessibility Settings",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
fun TargetActionConfigCard(
    state: InterceptorServiceState,
    onSelectBlueLMAction: (TargetAction, String?) -> Unit,
    onOpenAppPickerForBlueLM: () -> Unit,
    onSelectCameraAction: (TargetAction, String?) -> Unit,
    onOpenAppPickerForCamera: () -> Unit,
    onSkipCameraAppChange: (Boolean) -> Unit,
    onDismissDelayChange: (Int) -> Unit,
    onCaptureModeChange: (Boolean) -> Unit,
    onRemoveCapturedKey: (Int) -> Unit,
    onTestSelectedAction: () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Target Action Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // BlueLM Power Button Interception Action
            Text(
                text = "BlueLM Button Trigger Action",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )

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

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Launch delay after dismiss: ${state.dismissDelayMs} ms",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "BACK hides Copilot immediately and keeps dismissing the OriginOS 5 float overlay while this wait runs. Then the action (Gemini) fires. Copilot still starts — the ROM does not give us the power key — but it should not stay on screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = state.dismissDelayMs.toFloat(),
                    onValueChange = { onDismissDelayChange(it.toInt()) },
                    valueRange = 0f..InterceptorStateRepository.LAUNCH_DELAY_MAX_MS.toFloat(),
                    steps = 24,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Camera Key Interception Action
            Text(
                text = "Camera Key Trigger Action",
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
                        text = "Don't intercept in camera apps",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Skip the shutter only if a camera app has already been in the foreground for 1.2s. Double-press still opens the camera (KEYCODE_DOUBLE_CLICK is not consumed).",
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
                    text = "Learned camera keys",
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
                            Text("Remove")
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
                    text = if (state.captureMode) "Stop key capture" else "Capture next key press",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (state.captureMode) {
                Text(
                    text = "Capture is armed. Press the shutter half-way, full press, and grip if you use one. Nothing is consumed while capturing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = onTestSelectedAction,
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
                    text = "Test Selected Action Now",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
fun ActionSelectorList(
    selectedAction: TargetAction,
    selectedSpecificPkg: String?,
    onSelectAction: (TargetAction) -> Unit,
    onOpenAppPicker: () -> Unit,
) {
    val context = LocalContext.current
    // Name the app "Default Assistant" actually resolves to. Without this the option reads as
    // "the assistant I chose" when it really means "whatever the OS is set to" — which is how a
    // stored Gemini pick can sit next to a launch that opens Google.
    val systemDefaultLabel = remember {
        ActionExecutionEngine.resolveSystemDefaultAssistant(context).first
            ?.let { pkg -> appLabelFor(context, pkg) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TargetAction.entries.forEach { action ->
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
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelectAction(action) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = action.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (action == TargetAction.DEFAULT_ASSISTANT && systemDefaultLabel != null) {
                                    "Currently the system default: $systemDefaultLabel"
                                } else {
                                    action.description
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
                                .padding(start = 36.dp),
                        ) {
                            Text(
                                text = if (!selectedSpecificPkg.isNullOrEmpty()) "App: $selectedSpecificPkg" else "No app selected",
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
                                    text = if (!selectedSpecificPkg.isNullOrEmpty()) "Change App" else "Select App",
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }

                    if (action == TargetAction.CIRCLE_TO_SEARCH && isSelected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        CircleToSearchGate(modifier = Modifier.padding(start = 36.dp))
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
                    text = "Detected Voice Assistants",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = "Voice assistant apps installed on your device that can be launched when BlueLM is intercepted:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                        text = "Default",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            if (assistant.isInstalled) {
                Surface(
                    color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { onSelectAsTarget() },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Check,
                            contentDescription = null,
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Installed",
                            color = Color(0xFF2E7D32),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "Not Installed",
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
                text = "Select Specific App",
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
                    placeholder = { Text("Search installed apps...") },
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
                                text = "No apps found matching search.",
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
                Text("Cancel")
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
                        text = "Diagnostics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "In-app log. This ROM's logcat is silent, so capture lives here.",
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
                    text = "Assistant resolution",
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
                        Text(if (state.captureMode) "Stop capture" else "Capture keys")
                    }
                    TextButton(onClick = onCopyDiagnostics) { Text("Copy") }
                    TextButton(onClick = onClearDiagnostics) { Text("Clear") }
                }

                val visible = events.takeLast(40).reversed()
                if (visible.isEmpty()) {
                    Text(
                        text = if (state.captureMode) {
                            "Waiting for a key. Press the shutter."
                        } else {
                            "No events yet. Toggle the accessibility service or press a key."
                        },
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
                                        Text("Use this key ($keyCode)")
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
                    text = "Interception Statistics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatItem(
                    label = "Total Intercepts",
                    value = state.interceptedCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = "Service State",
                    value = if (state.isRunning) "Running" else "Stopped",
                    valueColor = if (state.isRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatItem(
                    label = "Last Intercept Time",
                    value = formatTimestamp(state.lastInterceptedTime),
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = "Last Intercepted Package",
                    value = state.lastInterceptedPackage ?: "None yet",
                    modifier = Modifier.weight(1f),
                )
            }
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
            text = "Setup & How It Works",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )

        // Card A: Why Accessibility Permission is Needed
        InstructionCard(
            title = "1. Why Accessibility Permission is Needed",
            icon = AppIcons.HelpOutline,
        ) {
            Text(
                text = "On Vivo X200 Ultra, long pressing the power button triggers Vivo's built-in BlueLM / Copilot assistant by default with no native option to remap it.\n\n" +
                        "OriginOS 6 does not deliver KEYCODE_POWER to accessibility (camera shutter keys still arrive). Viva la Circle therefore watches for the Copilot window, dismisses it, and launches your chosen action.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Card B: Step-by-Step Enablement Guide
        InstructionCard(
            title = "2. Step-by-Step Setup Guide",
            icon = AppIcons.List,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StepItem(
                    stepNumber = "1",
                    text = "Tap 'Enable in Accessibility Settings' above to navigate to system settings.",
                )
                StepItem(
                    stepNumber = "2",
                    text = "Scroll down to 'Downloaded Apps' or 'Installed Services' section.",
                )
                StepItem(
                    stepNumber = "3",
                    text = "Find and select 'Viva la Circle' from the list.",
                )
                StepItem(
                    stepNumber = "4",
                    text = "Turn on 'Use Viva la Circle' toggle and confirm system permissions.",
                )
            }
        }

        // Card C: How Automatic Interception Works
        InstructionCard(
            title = "3. How Interception Works",
            icon = AppIcons.AutoAwesome,
        ) {
            Text(
                text = "Once enabled, the service runs quietly in the background:\n\n" +
                        "• OriginOS 6 does not give accessibility the power key, so Copilot still opens. The service dismisses that window, waits, then fires your BlueLM action.\n" +
                        "• Short-press Power is unchanged (the ROM keeps it).\n" +
                        "• Camera shutter remains a separate hardware-key intercept.",
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

fun formatTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0L) return "Never"
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

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val current = readiness
        if (current == null) {
            Text(
                text = "Checking Google / assistant status…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = when {
                    current.usable && current.googleIsAssistant ->
                        "Ready. Google is the default assistant."
                    current.usable ->
                        "Ready. This ROM exposes Contextual Search, so the default assistant does not need to be Google."
                    else -> current.blocker ?: "Circle to Search is not ready."
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (current.usable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (!current.googleInstalled) {
                Text(
                    text = "Install the Google app, then set it as the default digital assistant.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!current.googleIsAssistant && current.contextualSearchKey.isNullOrEmpty()) {
                val settingsIntent = CircleToSearch.assistantSettingsIntent(context.packageManager)
                if (settingsIntent != null) {
                    OutlinedButton(
                        onClick = { startCircleToSearchIntent(context, settingsIntent) },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Open assistant settings", fontSize = 12.sp)
                    }
                } else {
                    Text(
                        text = "Settings → Apps → Default apps → Digital assistant → Google",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "Set Autostart and No restrictions for Google in vivo battery settings. " +
                    "Otherwise Circle to Search may appear only after you open Google by hand.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun startCircleToSearchIntent(context: android.content.Context, intent: Intent?) {
    if (intent == null) return
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * States the concrete outcome of the current selection. `DEFAULT_ASSISTANT` ignores any stored
 * specific package, so a Gemini pick can sit in preferences while Google is what actually opens —
 * this line makes that visible instead of surprising.
 */
@Composable
fun WillLaunchSummary(action: TargetAction, specificPackage: String?) {
    val context = LocalContext.current
    val summary = remember(action, specificPackage) {
        when (action) {
            TargetAction.NONE -> "Nothing — this trigger is disabled."
            TargetAction.ASSISTANT_CHOOSER -> "The chooser menu, every time."
            TargetAction.DEFAULT_ASSISTANT -> {
                val pkg = ActionExecutionEngine.resolveSystemDefaultAssistant(context).first
                if (pkg == null) {
                    "The system default assistant (none is currently set)."
                } else {
                    "${appLabelFor(context, pkg)} — the system default, not a pick made here."
                }
            }
            TargetAction.CIRCLE_TO_SEARCH -> "Google Circle to Search over the current screen."
            TargetAction.HWCTS -> "HwCTS circle search on the current screen."
            TargetAction.SPECIFIC_APP ->
                if (specificPackage.isNullOrEmpty()) {
                    "No app chosen yet — falls back to the system default assistant."
                } else {
                    appLabelFor(context, specificPackage)
                }
            TargetAction.FLASHLIGHT -> "Toggle the torch."
            TargetAction.SCREENSHOT -> "Take a screenshot."
            TargetAction.MUTE_TOGGLE -> "Toggle media mute."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Will launch: $summary",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
