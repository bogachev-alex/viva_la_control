package viva.la.circle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import viva.la.circle.R
import viva.la.circle.model.TargetAction
import viva.la.circle.ui.theme.AppIcons

/**
 * One-line "trigger → TargetAction" row that opens the full [ActionSelectorList] in a dialog.
 *
 * The inline list is ~50 semantics nodes per trigger. With an accessibility service enabled
 * (always, for this app) Compose re-walks every node on screen each scroll frame, so five inline
 * lists on one screen dropped frames. The dialog only exists while open.
 */
@Composable
fun ActionPickerRow(
    label: String,
    selectedAction: TargetAction?,
    selectedSpecificPkg: String?,
    onSelectAction: (TargetAction) -> Unit,
    onOpenAppPicker: () -> Unit,
    availableActions: List<TargetAction>,
    modifier: Modifier = Modifier,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    val valueText = when {
        selectedAction == null -> stringResource(R.string.menu_not_set)
        selectedAction == TargetAction.SPECIFIC_APP && !selectedSpecificPkg.isNullOrEmpty() ->
            rememberAppLabel(selectedSpecificPkg)
        else -> stringResource(selectedAction.titleRes)
    }

    OutlinedCard(
        onClick = { dialogOpen = true },
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = selectedAction?.let { getActionIcon(it) } ?: AppIcons.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(text = label, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ActionSelectorList(
                        selectedAction = selectedAction,
                        selectedSpecificPkg = selectedSpecificPkg,
                        onSelectAction = { action ->
                            onSelectAction(action)
                            // Specific App needs the app picker next; every other pick is final.
                            if (action != TargetAction.SPECIFIC_APP) dialogOpen = false
                        },
                        onOpenAppPicker = {
                            dialogOpen = false
                            onOpenAppPicker()
                        },
                        availableActions = availableActions,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(R.string.done))
                }
            },
        )
    }
}
