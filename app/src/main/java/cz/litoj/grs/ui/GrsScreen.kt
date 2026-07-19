package cz.litoj.grs.ui

import cz.litoj.grs.CameraController
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.litoj.grs.GpsEvent
import cz.litoj.grs.GpsSpoofViewModel

/**
 * Main screen for GPS Read & Spoof app.
 * Camera scans continuously, coordinates auto-detect and auto-mock.
 */
@Composable
fun GrsScreen(
    viewModel: GpsSpoofViewModel,
    cameraController: CameraController,
) {
    val uiState by viewModel.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    // Collect one-time events from the ViewModel
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is GpsEvent.MockError -> {
                    snackbarHostState.showSnackbar(event.message)
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                CoordinateInputSection(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 12.dp, bottom = 8.dp),
                )

                CameraPreviewSection(
                    cameraController = cameraController,
                    lastRawText = uiState.lastRawText,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
    }
}
