// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.R
import data.backup.AppBackupRestorePreview
import data.backup.AppBackupWarning
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import system.AndroidLauncherIcon
import ui.components.WarningConfirmDialog
import ui.text.formatTemplate
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SettingsRestoreConfirmDialog(
    preview: AppBackupRestorePreview?,
    onDismissRequest: () -> Unit,
    onRestore: () -> Unit,
) {
    if (preview == null) return

    WarningConfirmDialog(
        show = true,
        title = stringResource(R.string.settings_restore_confirm_title),
        summary = stringResource(R.string.settings_restore_confirm_summary),
        dismissText = stringResource(R.string.common_cancel),
        confirmText = stringResource(R.string.common_restore),
        onDismissRequest = onDismissRequest,
        onConfirm = onRestore,
        detailsMaxHeight = 220.dp,
    ) {
        val warningColor = MiuixTheme.colorScheme.error
        RestoreInfoText(backupVersionText(preview))
        RestoreInfoText(backupCreatedAtText(preview))
        val showLauncherIconWarning = preview.restoredState.hideLauncherIcon && AndroidLauncherIcon.isSupported
        RestoreInfoText(
            backupCountsText(preview),
            bottomPadding = if (preview.warnings.isEmpty() && !showLauncherIconWarning) 0.dp else 12.dp,
        )
        preview.warnings.forEachIndexed { index, warning ->
            RestoreInfoText(
                text = warningText(warning),
                color = warningColor,
                bottomPadding = if (index == preview.warnings.lastIndex && !showLauncherIconWarning) 0.dp else 10.dp,
            )
        }
        if (showLauncherIconWarning) {
            RestoreInfoText(
                text = stringResource(R.string.settings_hide_launcher_icon_confirm, AndroidLauncherIcon.DIAL_CODE),
                color = warningColor,
                bottomPadding = 0.dp,
            )
        }
    }
}

@Composable
private fun RestoreInfoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.onBackground,
    bottomPadding: Dp = 10.dp,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(bottom = bottomPadding),
        style = MiuixTheme.textStyles.body2,
        color = color,
    )
}

@Composable
private fun backupVersionText(preview: AppBackupRestorePreview): String {
    return stringResource(R.string.settings_restore_backup_version).formatTemplate(
        "version" to preview.backup.version,
        "appVersion" to preview.backup.appVersionName.ifBlank { "-" },
    )
}

@Composable
private fun backupCreatedAtText(preview: AppBackupRestorePreview): String {
    val createdAt = preview.backup.createdAtMillis
        .takeIf { value -> value > 0L }
        ?.let { value -> DateFormat.getDateTimeInstance().format(Date(value)) }
        ?: "-"
    return stringResource(R.string.settings_restore_backup_created_at).formatTemplate(
        "time" to createdAt,
    )
}

@Composable
private fun backupCountsText(preview: AppBackupRestorePreview): String {
    return stringResource(R.string.settings_restore_backup_counts).formatTemplate(
        "groups" to preview.subscriptionGroupCount,
        "servers" to preview.proxyServerCount,
        "rules" to preview.routeRuleCount,
    )
}

@Composable
private fun warningText(warning: AppBackupWarning): String {
    return when (warning) {
        is AppBackupWarning.MissingChainProxyMembers -> {
            stringResource(R.string.settings_restore_warning_missing_chain_members).formatTemplate(
                "count" to warning.count,
            )
        }
    }
}
