// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.R
import app.ServiceControlSettings
import app.ServiceControlWifiRule
import app.ServiceControlWifiRuleKind
import features.settings.servicecontrol.ServiceCronParseResult
import features.settings.servicecontrol.canSaveServiceControlDraft
import features.settings.servicecontrol.isValidServiceSsid
import features.settings.servicecontrol.normalizeBssidOrNull
import features.settings.servicecontrol.parseServiceCron
import features.settings.servicecontrol.setWifiRuleEnabled
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import ui.components.StringListEditor

@Composable
internal fun ServiceControlBottomSheet(
    show: Boolean,
    saving: Boolean,
    draft: ServiceControlSettings,
    runtimeError: String?,
    onDraftChange: (ServiceControlSettings) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (ServiceControlSettings) -> Unit,
) {
    var pendingEditors by remember(show) { mutableStateOf(emptySet<String>()) }
    val scheduleEffective = draft.enabled && draft.schedule.enabled
    val wifiEffective = draft.enabled && draft.wifi.enabled
    val startCronInvalid = scheduleEffective &&
        parseServiceCron(draft.schedule.startCron) is ServiceCronParseResult.Invalid
    val stopCronInvalid = scheduleEffective &&
        parseServiceCron(draft.schedule.stopCron) is ServiceCronParseResult.Invalid
    val canSave = canSaveServiceControlDraft(draft, pendingEditors.isNotEmpty()) && !saving

    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.settings_service_control),
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                enabled = !saving,
                onClick = { if (!saving) onDismissRequest() },
            )
        },
        endAction = {
            TextButton(
                text = if (saving) {
                    stringResource(R.string.settings_service_control_saving)
                } else {
                    stringResource(R.string.common_save)
                },
                enabled = canSave,
                onClick = { if (canSave) onSave(draft) },
            )
        },
        onDismissRequest = { if (!saving) onDismissRequest() },
    ) {
        key(show) {
            SettingsSheetContent {
                ServiceControlSection(stringResource(R.string.settings_service_control_basic)) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_service_control_enable),
                        summary = stringResource(R.string.settings_service_control_enable_summary),
                        checked = draft.enabled,
                        onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.settings_service_control_schedule_enable),
                        checked = draft.schedule.enabled,
                        enabled = draft.enabled,
                        onCheckedChange = {
                            onDraftChange(draft.copy(schedule = draft.schedule.copy(enabled = it)))
                        },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.settings_service_control_wifi_enable),
                        checked = draft.wifi.enabled,
                        enabled = draft.enabled,
                        onCheckedChange = {
                            onDraftChange(draft.copy(wifi = draft.wifi.copy(enabled = it)))
                        },
                    )
                }

                AnimatedVisibility(
                    visible = scheduleEffective,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut(),
                    label = "service-control-schedule",
                ) {
                    ServiceControlSection(stringResource(R.string.settings_service_control_schedule)) {
                        ServiceControlStatusText(stringResource(R.string.settings_service_control_cron_hint))
                        SettingsTextField(
                            value = draft.schedule.startCron,
                            onValueChange = {
                                onDraftChange(draft.copy(schedule = draft.schedule.copy(startCron = it)))
                            },
                            label = stringResource(R.string.settings_service_control_start_cron),
                            errorText = stringResource(R.string.settings_service_control_cron_invalid)
                                .takeIf { startCronInvalid },
                        )
                        SettingsTextField(
                            value = draft.schedule.stopCron,
                            onValueChange = {
                                onDraftChange(draft.copy(schedule = draft.schedule.copy(stopCron = it)))
                            },
                            label = stringResource(R.string.settings_service_control_stop_cron),
                            errorText = stringResource(R.string.settings_service_control_cron_invalid)
                                .takeIf { stopCronInvalid },
                        )
                    }
                }

                AnimatedVisibility(
                    visible = wifiEffective,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut(),
                    label = "service-control-wifi",
                ) {
                    ServiceControlSection(stringResource(R.string.settings_service_control_wifi)) {
                        ServiceControlWifiRuleKind.entries.forEach { kind ->
                            val rule = draft.rule(kind)
                            ServiceControlRuleEditor(
                                kind = kind,
                                rule = rule,
                                onRuleChange = { next -> onDraftChange(draft.withRule(kind, next)) },
                                onEnabledChange = { enabled ->
                                    onDraftChange(setWifiRuleEnabled(draft, kind, enabled))
                                },
                                onPendingChange = { editor, pending ->
                                    pendingEditors = if (pending) {
                                        pendingEditors + editor
                                    } else {
                                        pendingEditors - editor
                                    }
                                },
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = runtimeError != null,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut(),
                    label = "service-control-runtime-error",
                ) {
                    ServiceControlStatusText(runtimeError.orEmpty(), error = true)
                }
                ServiceControlStatusText(stringResource(R.string.settings_service_control_doze_note))
            }
        }
    }
}

