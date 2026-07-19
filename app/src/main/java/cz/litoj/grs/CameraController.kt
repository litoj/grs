package cz.litoj.grs

import android.content.Context
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

private const val TAG = "CameraController"

private const val SCAN_INTERVAL_FAST_MS = 250L
private const val SCAN_INTERVAL_SLOW_MS = 1500L
private const val SCAN_INTERVAL_MANUAL_MS = 100L

private const val PREFS_NAME = "grs_settings"
private const val KEY_AUTO_SCAN = "auto_scan"

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
class CameraController(
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

    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether automatic (continuous) scanning is enabled. Persisted across restarts. */
    private val _autoScan = MutableStateFlow(sharedPrefs.getBoolean(KEY_AUTO_SCAN, true))
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
            _autoScan.value ->
                if (hasCoords.get()) SCAN_INTERVAL_SLOW_MS else SCAN_INTERVAL_FAST_MS
            _scanState.value -> SCAN_INTERVAL_MANUAL_MS
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
                val recognizedText = textRecognizer.recognizeText(imageProxy)
                Log.d(TAG, "OCR result: '${recognizedText?.take(200)}'")
                if (!recognizedText.isNullOrBlank()) {
                    val found = onTextRecognized(recognizedText)
                    hasCoords.set(found)
                    // In manual mode, stop scanning once coordinates are found
                    if (found && !_autoScan.value) {
                        _scanState.value = false
                    }
                } else {
                    onTextRecognized("")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error", e)
            } finally {
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
     * Start a manual scan. Only effective in manual mode.
     * Scans every ~100ms until valid coordinates are found, then stops automatically.
     */
    fun startScanning() {
        if (_autoScan.value) {
            Log.d(TAG, "startScanning ignored — automatic mode is active")
            return
        }
        _scanState.value = true
        Log.d(TAG, "Manual scan started")
    }

    /**
     * Stop an active manual scan.
     */
    fun stopScanning() {
        _scanState.value = false
        Log.d(TAG, "Manual scan stopped")
    }

    /**
     * Shut down the camera executor and release the text recognizer.
     */
    fun stop() {
        cameraExecutor.shutdown()
        textRecognizer.close()
    }
}
