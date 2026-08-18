package cz.litoj.grs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import android.util.Log
import cz.litoj.grs.CameraReaderService
import cz.litoj.grs.CropParams
import cz.litoj.grs.R

private const val TAG = "CameraPreviewSection"

/** Width ratio of the scan-box overlay relative to the preview area. */
private const val SCAN_BOX_WIDTH_RATIO = 0.85f

/** Height of the scan-box overlay. */
private val SCAN_BOX_HEIGHT = 120.dp

@Composable
fun CameraPreviewSection(
    cameraReaderService: CameraReaderService,
    hasCameraPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    lastRawText: String,
    pendingScan: Boolean,
    onScanTriggered: () -> Unit,
    isCameraActive: Boolean,
    onScanNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Start/stop the camera based on permission and active state
    LaunchedEffect(hasCameraPermission, isCameraActive) {
        if (hasCameraPermission && isCameraActive) {
            Log.d(TAG, "Starting camera")
            cameraReaderService.start()
        } else if (!isCameraActive) {
            Log.d(TAG, "Stopping camera")
            cameraReaderService.stopCamera()
        }
    }

    // Trigger a burst scan when the shortcut was used (and camera is ready)
    LaunchedEffect(pendingScan, hasCameraPermission) {
        if (pendingScan && hasCameraPermission) {
            cameraReaderService.startScanning()
            onScanTriggered()
        }
    }

    val density = LocalDensity.current

    // Measure the preview area and update the OCR crop region to match the scan-box overlay
    Box(
        modifier = modifier
            .background(Color.Black)
            .onGloballyPositioned { coords ->
                val w = coords.size.width
                val h = coords.size.height
                if ((w > 0) && (h > 0)) {
                    cameraReaderService.cropParams = CropParams(
                        screenWidthPx = w,
                        previewHeightPx = h,
                        overlayWidthPx = (w * SCAN_BOX_WIDTH_RATIO).toInt(),
                        overlayHeightPx = with(density) { SCAN_BOX_HEIGHT.toPx() }.toInt(),
                    )
                }
            },
    ) {
        if (hasCameraPermission) {
            // Camera preview - only shown when active
            if (isCameraActive) {
                AndroidView(
                    factory = { cameraReaderService.previewView },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Camera is off - show blank background instead of camera view
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }

            // Scan box overlay - ALWAYS visible
            ScanBoxOverlay()

            // OCR text preview - ALWAYS visible when there's content
            if (lastRawText.isNotBlank()) {
                Text(
                    text = stringResource(R.string.ocr_prefix, lastRawText.replace("\n", " \u21B5")),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            // "Camera is off" message and "Scan Now" button - only when camera is inactive
            if (!isCameraActive) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.camera_is_off),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                    )
                    
                    ExtendedFloatingActionButton(
                        onClick = onScanNow,
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_document_scanner),
                                contentDescription = stringResource(R.string.scan),
                            )
                        },
                        text = { Text(stringResource(R.string.scan_now)) },
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.camera_permission_required),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentSize(Alignment.Center)
                    .clickable {
                        onRequestCameraPermission()
                    },
            )
        }
    }
}

/**
 * Semi-transparent overlay with a transparent rectangle in the center
 * to indicate the scanning area.
 */
@Composable
private fun ScanBoxOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f)),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(SCAN_BOX_WIDTH_RATIO)
                .height(SCAN_BOX_HEIGHT)
                .background(Color.Transparent)
                .border(
                    width = 2.dp,
                    color = Color.White,
                    shape = RoundedCornerShape(8.dp),
                ),
        )
    }
}
