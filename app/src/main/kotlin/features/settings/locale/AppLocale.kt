// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.locale

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.modes.LanguageModeEnglish
import app.modes.LanguageModeSimplifiedChinese
import java.util.Locale

private val systemLocale: Locale = Locale.getDefault()

private fun languageTagForMode(mode: Int): String? = when (mode) {
    LanguageModeEnglish -> "en"
    LanguageModeSimplifiedChinese -> "zh-CN"
    else -> null
}

@Composable
fun ProvideAppLanguage(
    languageMode: Int,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val languageTag = languageTagForMode(languageMode)
    val locale = remember(languageTag) { languageTag.toAppLocale() }
    val configuration = remember(context, locale) { context.localizedConfiguration(locale) }
    val localizedContext = remember(context, configuration) {
        context.createConfigurationContext(configuration)
    }

    SideEffect {
        if (Locale.getDefault().toLanguageTag() != locale.toLanguageTag()) {
            Locale.setDefault(locale)
        }
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides configuration,
        content = content,
    )
}

private fun String?.toAppLocale(): Locale {
    return this?.let(Locale::forLanguageTag) ?: systemLocale
}

internal fun Context.localizedAppContext(languageMode: Int): Context {
    val locale = languageTagForMode(languageMode).toAppLocale()
    return createConfigurationContext(localizedConfiguration(locale))
}

private fun Context.localizedConfiguration(locale: Locale): Configuration {
    return Configuration(resources.configuration).apply {
        setLocales(LocaleList(locale))
        setLayoutDirection(locale)
    }
}
