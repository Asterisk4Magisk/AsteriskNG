package features.proxy.server.qr

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import app.R
import features.logs.AndroidAppLogger
import ui.feedback.AndroidToastTipNotifier
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Size
import data.AppSettingsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import features.settings.locale.localizedAppContext
import kotlin.math.min
import kotlin.math.roundToInt

class PortraitQrCaptureActivity : CaptureActivity() {
    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tipNotifier by lazy { AndroidToastTipNotifier(this) }

    override fun attachBaseContext(newBase: Context) {
        val languageMode = AppSettingsPreferences(newBase).load().languageMode
        super.attachBaseContext(newBase.localizedAppContext(languageMode))
    }

    override fun initializeContent(): DecoratedBarcodeView {
        val barcodeView = super.initializeContent()
        val metrics = resources.displayMetrics
        val frameSize = (min(metrics.widthPixels, metrics.heightPixels) * FrameSizeRatio).roundToInt()
        barcodeView.barcodeView.setFramingRectSize(Size(frameSize, frameSize))
        barcodeView.viewFinder.setLaserVisibility(false)
        addImagePickerButton(frameSize)
        return barcodeView
    }

    override fun onDestroy() {
        decodeScope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != ImagePickerRequestCode) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            return
        }

        decodeScope.launch {
            val text = withContext(Dispatchers.Default) {
                runCatching {
                    decodeQrCodeFromImage(this@PortraitQrCaptureActivity, uri)
                }.onFailure { error ->
                    AndroidAppLogger.warn(LogTag, "Failed to decode QR code from selected image: $uri", error)
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                tipNotifier.show(getString(R.string.error_qr_image_decode_failed))
            } else {
                setResult(
                    RESULT_OK,
                    Intent().apply {
                        putExtra(Intents.Scan.RESULT, text)
                        putExtra(Intents.Scan.RESULT_FORMAT, BarcodeFormat.QR_CODE.toString())
                    },
                )
                finish()
            }
        }
    }

    private fun addImagePickerButton(frameSize: Int) {
        val root = findViewById<FrameLayout>(android.R.id.content)
        val button = TextView(this).apply {
            text = getString(R.string.qr_scan_album_button)
            contentDescription = getString(R.string.qr_scan_choose_image)
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = createAlbumButtonBackground()
            elevation = 6.dp().toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                openImagePicker()
            }
        }
        root.addView(
            button,
            FrameLayout.LayoutParams(
                AlbumButtonSizeDp.dp(),
                AlbumButtonSizeDp.dp(),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ),
        )
        root.post {
            val buttonHeight = button.measuredHeight.takeIf { it > 0 } ?: AlbumButtonSizeDp.dp()
            val topMargin = (root.height / 2f + frameSize / 2f + AlbumButtonOffsetDp.dp()).roundToInt()
                .coerceAtMost((root.height - buttonHeight - 24.dp()).coerceAtLeast(0))
            button.layoutParams = (button.layoutParams as FrameLayout.LayoutParams).apply {
                this.topMargin = topMargin
            }
        }
    }

    private fun openImagePicker() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
        }
        runCatching {
            startActivityForResult(
                Intent.createChooser(intent, getString(R.string.qr_scan_choose_image)),
                ImagePickerRequestCode,
            )
        }.onFailure { error ->
            AndroidAppLogger.error(LogTag, "Failed to open image picker", error)
        }
    }

    private fun createAlbumButtonBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(77, 0, 0, 0))
            setStroke(1.dp(), Color.argb(96, 255, 255, 255))
        }
    }

    private fun Int.dp(): Int {
        return (this * resources.displayMetrics.density).roundToInt()
    }

    private companion object {
        const val FrameSizeRatio = 0.72f
        const val ImagePickerRequestCode = 2001
        const val AlbumButtonSizeDp = 92
        const val AlbumButtonOffsetDp = 78
        const val LogTag = "PortraitQrCapture"
    }
}
