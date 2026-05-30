// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.about

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import features.about.license.Library

@Composable
internal fun LibraryLicenseCard(
    library: Library,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp),
    ) {
        ArrowPreference(
            title = library.name,
            summary = "${library.artifactVersion}, ${library.licenses.firstOrNull()}",
            onClick = {
                library.website?.let { url -> uriHandler.openUri(url) }
            },
        )
    }
}
