// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.locale

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.modes.ColorModeDark
import app.modes.ColorModeLight
import app.modes.LanguageModeEnglish
import app.modes.LanguageModeRussian
import app.modes.LanguageModeSimplifiedChinese
import app.modes.normalizeColorMode
import java.util.Locale

private fun languageTagForMode(mode: Int): String? = when (mode) {
    LanguageModeEnglish -> "en"
    LanguageModeSimplifiedChinese -> "zh-CN"
    LanguageModeRussian -> "ru"
    else -> null
}

@Composable
fun ProvideAppLanguage(
    languageMode: Int,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemLocale = LocalConfiguration.current.primaryLocale()
    val languageTag = languageTagForMode(languageMode)
    val locale = remember(languageTag, systemLocale) { languageTag.toAppLocale(systemLocale) }
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

@Composable
fun RecreateActivityOnAppLanguageChange(languageMode: Int) {
    val activity = LocalContext.current.findActivity()
    var previousLanguageMode by remember { mutableIntStateOf(languageMode) }

    LaunchedEffect(activity, languageMode) {
        if (previousLanguageMode == languageMode) {
            return@LaunchedEffect
        }
        previousLanguageMode = languageMode
        activity?.recreate()
    }
}

private fun String?.toAppLocale(systemLocale: Locale): Locale {
    return this?.let(Locale::forLanguageTag) ?: systemLocale
}

internal fun Context.localizedAppContext(
    languageMode: Int,
    colorMode: Int? = null,
): Context {
    val locale = languageTagForMode(languageMode).toAppLocale(resources.configuration.primaryLocale())
    return createConfigurationContext(localizedConfiguration(locale, colorMode))
}

private fun Configuration.primaryLocale(): Locale {
    return locales[0] ?: Locale.getDefault()
}

private fun Context.localizedConfiguration(
    locale: Locale,
    colorMode: Int? = null,
): Configuration {
    return Configuration(resources.configuration).apply {
        setLocales(LocaleList(locale))
        setLayoutDirection(locale)
        colorMode?.let(::applyAppColorMode)
    }
}

private fun Configuration.applyAppColorMode(colorMode: Int) {
    val nightMode = when (normalizeColorMode(colorMode)) {
        ColorModeLight -> Configuration.UI_MODE_NIGHT_NO
        ColorModeDark -> Configuration.UI_MODE_NIGHT_YES
        else -> return
    }
    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
