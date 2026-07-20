package cz.litoj.grs

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
 * Preview area dimensions and scan-box overlay size in pixels.
 * Both width and height are needed because FILL_CENTER (CENTER_CROP) scales
 * by the larger of the two dimension ratios, so either can be constraining.
 */
data class CropParams(
    val screenWidthPx: Int,
    val previewHeightPx: Int,
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
    var cropParams: CropParams = CropParams(1, 1, 1, 1)

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
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    /**
     * Bind CameraX preview + image analysis to the lifecycle.
     *
     * ISP postprocessing is disabled via [Camera2Interop] for minimal pipeline
     * latency: edge enhancement, noise reduction, hot pixel correction, lens
     * shading, and chromatic aberration correction are all turned off.
     *
     * Focus is locked to a fixed distance between the lens's closest focus and
     * 1 meter, ideal for scanning nearby text without autofocus hunting.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun start() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                // Target a resolution matched to the device screen width to avoid
                // unnecessary overhead. The back camera sensor is landscape, so
                // screen width (portrait) maps to sensor height. Using 4:3 aspect
                // ratio (sensor native for most back cameras).
                val screenWidth = context.resources.displayMetrics.widthPixels
                val screenResolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(screenWidth * 4 / 3, screenWidth * 4 / 3),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val previewBuilder = Preview.Builder()
                    .setResolutionSelector(screenResolution)
                val previewExtender = Camera2Interop.Extender(previewBuilder)
                    .setCaptureRequestOption(
                        CaptureRequest.EDGE_MODE,
                        CaptureRequest.EDGE_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.NOISE_REDUCTION_MODE,
                        CaptureRequest.NOISE_REDUCTION_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.HOT_PIXEL_MODE,
                        CaptureRequest.HOT_PIXEL_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.SHADING_MODE,
                        CaptureRequest.SHADING_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
                    )

                val preview = previewBuilder.build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val analyzerBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(screenResolution)
                Camera2Interop.Extender(analyzerBuilder)
                    .setCaptureRequestOption(
                        CaptureRequest.EDGE_MODE,
                        CaptureRequest.EDGE_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.NOISE_REDUCTION_MODE,
                        CaptureRequest.NOISE_REDUCTION_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.HOT_PIXEL_MODE,
                        CaptureRequest.HOT_PIXEL_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.SHADING_MODE,
                        CaptureRequest.SHADING_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
                    )
                val imageAnalyzer = analyzerBuilder.build().also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer,
                )
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
        Log.d(TAG, "Processing frame ${imageProxy.width}x${imageProxy.height}")

        scope.launch {
            try {
                val inputImage = imageProxy.toCroppedInputImage()
                val recognizedText = textRecognizer.recognizeText(inputImage)
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
    }

    /** Convenience method to toggle automatic scanning. */
    fun toggleAutoScan() = setAutoScan(!_autoScan.value)

    /**
     * Start a burst scan. Works in both automatic and manual modes.
     * Scans every ~100ms until valid coordinates are found, then stops automatically.
     */
    fun startScanning() {
        _scanState.value = true
    }

    /**
     * Stop an active manual scan.
     */
    fun stopScanning() {
        _scanState.value = false
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
            // Rotated bitmap: W_b × H_b → display as H_b × W_b (portrait)
            // FILL_CENTER scales by max(width/rotatedW, height/rotatedH)
            val scale = maxOf(
                p.screenWidthPx.toFloat() / bitmap.height,
                p.previewHeightPx.toFloat() / bitmap.width,
            )
            // Screen X → bitmap Y; Screen Y → bitmap X
            (p.overlayHeightPx / scale).toInt() to (p.overlayWidthPx / scale).toInt()
        } else {
            val scale = maxOf(
                p.screenWidthPx.toFloat() / bitmap.width,
                p.previewHeightPx.toFloat() / bitmap.height,
            )
            (p.overlayWidthPx / scale).toInt() to (p.overlayHeightPx / scale).toInt()
        }

        val left = ((bitmap.width - cropW) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - cropH) / 2).coerceAtLeast(0)
        val w = cropW.coerceAtMost(bitmap.width - left)
        val h = cropH.coerceAtMost(bitmap.height - top)

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
