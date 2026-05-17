package features.resources

import app.ResourceFileStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.R
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Replace
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import ui.text.formatTemplate

@Composable
internal fun settingsResourceFileSourceOptions() = listOf(
    stringResource(R.string.settings_resource_files_source_loyalsoldier_github),
    stringResource(R.string.settings_resource_files_source_v2fly_github),
    stringResource(R.string.settings_resource_files_source_chocolate4u_github),
)

@Composable
internal fun ResourceFileSourceCard(
    sourceOptions: List<String>,
    selectedSource: Int,
    updating: Boolean,
    onSourceChange: (Int) -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_resource_files_source),
            items = sourceOptions,
            selectedIndex = selectedSource.coerceIn(sourceOptions.indices),
            onSelectedIndexChange = onSourceChange,
        )
        ArrowPreference(
            title = stringResource(R.string.settings_resource_files_update),
            onClick = onUpdate,
            enabled = !updating,
        )
    }
}

@Composable
internal fun ResourceFileCard(
    fileName: String,
    status: ResourceFileStatus,
    updating: Boolean,
    onReplace: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!description.isNullOrBlank()) {
                        Text(
                            text = description,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = status.summaryText(),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                ResourceFileStatusChip(
                    text = if (status.exists) {
                        stringResource(R.string.settings_resource_files_ready)
                    } else {
                        stringResource(R.string.settings_resource_files_missing)
                    },
                    ready = status.exists,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    enabled = !updating,
                    onClick = onReplace,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Replace,
                        contentDescription = stringResource(R.string.common_replace),
                        tint = if (updating) {
                            MiuixTheme.colorScheme.disabledOnSecondaryVariant
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                    )
                }
                IconButton(
                    enabled = !updating,
                    onClick = onRestore,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Reset,
                        contentDescription = stringResource(R.string.common_restore),
                        tint = if (updating) {
                            MiuixTheme.colorScheme.disabledOnSecondaryVariant
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResourceFileStatusChip(
    text: String,
    ready: Boolean,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (ready) {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MiuixTheme.colorScheme.error.copy(alpha = 0.12f)
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (ready) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ResourceFileStatus.summaryText(): String {
    return if (exists) {
        stringResource(R.string.settings_resource_files_size).formatTemplate("size" to sizeBytes.toReadableSize())
    } else {
        stringResource(R.string.settings_resource_files_missing)
    }
}

private fun Long.toReadableSize(): String {
    if (this < 1024) return "$this B"
    val kib = this / 1024.0
    if (kib < 1024) return "${kib.formatOneDecimal()} KiB"
    val mib = kib / 1024.0
    return "${mib.formatOneDecimal()} MiB"
}

private fun Double.formatOneDecimal(): String {
    val rounded = kotlin.math.round(this * 10.0) / 10.0
    val text = rounded.toString()
    return text.removeSuffix(".0")
}
