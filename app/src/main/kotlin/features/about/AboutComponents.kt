package features.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.R
import app.ProjectInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val ProjectSourceUri = "https://github.com/Asterisk4Magisk/AsteriskNG"
private const val TelegramChannelUri = "https://t.me/AsteriskFactory"

@Composable
internal fun AboutHeader(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 20.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = ProjectInfo.PROJECT_NAME,
            modifier = Modifier.size(88.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = ProjectInfo.PROJECT_NAME,
            fontSize = MiuixTheme.textStyles.title2.fontSize,
            color = MiuixTheme.colorScheme.onBackground,
        )
        Text(
            text = "v${ProjectInfo.VERSION_NAME} (${ProjectInfo.VERSION_CODE})",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
internal fun AboutRuntimeCard(
    modifier: Modifier = Modifier,
) {
    SmallTitle(text = stringResource(R.string.about_runtime))
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        BasicComponent(
            title = stringResource(R.string.about_core),
            summary = "AndroidLibXrayLite ${ProjectInfo.ANDROID_LIB_XRAY_LITE_VERSION} / xray-core ${ProjectInfo.XRAY_CORE_VERSION}",
        )
    }
}

@Composable
internal fun AboutLinksCard(
    title: String,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    SmallTitle(text = title)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        ArrowPreference(
            title = stringResource(R.string.about_view_source),
            onClick = { uriHandler.openUri(ProjectSourceUri) },
        )
        ArrowPreference(
            title = stringResource(R.string.about_join_telegram),
            onClick = { uriHandler.openUri(TelegramChannelUri) },
        )
    }
}
