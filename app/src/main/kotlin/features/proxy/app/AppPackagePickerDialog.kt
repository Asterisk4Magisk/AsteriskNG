// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.LocalAppServices
import app.LocalIsWideScreen
import app.R
import features.proxy.app.model.AppPackageEntry
import features.proxy.app.model.loadProxyAppListPackages
import features.proxy.app.model.name
import kotlinx.coroutines.CancellationException
import system.ANDROID_APP_ICON_SIZE_DP
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.theme.MiuixTheme
import ui.components.BackNavigationIcon
import ui.components.IconDropdownMenu
import ui.components.IconDropdownMenuEntry
import ui.components.NavigationIcon
import ui.layout.AdaptiveTopAppBar
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.layout.pageScrollModifiers

/**
 * Header action that lets a [ui.components.StringListEditor] holding Android package names be
 * maintained by checking installed apps. Values that are not installed packages stay untouched.
 */
@Composable
internal fun AppPackagePickerAction(
    values: List<String>,
    onValuesChange: (List<String>) -> Unit,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    IconButton(
        modifier = Modifier.size(38.dp),
        onClick = { showPicker = true },
    ) {
        Icon(
            modifier = Modifier.size(20.dp),
            imageVector = MiuixIcons.GridView,
            contentDescription = stringResource(R.string.proxy_app_list_pick_installed_apps),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
    if (showPicker) {
        AppPackagePickerDialog(
            initialValues = values,
            onDismissRequest = { showPicker = false },
            onConfirm = { nextValues ->
                showPicker = false
                onValuesChange(nextValues)
            },
        )
    }
}

@OptIn(ExperimentalScrollBarApi::class)
@Composable
internal fun AppPackagePickerDialog(
    initialValues: List<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val services = LocalAppServices.current
    val packageCatalog = services.packageCatalog
    val tipNotifier = services.tipNotifier
    val isWideScreen = LocalIsWideScreen.current
    val title = stringResource(R.string.proxy_app_list_picker_title)
    val loadFailedMessage = stringResource(R.string.proxy_app_list_load_failed)
    var selectedPackages by rememberSaveable { mutableStateOf(initialValues.distinct()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppPackageEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val iconSizePx = with(LocalDensity.current) { ANDROID_APP_ICON_SIZE_DP.dp.roundToPx() }

    LaunchedEffect(showSystemApps) {
        loading = true
        val selectedOnLoad = selectedPackages.toSet()
        try {
            apps = packageCatalog.loadProxyAppListPackages(
                showSystemApps = showSystemApps,
                currentUserOnly = true,
                excludedPackageName = "",
            )
                .distinctBy(AppPackageEntry::packageName)
                .sortedBy { app -> app.packageName !in selectedOnLoad }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            tipNotifier.showError(error, loadFailedMessage)
            apps = emptyList()
        } finally {
            loading = false
        }
    }

    val visibleApps = remember(apps, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            apps
        } else {
            apps.filter { app ->
                app.name.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val topAppBarScrollBehavior = MiuixScrollBehavior()
        Scaffold(
            topBar = {
                AdaptiveTopAppBar(
                    title = title,
                    isWideScreen = isWideScreen,
                    scrollBehavior = topAppBarScrollBehavior,
                    navigationIcon = {
                        BackNavigationIcon(onClick = onDismissRequest)
                    },
                    actions = {
                        IconDropdownMenu(
                            imageVector = MiuixIcons.More,
                            contentDescription = stringResource(R.string.proxy_app_list_more_actions),
                            entries = listOf(
                                IconDropdownMenuEntry(
                                    key = "show-system-apps",
                                    title = stringResource(R.string.proxy_app_list_show_system_apps),
                                    selected = showSystemApps,
                                    action = Unit,
                                ),
                            ),
                            onAction = { showSystemApps = !showSystemApps },
                        )
                        NavigationIcon(
                            onClick = { onConfirm(selectedPackages) },
                            imageVector = MiuixIcons.Ok,
                            contentDescription = stringResource(R.string.common_save),
                        )
                    },
                    bottomContent = {
                        ProxyAppListSearchBar(
                            searchValue = searchQuery,
                            onSearchValueChange = { searchQuery = it },
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        )
                    },
                )
            },
        ) { innerPadding ->
            val contentPadding = pageContentPaddingWithCutout(
                innerPadding = innerPadding,
                outerPadding = PaddingValues(),
                isWideScreen = isWideScreen,
            )
            val listPadding = pageListPadding(contentPadding)
            val lazyListState = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.pageScrollModifiers(topAppBarScrollBehavior),
                    contentPadding = listPadding,
                ) {
                    if (visibleApps.isEmpty() && !loading) {
                        item(key = "app_empty", contentType = "empty") {
                            ProxyAppListEmptyState()
                        }
                    } else {
                        items(
                            items = visibleApps,
                            key = { app -> app.packageName },
                            contentType = { "app" },
                        ) { app ->
                            ProxyAppListItemCard(
                                app = app,
                                checked = app.packageName in selectedPackages,
                                enabled = true,
                                sharedUid = false,
                                iconSizePx = iconSizePx,
                                onCheckedChange = { checked ->
                                    selectedPackages = togglePickedPackage(
                                        selectedPackages,
                                        app.packageName,
                                        checked,
                                    )
                                },
                            )
                        }
                    }
                }
                VerticalScrollBar(
                    adapter = rememberScrollBarAdapter(lazyListState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    trackPadding = contentPadding,
                )
                if (loading && apps.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(listPadding)
                            .background(MiuixTheme.colorScheme.surface),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        InfiniteProgressIndicator(
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            size = 28.dp,
                            strokeWidth = 2.dp,
                            orbitingDotSize = 2.dp,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.proxy_app_list_loading),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Checking appends the package and unchecking removes it; every other value keeps its position,
 * so manually entered values (such as UIDs, paths or uninstalled packages) survive a picker round trip.
 */
internal fun togglePickedPackage(
    selected: List<String>,
    packageName: String,
    checked: Boolean,
): List<String> = when {
    checked && packageName !in selected -> selected + packageName
    !checked -> selected - packageName
    else -> selected
}