@Composable
private fun ServiceControlRuleEditor(
    kind: ServiceControlWifiRuleKind,
    rule: ServiceControlWifiRule,
    onRuleChange: (ServiceControlWifiRule) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPendingChange: (String, Boolean) -> Unit,
) {
    val invalidSsid = stringResource(R.string.settings_service_control_ssid_invalid)
    val invalidBssid = stringResource(R.string.settings_service_control_bssid_invalid)
    SwitchPreference(
        title = stringResource(kind.titleResource()),
        checked = rule.enabled,
        onCheckedChange = onEnabledChange,
    )
    AnimatedVisibility(
        visible = rule.enabled,
        enter = fadeIn() + expandVertically(),
        exit = shrinkVertically() + fadeOut(),
        label = "service-control-rule-${kind.name}",
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            StringListEditor(
                editorKey = "${kind.name}-ssid",
                title = stringResource(R.string.settings_service_control_ssids),
                values = rule.ssids,
                onValuesChange = { onRuleChange(rule.copy(ssids = it.take(MaxWifiIdentifiers))) },
                emptyText = stringResource(R.string.settings_service_control_ssids_empty),
                description = stringResource(R.string.settings_service_control_match_note),
                normalizeInput = { it },
                validateInput = { value -> invalidSsid.takeUnless { isValidServiceSsid(value) } },
                onPendingChange = { onPendingChange("${kind.name}-ssid", it) },
            )
            Spacer(Modifier.height(8.dp))
            StringListEditor(
                editorKey = "${kind.name}-bssid",
                title = stringResource(R.string.settings_service_control_bssids),
                values = rule.bssids,
                onValuesChange = { onRuleChange(rule.copy(bssids = it.take(MaxWifiIdentifiers))) },
                emptyText = stringResource(R.string.settings_service_control_bssids_empty),
                normalizeInput = { value -> normalizeBssidOrNull(value) ?: value.trim().lowercase() },
                validateInput = { value -> invalidBssid.takeIf { normalizeBssidOrNull(value) == null } },
                onPendingChange = { onPendingChange("${kind.name}-bssid", it) },
            )
        }
    }
}

@Composable
private fun ServiceControlSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SmallTitle(text = title)
        content()
    }
}

@Composable
private fun ServiceControlStatusText(text: String, error: Boolean = false) {
    Text(
        text = text,
        color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private fun ServiceControlWifiRuleKind.titleResource(): Int = when (this) {
    ServiceControlWifiRuleKind.ConnectStart -> R.string.settings_service_control_connect_start
    ServiceControlWifiRuleKind.ConnectStop -> R.string.settings_service_control_connect_stop
    ServiceControlWifiRuleKind.DisconnectStart -> R.string.settings_service_control_disconnect_start
    ServiceControlWifiRuleKind.DisconnectStop -> R.string.settings_service_control_disconnect_stop
}

private fun ServiceControlSettings.rule(kind: ServiceControlWifiRuleKind): ServiceControlWifiRule = when (kind) {
    ServiceControlWifiRuleKind.ConnectStart -> wifi.connectStart
    ServiceControlWifiRuleKind.ConnectStop -> wifi.connectStop
    ServiceControlWifiRuleKind.DisconnectStart -> wifi.disconnectStart
    ServiceControlWifiRuleKind.DisconnectStop -> wifi.disconnectStop
}

private fun ServiceControlSettings.withRule(
    kind: ServiceControlWifiRuleKind,
    rule: ServiceControlWifiRule,
): ServiceControlSettings = copy(
    wifi = when (kind) {
        ServiceControlWifiRuleKind.ConnectStart -> wifi.copy(connectStart = rule)
        ServiceControlWifiRuleKind.ConnectStop -> wifi.copy(connectStop = rule)
        ServiceControlWifiRuleKind.DisconnectStart -> wifi.copy(disconnectStart = rule)
        ServiceControlWifiRuleKind.DisconnectStop -> wifi.copy(disconnectStop = rule)
    },
)

private const val MaxWifiIdentifiers = 64
