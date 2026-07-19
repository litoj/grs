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
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "CameraController"

private const val SCAN_INTERVAL_FAST_MS = 250L
private const val SCAN_INTERVAL_SLOW_MS = 1500L

/**
 * Manages CameraX preview + image analysis with continuous text recognition.
 *
 * Owns the [PreviewView], camera executor, and text recognizer.
 * Calls [onTextRecognized] for each frame that produces OCR text.
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
     * Process a single camera frame with adaptive debouncing.
     */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val timeSinceLastScan = now - lastScanTime.get()
        val debounceMs =
            if (hasCoords.get()) SCAN_INTERVAL_SLOW_MS else SCAN_INTERVAL_FAST_MS

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
                    hasCoords.set(onTextRecognized(recognizedText))
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
     * Shut down the camera executor and release the text recognizer.
     */
    fun stop() {
        cameraExecutor.shutdown()
        textRecognizer.close()
    }
}
