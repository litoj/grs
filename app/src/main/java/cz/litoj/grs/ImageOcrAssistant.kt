package cz.litoj.grs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "ImageOcrAssistant"

/**
 * Multiplier applied to screen width to derive the target image dimension.
 * Matches the camera's 4:3 aspect-ratio resolution strategy used in [CameraReaderService],
 * so gallery images are processed at the same resolution as camera frames.
 */
private const val TARGET_DIMENSION_RATIO = 4f / 3f

/**
 * One-shot OCR of a still image (gallery pick or share intent).
 *
 * Decodes the image at a bounded resolution, applies EXIF rotation, runs the
 * whole image through ML Kit text recognition (no scan-box crop — unlike the
 * camera path), and forwards the result to [onTextRecognized].
 * When no coordinates can be extracted, [onOcrFailure] receives the recognized
 * text plus a preview bitmap zoomed into the detected text region, so the UI
 * can offer an edit-and-rematch dialog.
 */
class ImageOcrAssistant(
    private val context: Context,
    private val onTextRecognized: (String) -> Boolean,
    private val onOcrFailure: (String?, Bitmap?) -> Unit = { _, _ -> },
) {
    private val textRecognizer = TextRecognizer()

    /**
     * Target bitmap dimension (long edge) for both OCR decoding and preview.
     * Derived from screen width × 4/3 to match the camera's resolution, so
     * gallery images are processed at the same quality as camera frames.
     */
    private val targetDimension: Int =
        (context.resources.displayMetrics.widthPixels * TARGET_DIMENSION_RATIO).toInt()

    /**
     * Decode, recognize and process coordinates from an image [uri].
     *
     * The URI bytes are copied into private cache FIRST — content-URI read
     * grants are transient and are revoked on activity recreation / process
     * relaunch. Decoding from the cached copy makes OCR immune to grant loss.
     *
     * Safe to call from any coroutine; heavy work runs on [Dispatchers.IO],
     * the result callback runs on the main thread.
     */
    suspend fun recognizeFromUri(uri: Uri) {
        val (recognizedText, preview) = withContext(Dispatchers.IO) {
            runCatching {
                val cacheFile = copyToCache(uri)
                    ?: throw IllegalStateException("Could not read image data")
                try {
                    val bitmap = decodeAndOrient(cacheFile)
                        ?: throw IllegalStateException("Could not decode image")
                    val result = textRecognizer.recognizeImage(
                        InputImage.fromBitmap(bitmap, 0),
                    )
                    // Only coordinate-like blocks count: their text is what we
                    // offer the user, and they decide the crop & outlines.
                    val coordBlocks = result?.textBlocks?.filter {
                        GpsCoordinateParser.looksLikeCoordinate(it.text)
                    }.orEmpty()
                    buildPreview(bitmap, result, coordBlocks)
                } finally {
                    cacheFile.delete()
                }
            }.onFailure { Log.e(TAG, "Image OCR failed", it) }
                .getOrNull()
                ?: (null to null)
        }

        withContext(Dispatchers.Main) {
            val found = onTextRecognized(recognizedText ?: "")
            if (!found) {
                onOcrFailure(recognizedText, preview)
            }
        }
    }

    /**
     * Build a display-sized preview of [source] zoomed into the union of the OCR
     * text blocks that look like coordinates (falls back forwardwhole image when
     * none). Only coordinate-like blocks get red outlines.
     *
     * Returns the raw text derived from the coordinate blocks plus the annotated
     * preview bitmap.
     */
    private fun buildPreview(
        source: Bitmap,
        text: Text?,
        coordinateBlocks: List<Text.TextBlock>,
    ): Pair<String?, Bitmap> {
        // The useful text for parsing/editing: only the coordinate blocks.
        // If OCR found none at all, keep the full OCR text so the user field
        // isn't empty in the dialog (they can still type manually).
        val blockText = coordinateBlocks.joinToString(" ") { it.text }
        val usefulText = blockText.ifBlank { text?.text }

        val labeled = text?.textBlocks?.mapNotNull { block ->
            block.boundingBox?.let {
                it to (block in coordinateBlocks)
            }
        }.orEmpty()

        val coordinateBoxes = labeled.filter { it.second }.map { it.first }
        val allBoxes = labeled.map { it.first }

        // Focus region: coordinate-like blocks if any → whole image.
        // (Never fall back to *all* text — that just re-shows the messy photo.)
        val focusBoxes = coordinateBoxes.ifEmpty {
            listOf(Rect(0, 0, source.width, source.height))
        }

        val union = Rect(focusBoxes.first()).also { u -> focusBoxes.forEach { u.union(it) } }
        val margin = (maxOf(source.width, source.height) * 0.02f).roundToInt()
        val region = Rect(
            (union.left - margin).coerceAtLeast(0),
            (union.top - margin).coerceAtLeast(0),
            (union.right + margin).coerceAtMost(source.width),
            (union.bottom + margin).coerceAtMost(source.height),
        ).takeIf { it.width() > 0 && it.height() > 0 }
            ?: Rect(0, 0, source.width, source.height)

        // Crop + downscale in one step via a scaling matrix
        val scale = min(
            1f,
            targetDimension.toFloat() / maxOf(region.width(), region.height()),
        )
        val matrix = Matrix().apply { setScale(scale, scale) }
        val rawPreview = Bitmap.createBitmap(
            source, region.left, region.top, region.width(), region.height(),
            matrix, true,
        )
        // Ensure a mutable bitmap so Canvas can draw the block outlines
        val preview = if (rawPreview.isMutable) rawPreview else rawPreview.copy(
            Bitmap.Config.ARGB_8888, true,
        )

        // Red outlines around coordinate-like blocks only (coords → preview
        // coords). Best-effort: if drawing fails, keep the un-annotated preview.
        if (coordinateBoxes.isNotEmpty()) {
            runCatching {
                val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.RED
                    style = Paint.Style.STROKE
                    strokeWidth = (preview.width / 250f).coerceAtLeast(2f)
                }
                val canvas = Canvas(preview)
                coordinateBoxes.forEach { box ->
                    canvas.drawRect(
                        (box.left - region.left) * scale,
                        (box.top - region.top) * scale,
                        (box.right - region.left) * scale,
                        (box.bottom - region.top) * scale,
                        coordPaint,
                    )
                }
            }.onFailure { Log.w(TAG, "Failed to draw block outlines", it) }
        }
        Log.d(
            TAG,
            "Preview: ${preview.width}x${preview.height}, " +
                "${coordinateBoxes.size}/${allBoxes.size} coordinate block(s), " +
                "region $region"
        )
        return usefulText to preview
    }

    /**
     * Copy the content behind [uri] into a private cache file.
     * Must be called while the transient read grant is still valid
     * (i.e. right when the picker/share callback fires).
     */
    private fun copyToCache(uri: Uri): File? {
        val resolver = context.contentResolver
        val input = runCatching { resolver.openInputStream(uri) }
            .onFailure { Log.e(TAG, "copyToCache: openInputStream threw", it) }
            .getOrNull()
        if (input == null) {
            Log.e(TAG, "copyToCache: openInputStream returned null for $uri")
            return null
        }

        val cacheFile = File.createTempFile("ocr_image_", ".tmp", context.cacheDir)
        return try {
            cacheFile.outputStream().use { out -> input.use { it.copyTo(out) } }
            Log.d(TAG, "Cached ${cacheFile.length()} bytes for $uri")
            cacheFile
        } catch (e: Exception) {
            Log.e(TAG, "copyToCache: copy failed", e)
            cacheFile.delete()
            null
        }
    }

    /**
     * Decode [file] into a [Bitmap], downscaled to [targetDimension] on the
     * long edge and rotated per EXIF orientation so the text is upright.
     */
    private fun decodeAndOrient(file: File): Bitmap? {
        // Pass 1: bounds only, to compute the sample size
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.e(TAG, "BitmapFactory bounds decode failed: ${bounds.outWidth}x${bounds.outHeight}")
            return null
        }
        Log.d(TAG, "Image bounds: ${bounds.outWidth}x${bounds.outHeight}")

        val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, targetDimension)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
        if (bitmap == null) {
            Log.e(TAG, "BitmapFactory full decode failed")
            return null
        }
        Log.d(TAG, "Decoded bitmap: ${bitmap.width}x${bitmap.height} config=${bitmap.config}")

        // EXIF orientation
        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        return applyOrientation(bitmap, orientation)
    }

    /**
     * Largest power-of-2 sample size that keeps both dimensions ≤ [targetDimension].
     */
    private fun computeSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxDimension ||
            height / (sample * 2) >= maxDimension
        ) {
            sample *= 2
        }
        return sample
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Release the ML Kit recognizer. */
    fun close() {
        textRecognizer.close()
    }
}
