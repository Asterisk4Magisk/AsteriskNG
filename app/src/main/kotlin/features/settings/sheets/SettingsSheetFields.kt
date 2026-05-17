package features.settings.sheets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import engine.network.NetworkLimits
import ui.components.StringListStatusText
import top.yukonga.miuix.kmp.basic.TextField


@Composable
internal fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    errorText: String?,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (errorText == null) 12.dp else 4.dp),
    )
    errorText?.let {
        StringListStatusText(
            text = it,
            error = true,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}


internal fun isPort(value: String): Boolean {
    return value.isNotEmpty() &&
        value.all(Char::isDigit) &&
        value.toIntOrNull()?.let { it in NetworkLimits.PORT_MIN..NetworkLimits.PORT_MAX } == true
}
