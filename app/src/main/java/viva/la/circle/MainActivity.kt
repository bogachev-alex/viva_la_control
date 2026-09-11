package viva.la.circle

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import viva.la.circle.engine.ActionExecutionEngine
import viva.la.circle.engine.CircleToSearch
import viva.la.circle.model.VolumeShortAction
import viva.la.circle.remap.TargetActionFire
import viva.la.circle.service.InterceptorStateRepository
import viva.la.circle.ui.MainScreen
import viva.la.circle.ui.theme.Bluelm_interceptorTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        InterceptorStateRepository.loadFromPreferences(this)

        setContent {
            Bluelm_interceptorTheme {
                val serviceState by InterceptorStateRepository.serviceState.collectAsStateWithLifecycle()
                val diagEvents by InterceptorStateRepository.diagLog.collectAsStateWithLifecycle()

                MainScreen(
                    state = serviceState,
                    onOpenSettingsClick = { openAccessibilitySettings() },
                    onTestAssistantClick = { testSelectedAction() },
                    onSelectBlueLMAction = { action, specificPkg ->
                        InterceptorStateRepository.setBlueLMAction(this, action, specificPkg)
                    },
                    onSelectCameraAction = { action, specificPkg ->
                        InterceptorStateRepository.setCameraAction(this, action, specificPkg)
                    },
                    onSkipCameraAppChange = { skip ->
                        InterceptorStateRepository.setSkipCameraApp(this, skip)
                    },
                    onVolumeSkipTracksChange = { enabled ->
                        InterceptorStateRepository.setVolumeSkipTracksEnabled(this, enabled)
                    },
                    onVolumeShortRemapChange = { enabled ->
                        InterceptorStateRepository.setVolumeShortRemapEnabled(this, enabled)
                    },
                    onVolumeLongPressMsChange = { ms ->
                        InterceptorStateRepository.setVolumeLongPressMs(this, ms)
                    },
                    onVolumeHapticChange = { enabled ->
                        InterceptorStateRepository.setVolumeHapticEnabled(this, enabled)
                    },
                    onVolumeUpShortChange = { action ->
                        InterceptorStateRepository.setVolumeUpShortAction(this, action)
                    },
                    onVolumeDownShortChange = { action ->
                        InterceptorStateRepository.setVolumeDownShortAction(this, action)
                    },
                    onDiagnosticsEnabledChange = { enabled ->
                        InterceptorStateRepository.setDiagnosticsEnabled(enabled)
                    },
                    onCaptureModeChange = { enabled ->
                        InterceptorStateRepository.setCaptureMode(enabled)
                    },
                    onUseCapturedKey = { keyCode ->
                        InterceptorStateRepository.addCameraKeyCode(this, keyCode)
                        Toast.makeText(
                            this,
                            getString(R.string.toast_learned_key, keyCode),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onRemoveCapturedKey = { keyCode ->
                        InterceptorStateRepository.removeCameraKeyCode(this, keyCode)
                    },
                    onCopyDiagnostics = { copyDiagnostics() },
                    onClearDiagnostics = { InterceptorStateRepository.clearDiag() },
                    onAcknowledgeHuaweiAppLaunch = {
                        InterceptorStateRepository.setHuaweiAppLaunchAcknowledged(this, true)
                    },
                    diagEvents = diagEvents,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (intent?.getBooleanExtra("trigger_cts", false) == true) {
            CircleToSearch.trigger(this)
        }
    }

    override fun onResume() {
        super.onResume()
        InterceptorStateRepository.updateEnabledStatus(this)
    }

    private fun copyDiagnostics() {
        val events = InterceptorStateRepository.diagSnapshot()
        val state = InterceptorStateRepository.serviceState.value
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

        // Always include the device/assistant header: the log alone is not enough to diagnose a
        // report coming from someone else's phone.
        val header = buildString {
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
            appendLine(
                "os=${state.detectedOsLabel} vendor=${state.detectedVendorId.ifEmpty { "-" }}",
            )
            appendLine("blueLM=${state.blueLMAction} ${state.blueLMSpecificPackage ?: ""}")
            appendLine("camera=${state.cameraAction} ${state.cameraSpecificPackage ?: ""}")
            appendLine("cameraKeys=${state.cameraKeyCodes.sorted()} skipCameraApp=${state.skipCameraApp}")
            appendLine(
                "volumeSkip=${state.volumeSkipTracksEnabled} " +
                    "volumeShort=${state.volumeShortRemapEnabled} " +
                    "timeout=${state.volumeLongPressMs} haptic=${state.volumeHapticEnabled}",
            )
            appendLine(
                "volumeUpShort=${VolumeShortAction.toStoredName(state.volumeUpShortAction)} " +
                    "${VolumeShortAction.toStoredPackage(state.volumeUpShortAction) ?: ""}",
            )
            appendLine(
                "volumeDownShort=${VolumeShortAction.toStoredName(state.volumeDownShortAction)} " +
                    "${VolumeShortAction.toStoredPackage(state.volumeDownShortAction) ?: ""}",
            )
            ActionExecutionEngine.assistantResolutionReport(this@MainActivity).forEach { (k, v) ->
                appendLine("$k = $v")
            }
        }
        val text = header + "---\n" + events.joinToString("\n") { event ->
            "${sdf.format(Date(event.timeMs))} ${event.kind} ${event.detail}"
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("bluelm diagnostics", text))
        Toast.makeText(
            this,
            getString(R.string.toast_copied_report, events.size),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.toast_a11y_settings_failed, e.message ?: ""),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun testSelectedAction() {
        val state = InterceptorStateRepository.serviceState.value
        val action = state.blueLMAction ?: return
        TargetActionFire.forService(this).fire(action, state.blueLMSpecificPackage)
    }
}
