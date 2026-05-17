@file:OptIn(ExperimentalScrollBarApi::class)

package features.logs

import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalAppServices
import app.LocalAppStateStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import app.R
import ui.components.BackNavigationIcon
import ui.components.NavigationIcon
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import ui.layout.AdaptiveTopAppBar
import ui.text.formatTemplate
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageScrollModifiers
import ui.clipboard.setPlainText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi

@Composable
fun CoreLogsPage(
    padding: PaddingValues,
) {
    val services = LocalAppServices.current
    val stateStore = LocalAppStateStore.current
    LogViewerPage(
        padding = padding,
        title = stringResource(R.string.core_logs_title),
        repository = services.coreLogRepository,
        onClear = {
            services.coreLogClearUseCase.clear(
                logFile = XrayLogFile.Error,
                runMode = stateStore.state.value.runMode,
            )
        },
    )
}

@Composable
fun AccessLogsPage(
    padding: PaddingValues,
) {
    val services = LocalAppServices.current
    val stateStore = LocalAppStateStore.current
    LogViewerPage(
        padding = padding,
        title = stringResource(R.string.access_logs_title),
        repository = services.accessLogRepository,
        onClear = {
            services.coreLogClearUseCase.clear(
                logFile = XrayLogFile.Access,
                runMode = stateStore.state.value.runMode,
            )
        },
    )
}

@Composable
fun LogcatLogsPage(
    padding: PaddingValues,
) {
    val services = LocalAppServices.current
    LogViewerPage(
        padding = padding,
        title = stringResource(R.string.logcat_logs_title),
        repository = services.logcatRepository,
        showLogMetadata = true,
    )
}

@Composable
private fun LogViewerPage(
    padding: PaddingValues,
    title: String,
    repository: CoreLogRepository,
    showLogMetadata: Boolean = false,
    onClear: suspend () -> Unit = {},
) {
    val isWideScreen = LocalIsWideScreen.current
    val navigator = LocalNavigator.current
    val clipboard = LocalClipboard.current
    val tipNotifier = LocalAppServices.current.tipNotifier
    val scope = rememberCoroutineScope()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    var refreshing by remember { mutableStateOf(false) }
    var logEntries by remember(repository) { mutableStateOf(repository.entries.value) }
    val displayedLogEntries = remember(logEntries) { logEntries.asReversed() }
    val copiedMessage = stringResource(R.string.logs_copied_to_clipboard)

    LaunchedEffect(repository) {
        repository.refresh()
        logEntries = repository.entries.value
    }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = title,
                subtitle = stringResource(R.string.logs_count).formatTemplate("count" to logEntries.size),
                isWideScreen = isWideScreen,
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    BackNavigationIcon(
                        onClick = { navigator.pop() },
                    )
                },
                actions = {
                    NavigationIcon(
                        onClick = {
                            scope.launch {
                                onClear()
                                repository.clear()
                                logEntries = emptyList()
                            }
                        },
                        imageVector = MiuixIcons.Delete,
                    )
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
        val listPadding = PaddingValues(bottom = 12.dp)

        Box {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                SmallTitle(text = stringResource(R.string.logs_output))
                Box(modifier = Modifier.weight(1f)) {
                    PullToRefresh(
                        isRefreshing = refreshing,
                        onRefresh = {
                            if (!refreshing) {
                                scope.launch {
                                    refreshing = true
                                    withFrameNanos { }
                                    try {
                                        repository.refresh()
                                        logEntries = repository.entries.value
                                    } finally {
                                        withFrameNanos { }
                                        refreshing = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = listPadding,
                        topAppBarScrollBehavior = topAppBarScrollBehavior,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        refreshTexts = listOf(
                            stringResource(R.string.logs_pull_to_refresh),
                            stringResource(R.string.logs_release_to_refresh),
                            stringResource(R.string.logs_refreshing),
                            stringResource(R.string.logs_refreshed),
                        ),
                    ) {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.pageScrollModifiers(
                                topAppBarScrollBehavior,
                            ),
                            contentPadding = listPadding,
                        ) {
                            if (displayedLogEntries.isEmpty()) {
                                item(key = "log_empty") {
                                    LogEmptyCard()
                                }
                            } else {
                                items(
                                    items = displayedLogEntries,
                                    key = { it.id },
                                ) { entry ->
                                    LogEntryCard(
                                        entry = entry,
                                        showMetadata = showLogMetadata,
                                        onClick = {
                                            scope.launch {
                                                clipboard.setPlainText(entry.copyText())
                                                tipNotifier.show(copiedMessage)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    VerticalScrollBar(
                        adapter = rememberScrollBarAdapter(lazyListState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        trackPadding = listPadding,
                    )
                }
            }
        }
    }
}

private fun CoreLogEntry.copyText(): String {
    return "$time  ${level.uppercase()}  $message"
}
