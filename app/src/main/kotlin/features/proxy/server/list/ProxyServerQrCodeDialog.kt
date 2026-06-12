// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.list

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import utils.generateQrCodeImageBitmap

@Composable
internal fun ProxyServerQrCodeDialog(
    title: String,
    text: String,
    onDismissRequest: () -> Unit,
) {
    val qrCode = remember(text) {
        runCatching { generateQrCodeImageBitmap(text, QrCodeBitmapSizePx) }.getOrNull()
    }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth(),
                insideMargin = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.fillMaxWidth(),
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    if (qrCode == null) {
                        Text(
                            text = stringResource(R.string.proxy_server_qr_generate_failed),
                            modifier = Modifier.fillMaxWidth(),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Image(
                            bitmap = qrCode,
                            contentDescription = title,
                            modifier = Modifier
                                .size(288.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val QrCodeBitmapSizePx = 768
