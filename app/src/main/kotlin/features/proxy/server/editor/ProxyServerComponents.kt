// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.R
import features.proxy.server.model.ChainProxy
import features.proxy.server.model.StrategyGroup
import features.proxy.server.model.StrategyGroupConstants
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import ui.text.formatTemplate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal fun CharSequence.isDigitsOnly(): Boolean {
    if (isEmpty()) return true
    return all { char -> char.isDigit() }
}

internal data class ProxyServerEditorGroupOption(
    val id: Int?,
    val label: String,
)

internal data class ProxyServerEditorMemberOption(
    val id: Int,
    val label: String,
)

internal fun LazyListScope.strategyGroupProxyServer(
    strategyGroupEdit: StrategyGroup,
    groupOptions: List<ProxyServerEditorGroupOption>,
) {
    item(key = "properties") {
        val focusManager = LocalFocusManager.current
        val strategyValues = remember {
            listOf(
                StrategyGroupConstants.TYPE_LEAST_PING,
                StrategyGroupConstants.TYPE_LEAST_LOAD,
                StrategyGroupConstants.TYPE_RANDOM,
                StrategyGroupConstants.TYPE_ROUND_ROBIN,
            )
        }
        val strategyLabels = listOf(
            stringResource(R.string.proxy_editor_strategy_group_least_ping),
            stringResource(R.string.proxy_editor_strategy_group_least_load),
            stringResource(R.string.proxy_editor_strategy_group_random),
            stringResource(R.string.proxy_editor_strategy_group_round_robin),
        )
        val effectiveGroupOptions = groupOptions.ifEmpty {
            listOf(ProxyServerEditorGroupOption(null, stringResource(R.string.proxy_editor_strategy_group_all_groups)))
        }
        val strategyIndex = remember {
            mutableIntStateOf(strategyValues.indexOf(strategyGroupEdit.strategy).coerceAtLeast(0))
        }
        val groupIndex = remember {
            mutableIntStateOf(
                effectiveGroupOptions
                    .indexOfFirst { option -> option.id == strategyGroupEdit.subscriptionGroupId }
                    .coerceAtLeast(0),
            )
        }

        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        TextField(
            label = stringResource(R.string.proxy_editor_remarks),
            state = rememberTextFieldState(initialText = strategyGroupEdit.remarks),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                strategyGroupEdit.remarks = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.proxy_editor_strategy_group_type),
            items = strategyLabels,
            selectedIndex = strategyIndex.intValue,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onSelectedIndexChange = { index ->
                strategyIndex.intValue = index
                strategyGroupEdit.strategy = strategyValues[index]
            },
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.proxy_editor_strategy_group_source_group),
            items = effectiveGroupOptions.map { option -> option.label },
            selectedIndex = groupIndex.intValue,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onSelectedIndexChange = { index ->
                groupIndex.intValue = index
                strategyGroupEdit.subscriptionGroupId = effectiveGroupOptions[index].id
            },
        )
        TextField(
            label = stringResource(R.string.proxy_editor_strategy_group_filter),
            state = rememberTextFieldState(initialText = strategyGroupEdit.filter),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                strategyGroupEdit.filter = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
    }
}
internal fun LazyListScope.chainProxyServer(
    chainProxyEdit: ChainProxy,
    memberOptions: List<ProxyServerEditorMemberOption>,
) {
    item(key = "properties") {
        val focusManager = LocalFocusManager.current
        var members by remember(chainProxyEdit) {
            mutableStateOf(chainProxyEdit.proxyServerIds.ifEmpty { listOf(0, 0) })
        }
        val unselectedMember = ProxyServerEditorMemberOption(
            id = 0,
            label = stringResource(R.string.proxy_editor_chain_member_unselected),
        )
        val effectiveMemberOptions = listOf(unselectedMember) + memberOptions

        fun updateMembers(nextMembers: List<Int>) {
            members = nextMembers
            chainProxyEdit.proxyServerIds = nextMembers.filter { id -> id != unselectedMember.id }
        }

        SmallTitle(text = stringResource(R.string.proxy_editor_properties))
        TextField(
            label = stringResource(R.string.proxy_editor_remarks),
            state = rememberTextFieldState(initialText = chainProxyEdit.remarks),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation {
                chainProxyEdit.remarks = asCharSequence().toString()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            onKeyboardAction = { focusManager.clearFocus() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        SmallTitle(text = stringResource(R.string.proxy_editor_chain_members))
        members.forEachIndexed { index, memberId ->
            val selectedIndex = effectiveMemberOptions
                .indexOfFirst { option -> option.id == memberId }
                .coerceAtLeast(0)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.proxy_editor_chain_member)
                        .formatTemplate("index" to index + 1),
                    items = effectiveMemberOptions.map { option -> option.label },
                    selectedIndex = selectedIndex,
                    modifier = Modifier.weight(1f),
                    onSelectedIndexChange = { optionIndex ->
                        updateMembers(members.toMutableList().also { it[index] = effectiveMemberOptions[optionIndex].id })
                    },
                )
                IconButton(
                    modifier = Modifier.size(48.dp),
                    onClick = {
                        if (members.size > 1) {
                            updateMembers(members.filterIndexed { memberIndex, _ -> memberIndex != index })
                        }
                    },
                ) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            TextButton(
                text = stringResource(R.string.proxy_editor_chain_add_member),
                onClick = {
                    updateMembers(members + unselectedMember.id)
                },
            )
        }
    }
}
