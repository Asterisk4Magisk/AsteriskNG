@file:OptIn(ExperimentalScrollBarApi::class)

package features.resources

import app.ResourceFileKind
import app.ResourceFilesStatus
import app.AppState
import app.LocalAppStateStore
import app.LocalAppServices
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.modes.RunModeVpnService
import app.collectAppState
import app.resourceFileUpdateSourceAt
import app.statusOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.R
import ui.components.BackNavigationIcon
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import ui.layout.AdaptiveTopAppBar
import ui.text.formatTemplate
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.layout.pageScrollModifiers
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi

@Composable
fun ResourceManagementPage(
    padding: PaddingValues,
) {
    val isWideScreen = LocalIsWideScreen.current
    val navigator = LocalNavigator.current
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val services = LocalAppServices.current
    val resourceFileUseCase = services.resourceFileUseCase
    val sourceOptions = settingsResourceFileSourceOptions()
    val tipNotifier = services.tipNotifier
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(ResourceFilesStatus()) }
    var updating by remember { mutableStateOf(false) }
    val updatingMessage = stringResource(R.string.settings_resource_files_updating)
    val updatedMessage = stringResource(R.string.settings_resource_files_updated)
    val replacedMessage = stringResource(R.string.settings_resource_files_replaced)
    val restoredMessage = stringResource(R.string.settings_resource_files_restored)

    fun runResourceFileAction(action: suspend () -> ResourceFilesStatus?, successMessage: String?) {
        scope.launch {
            updating = true
            runCatching { action() }
                .onSuccess { nextStatus ->
                    nextStatus?.let {
                        status = it
                        successMessage?.let { message -> tipNotifier.show(message) }
                    }
                }
                .onFailure { error ->
                    tipNotifier.showError(error)
                }
            updating = false
        }
    }

    LaunchedEffect(Unit) {
        status = resourceFileUseCase.status()
    }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = stringResource(R.string.settings_resource_management),
                isWideScreen = isWideScreen,
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    BackNavigationIcon(onClick = { navigator.pop() })
                },
            )
        },
    ) { innerPadding ->
        val lazyListState = rememberLazyListState()
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        val listPadding = pageListPadding(contentPadding)

        Box {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.pageScrollModifiers(topAppBarScrollBehavior),
                contentPadding = listPadding,
            ) {
                item(key = "resource_files_core_title") {
                    SmallTitle(text = stringResource(R.string.settings_resource_files_core_files))
                }
                item(key = ResourceFileKind.XrayCore.fileName) {
                    val kind = ResourceFileKind.XrayCore
                    ResourceFileCard(
                        fileName = kind.displayName,
                        status = status.statusOf(kind),
                        updating = updating,
                        description = stringResource(R.string.settings_resource_files_tproxy_only),
                        onReplace = {
                            runResourceFileAction(
                                action = { resourceFileUseCase.replace(kind) },
                                successMessage = replacedMessage.formatTemplate("name" to kind.displayName),
                            )
                        },
                        onRestore = {
                            runResourceFileAction(
                                action = { resourceFileUseCase.restoreBundled(kind) },
                                successMessage = restoredMessage.formatTemplate("name" to kind.displayName),
                            )
                        },
                    )
                }
                item(key = "resource_files_title") {
                    SmallTitle(text = stringResource(R.string.settings_resource_files_files))
                }
                item(key = "resource_files_source") {
                    ResourceFileSourceCard(
                        sourceOptions = sourceOptions,
                        selectedSource = appState.resourceFileSource,
                        updating = updating,
                        onSourceChange = { index ->
                            updateAppState { state -> state.copy(resourceFileSource = index.coerceIn(sourceOptions.indices)) }
                        },
                        onUpdate = {
                            runResourceFileAction(
                                action = {
                                    tipNotifier.show(updatingMessage)
                                    resourceFileUseCase.update(
                                        source = resourceFileUpdateSourceAt(appState.resourceFileSource),
                                        options = appState.resourceFileUpdateOptions(),
                                    )
                                },
                                successMessage = updatedMessage,
                            )
                        },
                    )
                }
                listOf(ResourceFileKind.GeoIp, ResourceFileKind.GeoSite).forEach { kind ->
                    item(key = kind.fileName) {
                        ResourceFileCard(
                            fileName = kind.displayName,
                            status = status.statusOf(kind),
                            updating = updating,
                            onReplace = {
                                runResourceFileAction(
                                    action = { resourceFileUseCase.replace(kind) },
                                    successMessage = replacedMessage.formatTemplate("name" to kind.displayName),
                                )
                            },
                            onRestore = {
                                runResourceFileAction(
                                    action = { resourceFileUseCase.restoreBundled(kind) },
                                    successMessage = restoredMessage.formatTemplate("name" to kind.displayName),
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
        }
    }
}

private fun AppState.resourceFileUpdateOptions(): ResourceFileUpdateOptions {
    return ResourceFileUpdateOptions(
        useRunningProxy = proxyRunning && runMode == RunModeVpnService,
        fallbackProxyPort = localProxyPort.toIntOrNull(),
        fallbackProxyUsername = localProxyUsername,
        fallbackProxyPassword = localProxyPassword,
    )
}
