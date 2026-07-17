// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(ExperimentalScrollBarApi::class)

package features.logs

import android.content.Context
import android.net.Uri
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalAppServices
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.R
import ui.components.BackNavigationIcon
import ui.components.NavigationIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.ScrollBarColors
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Share
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
    val context = LocalContext.current
    LogViewerPage(
        padding = padding,
        title = stringResource(R.string.core_logs_title),
        repository = services.coreLogRepository,
        onClear = {
            context.clearCoreLogFile(XrayLogFile.Error)
        },
    )
}

@Composable
fun AccessLogsPage(
    padding: PaddingValues,
) {
    val services = LocalAppServices.current
    val context = LocalContext.current
    LogViewerPage(
        padding = padding,
        title = stringResource(R.string.access_logs_title),
        repository = services.accessLogRepository,
        onClear = {
            context.clearCoreLogFile(XrayLogFile.Access)
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
    val context = LocalContext.current
    val services = LocalAppServices.current
    val tipNotifier = services.tipNotifier
    val logFileCreator = services.logFileCreator
    val scope = rememberCoroutineScope()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    var refreshing by remember { mutableStateOf(false) }
    var logEntries by remember(repository) { mutableStateOf(repository.entries.value) }
    val displayedLogEntries = remember(logEntries) { logEntries.asReversed() }
    val copiedMessage = stringResource(R.string.logs_copied_to_clipboard)
    val exportDescription = stringResource(R.string.logs_export)
    val exportedMessage = stringResource(R.string.logs_exported)
    val exportFailedMessage = stringResource(R.string.logs_export_failed)

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
                            val exportEntries = logEntries.toList()
                            scope.launch {
                                val uri = logFileCreator(logExportFileName(title)) ?: return@launch
                                runCatching {
                                    context.exportLogEntries(uri, exportEntries)
                                }.onSuccess {
                                    tipNotifier.show(exportedMessage)
                                }.onFailure { error ->
                                    tipNotifier.showError(error, exportFailedMessage)
                                }
                            }
                        },
                        imageVector = MiuixIcons.Share,
                        contentDescription = exportDescription,
                    )
                    NavigationIcon(
                        onClick = {
                            scope.launch {
                                onClear()
                                repository.clear()
                                logEntries = emptyList()
                            }
                        },
                        imageVector = MiuixIcons.Delete,
                        contentDescription = stringResource(R.string.common_delete),
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
                        thumbWidth = 5.dp,
                        thumbMinLength = 48.dp,
                        colors = ScrollBarColors(
                            thumbColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        ),
                    )
                }
            }
        }
    }
}

private fun CoreLogEntry.copyText(): String {
    return "$time  ${level.uppercase()}  $message"
}

private suspend fun Context.exportLogEntries(
    uri: Uri,
    entries: List<CoreLogEntry>,
) {
    withContext(Dispatchers.IO) {
        val outputStream = contentResolver.openOutputStream(uri) ?: throw IllegalStateException()
        outputStream.writer(Charsets.UTF_8).use { writer ->
            entries.forEachIndexed { index, entry ->
                if (index > 0) writer.write("\n")
                writer.write(entry.copyText())
            }
        }
    }
}

private fun logExportFileName(title: String): String {
    val safeTitle = title
        .trim()
        .map { char -> if (char.isLetterOrDigit()) char else '-' }
        .joinToString("")
        .trim('-')
        .ifBlank { "logs" }
    return "asteriskng-$safeTitle-${System.currentTimeMillis()}.log"
}
