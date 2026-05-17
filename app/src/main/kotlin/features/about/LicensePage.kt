// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(ExperimentalScrollBarApi::class)

package features.about

import app.LocalIsWideScreen
import app.LocalNavigator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ui.components.BackNavigationIcon
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import ui.layout.AdaptiveTopAppBar
import features.about.license.Library
import features.about.license.decodeAboutLibraries
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageScrollModifiers
import app.R
import androidx.compose.runtime.getValue
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi

@Composable
fun LicensePage(
    padding: PaddingValues,
) {
    val isWideScreen = LocalIsWideScreen.current
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val navigator = LocalNavigator.current
    val appContext = LocalContext.current.applicationContext

    val libraries by produceState<List<Library>?>(initialValue = null, appContext) {
        try {
            val jsonString = appContext.assets
                .open("aboutlibraries.json")
                .bufferedReader()
                .use { reader -> reader.readText() }
            val libs = decodeAboutLibraries(jsonString)
            value = libs.libraries
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = stringResource(R.string.license_title),
                isWideScreen = isWideScreen,
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    BackNavigationIcon(
                        onClick = { navigator.pop() },
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
        Box {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.pageScrollModifiers(
                    topAppBarScrollBehavior,
                ),
                contentPadding = contentPadding,
            ) {
                libraries?.let { libs ->
                    items(libs, key = { it.uniqueId }) { library ->
                        LibraryLicenseCard(library)
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(12.dp))
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
