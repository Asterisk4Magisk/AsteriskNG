// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.qr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import app.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Size
import data.AppSettingsPreferences
import features.logs.AndroidAppLogger
import features.settings.locale.localizedAppContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.feedback.AndroidToastTipNotifier
import kotlin.math.min
import kotlin.math.roundToInt

class PortraitQrCaptureActivity : ComponentActivity() {
    private lateinit var capture: CaptureManager
    private lateinit var barcodeView: DecoratedBarcodeView
    private var cameraPermissionRequested = false
    private var cameraPermissionPending = false
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionPending = false
        cameraPermissionRequested = true
        if (granted) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) capture.onResume()
        } else {
            // Delegate ZXing's missing-permission result and dialog without a platform callback.
            capture.onRequestPermissionsResult(
                CaptureManager.getCameraPermissionReqCode(),
                arrayOf(Manifest.permission.CAMERA),
                intArrayOf(PackageManager.PERMISSION_DENIED),
            )
        }
    }

    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tipNotifier by lazy { AndroidToastTipNotifier(this) }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(::decodeImage)
    }

    override fun attachBaseContext(newBase: Context) {
        val languageMode = AppSettingsPreferences(newBase).load().languageMode
        super.attachBaseContext(newBase.localizedAppContext(languageMode))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraPermissionPending = savedInstanceState?.getBoolean(CameraPermissionPendingKey) ?: false
        cameraPermissionRequested = cameraPermissionPending
        barcodeView = initializeContent()
        capture = CaptureManager(this, barcodeView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.decode()
    }

    private fun initializeContent(): DecoratedBarcodeView {
        setContentView(com.google.zxing.client.android.R.layout.zxing_capture)
        val barcodeView = findViewById<DecoratedBarcodeView>(com.google.zxing.client.android.R.id.zxing_barcode_scanner)
        val metrics = resources.displayMetrics
        val frameSize = (min(metrics.widthPixels, metrics.heightPixels) * FrameSizeRatio).roundToInt()
        barcodeView.barcodeView.framingRectSize = Size(frameSize, frameSize)
        barcodeView.viewFinder.setLaserVisibility(false)
        addImagePickerButton(frameSize)
        return barcodeView
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            capture.onResume()
        } else if (!cameraPermissionRequested) {
            cameraPermissionRequested = true
            cameraPermissionPending = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        super.onPause()
        capture.onPause()
    }

    override fun onDestroy() {
        decodeScope.cancel()
        super.onDestroy()
        capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
        // Only an outstanding result suppresses a new request after recreation.
        outState.putBoolean(CameraPermissionPendingKey, cameraPermissionPending)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return barcodeView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    private fun decodeImage(uri: Uri) {
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
            imagePicker.launch(Intent.createChooser(intent, getString(R.string.qr_scan_choose_image)))
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
        const val CameraPermissionPendingKey = "asterisk.cameraPermissionPending"
        const val FrameSizeRatio = 0.72f
        const val AlbumButtonSizeDp = 92
        const val AlbumButtonOffsetDp = 78
        const val LogTag = "PortraitQrCapture"
    }
}
