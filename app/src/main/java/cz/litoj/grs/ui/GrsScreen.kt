package cz.litoj.grs.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cz.litoj.grs.CameraReaderService
import cz.litoj.grs.GpsEvent
import cz.litoj.grs.GpsSpoofViewModel

/**
 * Main screen for GPS Read & Spoof app.
 * Camera scans continuously, coordinates auto-detect and auto-mock.
 */
@Composable
fun GrsScreen(
    viewModel: GpsSpoofViewModel,
    cameraReaderService: CameraReaderService,
    hasLocationPermission: Boolean,
    hasCameraPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Collect one-time events from the ViewModel
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is GpsEvent.MockError -> {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = "Settings",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        val intent =
                            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }

                is GpsEvent.PendingCoordinates -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "Tap for ${event.displayText}",
                        actionLabel = "Set",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.applyCoordinates(event.coordinates)
                    }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                // Fully clickable snackbar — no separate action button.
                // wrapContentWidth breaks min-constraint propagation so the
                // bubble doesn't stretch end-to-end; padding gives 10dp margins.
                Snackbar(
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                        .wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = 500.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { data.performAction() })
                        },
                    shape = RoundedCornerShape(8.dp),
                    containerColor = SnackbarDefaults.color,
                    contentColor = SnackbarDefaults.contentColor,
                ) {
                    Text(
                        text = data.visuals.message,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                CoordinateInputSection(
                    viewModel = viewModel,
                    hasLocationPermission = hasLocationPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                        .padding(bottom = 6.dp),
                )

                CameraPreviewSection(
                    cameraReaderService = cameraReaderService,
                    hasCameraPermission = hasCameraPermission,
                    onRequestCameraPermission = onRequestCameraPermission,
                    lastRawText = uiState.lastRawText,
                    pendingScan = uiState.pendingScan,
                    onScanTriggered = { viewModel.setPendingScan(false) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds(),
                )
            }
        }
    }
}
