package cz.litoj.grs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import cz.litoj.grs.CameraReaderService
import cz.litoj.grs.CropParams
import cz.litoj.grs.R

@Composable
fun CameraPreviewSection(
    cameraReaderService: CameraReaderService,
    hasCameraPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    lastRawText: String,
    pendingScan: Boolean,
    onScanTriggered: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Start the camera once permission is granted
    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            cameraReaderService.start()
        }
    }

    // Trigger a burst scan when the shortcut was used (and camera is ready)
    LaunchedEffect(pendingScan, hasCameraPermission) {
        if (pendingScan && hasCameraPermission) {
            cameraReaderService.startScanning()
            onScanTriggered()
        }
    }

    val autoScan by cameraReaderService.autoScan.collectAsState()
    val isScanning by cameraReaderService.scanState.collectAsState()
    val density = LocalDensity.current

    // Measure the preview area and update the OCR crop region to match the scan-box overlay
    Box(
        modifier = modifier
            .background(Color.Black)
            .onGloballyPositioned { coords ->
                val w = coords.size.width
                val h = coords.size.height
                if (w > 0 && h > 0) {
                    cameraReaderService.cropParams = CropParams(
                        screenWidthPx = w,
                        previewHeightPx = h,
                        overlayWidthPx = (w * 0.85f).toInt(),
                        overlayHeightPx = with(density) { 120.dp.toPx() }.toInt(),
                    )
                }
            },
    ) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { cameraReaderService.previewView },
                modifier = Modifier.fillMaxSize(),
            )

            ScanBoxOverlay()

            if (lastRawText.isNotBlank()) {
                Text(
                    text = "OCR: ${lastRawText.replace("\n", " \u21B5")}",
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

            // Auto/Manual scan toggle (top-right)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(
                        Color.Black.copy(alpha = 0.5f),
                        RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "Continuous scan",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                Switch(
                    checked = autoScan,
                    onCheckedChange = { cameraReaderService.toggleAutoScan() },
                )
            }

            // Scan Now floating button (bottom center, manual mode only)
            if (!autoScan) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (isScanning) cameraReaderService.stopScanning()
                        else cameraReaderService.startScanning()
                    },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_document_scanner),
                            contentDescription = "Scan",
                        )
                    },
                    text = {
                        Text(if (isScanning) "Scanning\u2026" else "Scan Now")
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                )
            }
        } else {
            Text(
                text = "Camera permission required\nfor coordinate scanning",
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
                .fillMaxWidth(0.85f)
                .height(120.dp)
                .background(Color.Transparent)
                .border(
                    width = 2.dp,
                    color = Color.White,
                    shape = RoundedCornerShape(8.dp),
                ),
        )
    }
}
