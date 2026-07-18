// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(ExperimentalScrollBarApi::class)

package features.resources

import app.ResourceFileKind
import app.ResourceFilesStatus
import app.AppState
import app.CustomResourceFileState
import app.CustomResourceFileStatus
import app.LocalAppStateStore
import app.LocalAppServices
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.collectAppState
import app.customResourceFileNameOrNull
import app.resourceFileUpdateSource
import app.statusOf
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.R
import ui.components.BackNavigationIcon
import ui.components.DeleteConfirmationDialog
import ui.components.NavigationIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import ui.layout.AdaptiveTopAppBar
import androidx.compose.ui.unit.dp
import ui.text.formatTemplate
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.layout.pageScrollModifiers
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import engine.network.toPortOrNull
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import features.subscription.SubscriptionUserAgentSelection
import features.subscription.SubscriptionUserAgentSelections
import features.subscription.DefaultSubscriptionUserAgent
import features.subscription.subscriptionUserAgentSelectionFor
import features.subscription.resolveUserAgent

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
    var status by remember { mutableStateOf(ResourceFilesStatus()) }
    var updating by remember { mutableStateOf(false) }
    val showCustomResourceFileDialog = remember { mutableStateOf(false) }
    var editingCustomResourceFile by remember { mutableStateOf<CustomResourceFileState?>(null) }
    var pendingCustomResourceFileDeletion by remember { mutableStateOf<CustomResourceFileState?>(null) }
    val customResourceFileNameState = rememberTextFieldState()
    val customResourceFileUrlState = rememberTextFieldState()
    val editCustomResourceFileNameState = rememberTextFieldState()
    val editCustomResourceFileUrlState = rememberTextFieldState()
    val updatingMessage = stringResource(R.string.settings_resource_files_updating)
    val updatedMessage = stringResource(R.string.settings_resource_files_updated)
    val updatedOneMessage = stringResource(R.string.settings_resource_file_updated)
    val replacedMessage = stringResource(R.string.settings_resource_files_replaced)
    val restoredMessage = stringResource(R.string.settings_resource_files_restored)
    val deletedMessage = stringResource(R.string.settings_resource_files_deleted)
    val customResourceFileNameInvalidMessage = stringResource(
        R.string.settings_resource_files_custom_name_invalid,
    )
    val customResourceFileNameDuplicateMessage = stringResource(
        R.string.settings_resource_files_custom_name_duplicate,
    )
    var showCustomUserAgentDialog by remember { mutableStateOf(false) }
    val customUserAgentDraftState = rememberTextFieldState()

    fun runResourceFileAction(
        action: suspend () -> ResourceFilesStatus?,
        successMessage: String?,
        failureStatusCustomResourceFiles: (() -> List<CustomResourceFileState>)? = null,
    ) {
        updating = true
        services.appScope.launch {
            try {
                action()?.let {
                    withContext(Dispatchers.Main.immediate) {
                        status = it
                    }
                    successMessage?.let { message -> tipNotifier.show(message) }
                }
            } catch (error: Throwable) {
                failureStatusCustomResourceFiles?.let { customResourceFiles ->
                    runCatching {
                        val failureStatus = resourceFileUseCase.status(customResourceFiles())
                        withContext(Dispatchers.Main.immediate) {
                            status = failureStatus
                        }
                    }
                }
                tipNotifier.showError(error)
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    updating = false
                }
            }
        }
    }

    fun showResourceFileEditorError(message: String) {
        services.appScope.launch {
            tipNotifier.show(message)
        }
    }

    fun updateResourceFile(kind: ResourceFileKind) {
        runResourceFileAction(
            action = {
                tipNotifier.show(updatingMessage)
                resourceFileUseCase.update(
                    kind = kind,
                    source = appState.resourceFileUpdateSource(),
                    options = appState.resourceFileUpdateOptions(),
                    customResourceFiles = appState.customResourceFiles,
                )
            },
            successMessage = updatedOneMessage.formatTemplate("name" to kind.displayName),
            failureStatusCustomResourceFiles = { appState.customResourceFiles },
        )
    }

    fun updateCustomResourceFile(file: CustomResourceFileState) {
        runResourceFileAction(
            action = {
                tipNotifier.show(updatingMessage)
                resourceFileUseCase.updateCustom(
                    customFile = file,
                    options = appState.resourceFileUpdateOptions(),
                    customResourceFiles = appState.customResourceFiles,
                )
            },
            successMessage = updatedOneMessage.formatTemplate("name" to file.name),
            failureStatusCustomResourceFiles = { appState.customResourceFiles },
        )
    }

    fun customResourceFileReservedNames(editingFileId: Int? = null): Set<String> {
        return ResourceFileKind.entries.map { kind -> kind.fileName }.toSet() +
            appState.customResourceFiles
                .filterNot { file -> file.id == editingFileId }
                .map { file -> file.name }
    }

    fun validatedCustomResourceFileName(name: String, reservedNames: Set<String>): String? {
        val fileName = customResourceFileNameOrNull(name)
        if (fileName == null) {
            showResourceFileEditorError(customResourceFileNameInvalidMessage)
            return null
        }
        if (fileName in reservedNames) {
            showResourceFileEditorError(customResourceFileNameDuplicateMessage)
            return null
        }
        return fileName
    }

    fun addCustomResourceFile(name: String, url: String): Boolean {
        val fileName = validatedCustomResourceFileName(
            name = name,
            reservedNames = customResourceFileReservedNames(),
        ) ?: return false
        var addedFile: CustomResourceFileState? = null
        var nextCustomResourceFiles = appState.customResourceFiles
        updateAppState { state ->
            val updateUrl = url.trim()
            val fileId = state.nextCustomResourceFileId
            val nextCustomFile = CustomResourceFileState(
                id = fileId,
                name = fileName,
                url = updateUrl,
            )
            addedFile = nextCustomFile
            nextCustomResourceFiles = state.customResourceFiles + nextCustomFile
            state.copy(
                customResourceFiles = nextCustomResourceFiles,
                nextCustomResourceFileId = fileId + 1,
            )
        }
        addedFile?.takeIf { file -> file.url.isBlank() }?.let { file ->
            runResourceFileAction(
                action = {
                    resourceFileUseCase.replaceCustom(
                        customFile = file,
                        customResourceFiles = nextCustomResourceFiles,
                    )
                },
                successMessage = replacedMessage.formatTemplate("name" to file.name),
            )
        }
        return true
    }

    fun editCustomResourceFile(file: CustomResourceFileState, name: String, url: String): Boolean {
        val fileName = validatedCustomResourceFileName(
            name = name,
            reservedNames = customResourceFileReservedNames(editingFileId = file.id),
        ) ?: return false
        var editedFile: CustomResourceFileState? = null
        var nextCustomResourceFiles = appState.customResourceFiles
        updateAppState { state ->
            val updateUrl = url.trim()
            val nextCustomFile = file.copy(
                name = fileName,
                url = updateUrl,
            )
            editedFile = nextCustomFile
            nextCustomResourceFiles = state.customResourceFiles.map { customFile ->
                if (customFile.id == file.id) nextCustomFile else customFile
            }
            state.copy(customResourceFiles = nextCustomResourceFiles)
        }
        editedFile?.let { nextFile ->
            runResourceFileAction(
                action = {
                    resourceFileUseCase.renameCustom(
                        previousFile = file,
                        customFile = nextFile,
                        customResourceFiles = nextCustomResourceFiles,
                    )
                },
                successMessage = null,
            )
        }
        return true
    }

    fun deleteCustomResourceFile(file: CustomResourceFileState) {
        runResourceFileAction(
            action = {
                var remainingCustomFiles = emptyList<CustomResourceFileState>()
                updateAppState { state ->
                    remainingCustomFiles = state.customResourceFiles.filterNot { it.id == file.id }
                    state.copy(
                        customResourceFiles = remainingCustomFiles,
                    )
                }
                resourceFileUseCase.deleteCustom(file, remainingCustomFiles)
            },
            successMessage = deletedMessage.formatTemplate("name" to file.name),
        )
    }

    fun requestCustomResourceFileDeletion(file: CustomResourceFileState) {
        if (appState.enableDeletionConfirmation) {
            pendingCustomResourceFileDeletion = file
        } else {
            deleteCustomResourceFile(file)
        }
    }

    LaunchedEffect(appState.customResourceFiles) {
        status = resourceFileUseCase.status(appState.customResourceFiles)
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
                actions = {
                    NavigationIcon(
                        onClick = {
                            customResourceFileNameState.clearText()
                            customResourceFileUrlState.clearText()
                            showCustomResourceFileDialog.value = true
                        },
                        imageVector = MiuixIcons.Add,
                        contentDescription = stringResource(R.string.settings_resource_files_add_custom),
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
                        description = stringResource(R.string.settings_resource_files_root_only),
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
                        selectedUpdateSource = appState.resourceFileUpdateSource(),
                        customGeoIpUrl = appState.customResourceFileGeoIpUrl,
                        customGeoSiteUrl = appState.customResourceFileGeoSiteUrl,
                        customGeoIpOnlyCnPrivateUrl = appState.customResourceFileGeoIpOnlyCnPrivateUrl,
                        customDirectCidrIpv4Url = appState.customResourceFileDirectCidrIpv4Url,
                        customDirectCidrIpv6Url = appState.customResourceFileDirectCidrIpv6Url,
                        updating = updating,
                        onSourceChange = { index ->
                            updateAppState { state -> state.copy(resourceFileSource = index.coerceIn(sourceOptions.indices)) }
                        },
                        onCustomSourceChange = {
                                geoIpUrl,
                                geoSiteUrl,
                                geoIpOnlyCnPrivateUrl,
                                directCidrIpv4Url,
                                directCidrIpv6Url,
                            ->
                            updateAppState { state ->
                                state.copy(
                                    customResourceFileGeoIpUrl = geoIpUrl,
                                    customResourceFileGeoSiteUrl = geoSiteUrl,
                                    customResourceFileGeoIpOnlyCnPrivateUrl = geoIpOnlyCnPrivateUrl,
                                    customResourceFileDirectCidrIpv4Url = directCidrIpv4Url,
                                    customResourceFileDirectCidrIpv6Url = directCidrIpv6Url,
                                )
                            }
                        },
                        onUpdate = {
                            runResourceFileAction(
                                action = {
                                    tipNotifier.show(updatingMessage)
                                    resourceFileUseCase.update(
                                        source = appState.resourceFileUpdateSource(),
                                        options = appState.resourceFileUpdateOptions(),
                                        customResourceFiles = appState.customResourceFiles,
                                    )
                                },
                                successMessage = updatedMessage,
                                failureStatusCustomResourceFiles = { appState.customResourceFiles },
                            )
                        },
                    )
                }
                item(key = "resource_files_user_agent") {
                    val userAgentItems = SubscriptionUserAgentSelections.map { selection ->
                        DropdownItem(
                            text = when (selection) {
                                SubscriptionUserAgentSelection.V2rayNg -> "v2rayNG"
                                SubscriptionUserAgentSelection.ClashMeta -> "Clash Meta"
                                SubscriptionUserAgentSelection.FlClashX -> "FlClash X"
                                SubscriptionUserAgentSelection.Custom -> stringResource(R.string.subscription_user_agent_custom)
                            },
                        )
                    }
                    val currentSelection = subscriptionUserAgentSelectionFor(appState.resourceFileUserAgent)
                    val selectedIndex = SubscriptionUserAgentSelections.indexOf(currentSelection)
                        .takeIf { it >= 0 } ?: 0
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        OverlaySpinnerPreference(
                            title = stringResource(R.string.subscription_user_agent),
                            summary = appState.resourceFileUserAgent,
                            items = userAgentItems,
                            selectedIndex = selectedIndex,
                            onSelectedIndexChange = { index ->
                                val selection = SubscriptionUserAgentSelections[index]
                                if (selection == SubscriptionUserAgentSelection.Custom) {
                                    customUserAgentDraftState.setTextAndPlaceCursorAtEnd(
                                        appState.resourceFileUserAgent.ifBlank { DefaultSubscriptionUserAgent },
                                    )
                                    showCustomUserAgentDialog = true
                                } else {
                                    val resolved = selection.resolveUserAgent(customUserAgent = "")
                                    updateAppState { state ->
                                        state.copy(resourceFileUserAgent = resolved)
                                    }
                                }
                            },
                        )
                        CustomResourceFileUserAgentDialog(
                            show = showCustomUserAgentDialog,
                            state = customUserAgentDraftState,
                            onDismissRequest = { showCustomUserAgentDialog = false },
                            onSave = {
                                val custom = customUserAgentDraftState.text.toString().trim()
                                    .ifBlank { DefaultSubscriptionUserAgent }
                                updateAppState { state ->
                                    state.copy(resourceFileUserAgent = custom)
                                }
                                showCustomUserAgentDialog = false
                            },
                        )
                    }
                }
                listOf(
                    ResourceFileKind.GeoIp,
                    ResourceFileKind.GeoSite,
                    ResourceFileKind.GeoIpOnlyCnPrivate,
                    ResourceFileKind.DirectCidrIpv4,
                    ResourceFileKind.DirectCidrIpv6,
                ).forEach { kind ->
                    item(key = kind.fileName) {
                        ResourceFileCard(
                            fileName = kind.displayName,
                            status = status.statusOf(kind),
                            updating = updating,
                            onUpdate = { updateResourceFile(kind) },
                            onReplace = {
                                runResourceFileAction(
                                    action = { resourceFileUseCase.replace(kind, appState.customResourceFiles) },
                                    successMessage = replacedMessage.formatTemplate("name" to kind.displayName),
                                )
                            },
                            onRestore = {
                                runResourceFileAction(
                                    action = { resourceFileUseCase.restoreBundled(kind, appState.customResourceFiles) },
                                    successMessage = restoredMessage.formatTemplate("name" to kind.displayName),
                                )
                            },
                        )
                    }
                }
                appState.customResourceFiles.forEach { customFile ->
                    item(key = "custom_resource_file_${customFile.id}") {
                        CustomResourceFileCard(
                            fileStatus = status.statusOf(customFile),
                            updating = updating,
                            onUpdate = { file -> updateCustomResourceFile(file) },
                            onReplace = { file ->
                                runResourceFileAction(
                                    action = {
                                        resourceFileUseCase.replaceCustom(
                                            customFile = file,
                                            customResourceFiles = appState.customResourceFiles,
                                        )
                                    },
                                    successMessage = replacedMessage.formatTemplate("name" to file.name),
                                )
                            },
                            onEdit = { file ->
                                editCustomResourceFileNameState.setTextAndPlaceCursorAtEnd(file.name)
                                editCustomResourceFileUrlState.setTextAndPlaceCursorAtEnd(file.url)
                                editingCustomResourceFile = file
                            },
                            onDelete = ::requestCustomResourceFileDeletion,
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
        CustomResourceFileEditorDialog(
            show = showCustomResourceFileDialog.value,
            nameState = customResourceFileNameState,
            urlState = customResourceFileUrlState,
            onDismissRequest = { showCustomResourceFileDialog.value = false },
            onSave = ::addCustomResourceFile,
        )
        CustomResourceFileEditorDialog(
            show = editingCustomResourceFile != null,
            nameState = editCustomResourceFileNameState,
            urlState = editCustomResourceFileUrlState,
            onDismissRequest = { editingCustomResourceFile = null },
            onSave = { name, url ->
                editingCustomResourceFile?.let { file -> editCustomResourceFile(file, name, url) } ?: false
            },
        )
        pendingCustomResourceFileDeletion?.let { file ->
            DeleteConfirmationDialog(
                show = true,
                title = stringResource(R.string.deletion_confirmation_delete_resource_file),
                onDismissRequest = { pendingCustomResourceFileDeletion = null },
                onConfirm = {
                    pendingCustomResourceFileDeletion = null
                    deleteCustomResourceFile(file)
                },
            )
        }
    }
}

private fun AppState.resourceFileUpdateOptions(): ResourceFileUpdateOptions {
    return ResourceFileUpdateOptions(
        useRunningProxy = proxyRunning,
        fallbackProxyPort = localProxyPort.toPortOrNull(),
        fallbackProxyUsername = localProxyUsername,
        fallbackProxyPassword = localProxyPassword,
        userAgent = resourceFileUserAgent,
    )
}

private fun ResourceFilesStatus.statusOf(customFile: CustomResourceFileState): CustomResourceFileStatus {
    return customResourceFiles.firstOrNull { fileStatus -> fileStatus.file.id == customFile.id }
        ?: CustomResourceFileStatus(file = customFile)
}
