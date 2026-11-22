// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.qr

import android.Manifest
import android.animation.ValueAnimator
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
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import app.R
import app.observeScreenshotProtection
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Size
import features.logs.AndroidAppLogger
import features.settings.locale.localizedFromSavedLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.feedback.AndroidToastTipNotifier
import ui.layout.usableWindowPane
import top.yukonga.miuix.kmp.anim.folmeSpring
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
        super.attachBaseContext(newBase.localizedFromSavedLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeScreenshotProtection()
        cameraPermissionPending = savedInstanceState?.getBoolean(CameraPermissionPendingKey) ?: false
        cameraPermissionRequested = cameraPermissionPending
        barcodeView = initializeContent()
        capture = CaptureManager(this, barcodeView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.decode()
    }

    private fun initializeContent(): DecoratedBarcodeView {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val scannerPane = FrameLayout(this)
        root.addView(scannerPane, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        layoutInflater.inflate(com.google.zxing.client.android.R.layout.zxing_capture, scannerPane, true)
        val scanner = scannerPane.findViewById<DecoratedBarcodeView>(
            com.google.zxing.client.android.R.id.zxing_barcode_scanner,
        )
        scanner.barcodeView.isUseTextureView = true
        scanner.viewFinder.setLaserVisibility(false)
        val button = addImagePickerButton(scannerPane)
        var layoutInfo: WindowLayoutInfo? = null
        var paneAnimation: Job? = null

        fun updatePane() {
            if (root.width == 0 || root.height == 0) return
            val decor = window.decorView
            val direction = if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
            val pane = usableWindowPane(layoutInfo, IntSize(decor.width, decor.height), direction)
            val insets = ViewCompat.getRootWindowInsets(root)
                ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val location = IntArray(2)
            root.getLocationInWindow(location)
            val left = (maxOf(pane.left, insets?.left ?: 0) - location[0]).coerceIn(0, root.width)
            val top = (maxOf(pane.top, insets?.top ?: 0) - location[1]).coerceIn(0, root.height)
            val right = (minOf(pane.right, decor.width - (insets?.right ?: 0)) - location[0])
                .coerceIn(left, root.width)
            val bottom = (minOf(pane.bottom, decor.height - (insets?.bottom ?: 0)) - location[1])
                .coerceIn(top, root.height)
            val width = right - left
            val height = bottom - top
            if (width == 0 || height == 0) return
            val params = scannerPane.layoutParams as FrameLayout.LayoutParams
            val resized = params.width != width || params.height != height
            if (!resized && root.paddingLeft == left && root.paddingTop == top) return

            val hadPane = params.width > 0 && params.height > 0
            root.setPadding(left, top, 0, 0)
            params.width = width
            params.height = height
            scannerPane.layoutParams = params
            val buttonSize = minOf(AlbumButtonSizeDp.dp(), width, height / 4).coerceAtLeast(1)
            val controlsSpace = buttonSize + 24.dp()
            val frameSize = (min(width, height) * FrameSizeRatio).roundToInt()
                .coerceAtMost(400.dp())
                .coerceAtMost((height - controlsSpace * 2).coerceAtLeast(height / 3))
                .coerceAtLeast(1)
            scanner.barcodeView.framingRectSize = Size(frameSize, frameSize)
            button.layoutParams = FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = minOf((height + frameSize) / 2 + 16.dp(), height - buttonSize - 8.dp()).coerceAtLeast(0)
            }
            // Apply the new bounds first so the preview never animates through the hinge.
            paneAnimation?.cancel()
            if (hadPane && ValueAnimator.areAnimatorsEnabled()) {
                scannerPane.alpha = 0f
                paneAnimation = lifecycleScope.launch(AndroidUiDispatcher.Main) {
                    Animatable(0f).animateTo(1f, folmeSpring(damping = 0.95f, response = 0.4f)) {
                        scannerPane.alpha = value.coerceIn(0f, 1f)
                    }
                }
            } else {
                scannerPane.alpha = 1f
            }
            if (resized && scanner.barcodeView.isPreviewActive) {
                // ZXing calculates its decode rectangle when it configures the preview.
                scanner.pause()
                scanner.resume()
            }
        }

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePane() }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            updatePane()
            insets
        }
        setContentView(root)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@PortraitQrCaptureActivity)
                    .windowLayoutInfo(this@PortraitQrCaptureActivity).collect {
                        layoutInfo = it
                        updatePane()
                    }
            }
        }
        return scanner
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

    private fun addImagePickerButton(root: FrameLayout): TextView {
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
        return button
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
        const val LogTag = "PortraitQrCapture"
    }
}
