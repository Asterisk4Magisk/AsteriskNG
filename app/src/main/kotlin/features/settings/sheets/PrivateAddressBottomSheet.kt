package features.settings.sheets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.R
import ui.components.StringListEditor
import engine.network.isCidrAddress
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import ui.text.formatTemplate

@Composable
internal fun privateAddressCidrsSummary(cidrs: List<String>): String {
    val sanitized = cidrs.sanitizePrivateAddressCidrs()
    if (sanitized.isEmpty()) {
        return stringResource(R.string.settings_private_addresses_none)
    }
    return stringResource(R.string.settings_private_addresses_selected)
        .formatTemplate("count" to sanitized.size)
}

internal fun List<String>.sanitizePrivateAddressCidrs(): List<String> {
    return map(String::trim)
        .filter(::isCidrAddress)
        .distinct()
}

@Composable
internal fun PrivateAddressBottomSheet(
    show: Boolean,
    selectedCidrs: List<String>,
    onSelectedCidrsChange: (List<String>) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val sanitizedCidrs = selectedCidrs.sanitizePrivateAddressCidrs()
    val invalidMessage = stringResource(R.string.settings_private_addresses_invalid)

    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.settings_private_addresses),
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismissRequest,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                onClick = { onSave(sanitizedCidrs) },
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            item {
                StringListEditor(
                    editorKey = "private-addresses:$show",
                    title = stringResource(R.string.settings_private_addresses_input),
                    description = stringResource(R.string.settings_private_addresses_summary),
                    inputLabel = stringResource(R.string.settings_private_addresses_input),
                    values = sanitizedCidrs,
                    onValuesChange = { onSelectedCidrsChange(it.sanitizePrivateAddressCidrs()) },
                    emptyText = stringResource(R.string.settings_private_addresses_empty),
                    duplicateText = stringResource(R.string.settings_private_addresses_duplicate),
                    validateInput = { if (isCidrAddress(it)) null else invalidMessage },
                )
            }
        }
    }
}
