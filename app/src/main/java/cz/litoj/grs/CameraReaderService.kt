package cz.litoj.grs

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "CameraReaderService"

private const val SCAN_INTERVAL_FAST_MS = 250L
private const val SCAN_INTERVAL_SLOW_MS = 1500L
private const val SCAN_INTERVAL_MANUAL_MS = 100L

private const val PREFS_NAME = "grs_settings"
private const val KEY_AUTO_SCAN = "auto_scan"

/**
 * Screen width and scan-box overlay dimensions in pixels.
 * The screen width is the constraining dimension in FILL_CENTER, so the
 * view height isn't needed to calculate the crop.
 */
data class CropParams(
    val screenWidthPx: Int,
    val overlayWidthPx: Int,
    val overlayHeightPx: Int,
)

/**
 * Manages CameraX preview + image analysis with text recognition.
 *
 * Supports two scanning modes:
 * - **Automatic** (default): continuously processes frames with adaptive debouncing
 *   (fast until coordinates are found, then slow).
 * - **Manual**: only processes frames when [startScanning] is called, at 100ms intervals
 *   until valid coordinates are read.
 *
 * The mode is controlled by [autoScan] and persisted across app restarts.
 */
class CameraReaderService(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onTextRecognized: (String) -> Boolean,
) {
    private val cameraExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()
    private val textRecognizer = TextRecognizer()
    private val scope = CoroutineScope(Dispatchers.Default)

    private val isProcessing = AtomicBoolean(false)
    private val lastScanTime = AtomicLong(0L)
    private val hasCoords = AtomicBoolean(false)

    /** Dimensions of the preview area + scan-box overlay, for exact OCR crop. */
    @Volatile
    var cropParams: CropParams = CropParams(1, 1, 1)

    private val sharedPrefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether automatic (continuous) scanning is enabled. Persisted across restarts. */
    private val _autoScan =
        MutableStateFlow(sharedPrefs.getBoolean(KEY_AUTO_SCAN, true))
    val autoScan: StateFlow<Boolean> = _autoScan.asStateFlow()

    /** Whether a manual scan is actively running. Only meaningful when [autoScan] is false. */
    private val _scanState = MutableStateFlow(value = false)
    val scanState: StateFlow<Boolean> = _scanState.asStateFlow()

    /** Preview view for the camera — created once, rendered by the composable. */
    val previewView: PreviewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    /**
     * Bind CameraX preview + image analysis to the lifecycle.
     */
    fun start() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analyzer ->
                        analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                            processFrame(imageProxy)
                        }
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer,
                )
                Log.d(TAG, "Camera bound successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Process a single camera frame.
     *
     * In automatic mode: adaptive debouncing (fast until coords found, then slow).
     * In manual mode: only processes when [scanState] is true, at 100ms intervals.
     */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val timeSinceLastScan = now - lastScanTime.get()

        val debounceMs = when {
            _scanState.value -> SCAN_INTERVAL_MANUAL_MS
            _autoScan.value ->
                if (hasCoords.get()) SCAN_INTERVAL_SLOW_MS else SCAN_INTERVAL_FAST_MS

            else -> {
                // Manual mode and not actively scanning — skip
                imageProxy.close()
                return
            }
        }

        if (timeSinceLastScan < debounceMs) {
            imageProxy.close()
            return
        }

        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        lastScanTime.set(now)
        Log.d(TAG, "Processing frame")

        scope.launch {
            try {
                val inputImage = imageProxy.toCroppedInputImage()
                val recognizedText = textRecognizer.recognizeText(inputImage)
                Log.d(TAG, "OCR result: '${recognizedText?.take(200)}'")
                if (!recognizedText.isNullOrBlank()) {
                    val found = onTextRecognized(recognizedText)
                    hasCoords.set(found)
                    // Stop burst scan once coordinates are found
                    if (found && _scanState.value) {
                        _scanState.value = false
                    }
                } else {
                    onTextRecognized("")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error", e)
            } finally {
                imageProxy.close()
                isProcessing.set(false)
            }
        }
    }

    /**
     * Enable or disable automatic scanning mode. Persisted across app restarts.
     * Switching to manual cancels any active scanning and resets coordinate state.
     */
    fun setAutoScan(enabled: Boolean) {
        sharedPrefs.edit { putBoolean(KEY_AUTO_SCAN, enabled) }
        _autoScan.value = enabled
        if (!enabled) {
            _scanState.value = false
            hasCoords.set(false)
        }
        Log.d(TAG, "Auto scan set to $enabled")
    }

    /** Convenience method to toggle automatic scanning. */
    fun toggleAutoScan() = setAutoScan(!_autoScan.value)

    /**
     * Start a burst scan. Works in both automatic and manual modes.
     * Scans every ~100ms until valid coordinates are found, then stops automatically.
     */
    fun startScanning() {
        _scanState.value = true
        Log.d(TAG, "Burst scan started")
    }

    /**
     * Stop an active manual scan.
     */
    fun stopScanning() {
        _scanState.value = false
        Log.d(TAG, "Manual scan stopped")
    }

    /**
     * Convert an [ImageProxy] to a cropped [InputImage] matching the scan-box overlay.
     * The overlay is centered, so the crop is centered on the bitmap.
     * Accounts for FILL_CENTER scaling and 90°/270° rotation.
     */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun ImageProxy.toCroppedInputImage(): InputImage {
        val bitmap = this.toBitmap()
        val rotationDegrees = this.imageInfo.rotationDegrees
        val p = cropParams

        val (cropW, cropH) = if (rotationDegrees == 90 || rotationDegrees == 270) {
            // Rotated bitmap: W_b × H_b → display as H_b × W_b
            // Screen width constrains in FILL_CENTER → scale = screenWidth / bitmap.height
            val scale = p.screenWidthPx.toFloat() / bitmap.height
            // View height → sensor width; View width → sensor height
            (p.overlayHeightPx / scale).toInt() to (p.overlayWidthPx / scale).toInt()
        } else {
            val scale = p.screenWidthPx.toFloat() / bitmap.width
            (p.overlayWidthPx / scale).toInt() to (p.overlayHeightPx / scale).toInt()
        }

        val left = ((bitmap.width - cropW) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - cropH) / 2).coerceAtLeast(0)
        val w = cropW.coerceAtMost(bitmap.width - left)
        val h = cropH.coerceAtMost(bitmap.height - top)

        Log.d(
            TAG,
            "Cropping to: left=$left top=$top w=$w h=$h (from ${bitmap.width}x${bitmap.height})"
        )

        val croppedBitmap = Bitmap.createBitmap(bitmap, left, top, w, h)
        return InputImage.fromBitmap(croppedBitmap, rotationDegrees)
    }

    /**
     * Shut down the camera executor and release the text recognizer.
     */
    fun stop() {
        cameraExecutor.shutdown()
        textRecognizer.close()
    }
}
