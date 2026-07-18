package cz.litoj.grs.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.location.LocationManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.litoj.grs.CoordinateFormat
import cz.litoj.grs.GpsEvent
import cz.litoj.grs.GpsSpoofViewModel
import cz.litoj.grs.TextRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "GpsSpoofScreen"

/**
 * Main screen for GPS Read & Spoof app.
 * Camera scans continuously, coordinates auto-detect and auto-mock.
 */
@Composable
fun GpsSpoofScreen(
    viewModel: GpsSpoofViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val permissionState by viewModel.permissionState.collectAsState()

    var expanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.setAppContext(context)
        updatePermissionStates(context, viewModel)
    }

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

    // Check GPS on launch and whenever app regains focus; show toast if disabled
    val checkGps = {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(context, "GPS is disabled. Please enable it in settings.", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) { checkGps() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) checkGps()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                modifier = Modifier
                    .fillMaxSize(),
            ) {
                // Top content keeps its padding
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 12.dp),
                ) {
                    CoordinateInputSection(
                        latitudeText = uiState.latitudeText,
                        longitudeText = uiState.longitudeText,
                        selectedFormat = uiState.selectedFormat,
                        isExpanded = expanded,
                        onExpandChange = { expanded = it },
                        onFormatSelected = { format ->
                            viewModel.setCoordinateFormat(format)
                            expanded = false
                        },
                        onLatitudeChange = viewModel::updateLatitudeText,
                        onLongitudeChange = viewModel::updateLongitudeText,
                        onCopy = {
                            val coords = "${uiState.latitudeText}, ${uiState.longitudeText}"
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Coordinates", coords))
                            Toast.makeText(context, "Copied: $coords", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                }

                // Camera preview — full bleed to sides and bottom
                CameraPreviewSection(
                    hasCameraPermission = permissionState.cameraPermissionGranted,
                    lastRawText = uiState.lastRawText,
                    onTextRecognized = viewModel::onTextRecognized,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }


        }
    }
}

// ------------------------------------------------------------------
// Coordinate input
// ------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoordinateInputSection(
    latitudeText: String,
    longitudeText: String,
    selectedFormat: CoordinateFormat,
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onFormatSelected: (CoordinateFormat) -> Unit,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(2f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = latitudeText,
                onValueChange = onLatitudeChange,
                label = { Text("Latitude (N/S)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            )
            OutlinedTextField(
                value = longitudeText,
                onValueChange = onLongitudeChange,
                label = { Text("Longitude (E/W)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ExposedDropdownMenuBox(
                expanded = isExpanded,
                onExpandedChange = onExpandChange,
            ) {
                OutlinedTextField(
                    value = selectedFormat.displayName,
                    onValueChange = {},
                    label = { Text("Format") },
                    readOnly = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor()
                        .width(120.dp),
                    shape = RoundedCornerShape(8.dp),
                )

                androidx.compose.material3.DropdownMenu(
                    expanded = isExpanded,
                    onDismissRequest = { onExpandChange(false) },
                ) {
                    CoordinateFormat.entries.forEach { format ->
                        DropdownMenuItem(
                            text = { Text(format.displayName) },
                            onClick = { onFormatSelected(format) },
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Copy")
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Camera preview with continuous text recognition
// ------------------------------------------------------------------

@Composable
private fun CameraPreviewSection(
    hasCameraPermission: Boolean,
    lastRawText: String,
    onTextRecognized: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val textRecognizer = remember { TextRecognizer() }
    val scope = rememberCoroutineScope()

    // Prevents concurrent frame processing
    val isProcessing = remember { AtomicBoolean(false) }
    // Debounce: minimum time between scans
    val lastScanTime = remember { AtomicLong(0L) }
    // Whether the last scan produced valid coordinates (controls scan rate)
    val hasCoords = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            textRecognizer.close()
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black),
    ) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }

                    bindCamera(
                        context = ctx,
                        previewView = previewView,
                        lifecycleOwner = lifecycleOwner,
                        cameraExecutor = cameraExecutor,
                        textRecognizer = textRecognizer,
                        scope = scope,
                        isProcessing = isProcessing,
                        lastScanTime = lastScanTime,
                        hasCoords = hasCoords,
                        onTextRecognized = onTextRecognized,
                    )

                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )

            ScanBoxOverlay()

            // Raw OCR text overlay
            if (lastRawText.isNotBlank()) {
                Text(
                    text = "OCR: ${lastRawText.replace("\n", " \u21B5 ")}",
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
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Camera permission required\nfor coordinate scanning",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Binds CameraX preview + image analysis to the lifecycle.
 */
private fun bindCamera(
    context: Context,
    previewView: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    cameraExecutor: ExecutorService,
    textRecognizer: TextRecognizer,
    scope: CoroutineScope,
    isProcessing: AtomicBoolean,
    lastScanTime: AtomicLong,
    hasCoords: AtomicBoolean,
    onTextRecognized: (String) -> Boolean,
) {
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
                        processFrame(
                            imageProxy = imageProxy,
                            textRecognizer = textRecognizer,
                            scope = scope,
                            isProcessing = isProcessing,
                            lastScanTime = lastScanTime,
                            hasCoords = hasCoords,
                            onTextRecognized = onTextRecognized,
                        )
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

/** Scan interval when no valid coordinates have been found yet (fast scanning). */
private const val SCAN_INTERVAL_FAST_MS = 250L
/** Scan interval when valid coordinates are already available (slow scanning). */
private const val SCAN_INTERVAL_SLOW_MS = 1500L

/**
 * Processes a single camera frame with continuous scanning + adaptive debouncing:
 * - Scans every 250ms when no valid coordinates have been found yet
 * - Scans every 1.5s when valid coordinates are already available
 * - Skips if still processing a previous frame (isProcessing flag)
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun processFrame(
    imageProxy: ImageProxy,
    textRecognizer: TextRecognizer,
    scope: CoroutineScope,
    isProcessing: AtomicBoolean,
    lastScanTime: AtomicLong,
    hasCoords: AtomicBoolean,
    onTextRecognized: (String) -> Boolean,
) {
    val now = System.currentTimeMillis()
    val timeSinceLastScan = now - lastScanTime.get()
    val debounceMs = if (hasCoords.get()) SCAN_INTERVAL_SLOW_MS else SCAN_INTERVAL_FAST_MS

    // Skip if within debounce window
    if (timeSinceLastScan < debounceMs) {
        imageProxy.close()
        return
    }

    // Skip if still processing a previous frame
    if (!isProcessing.compareAndSet(false, true)) {
        imageProxy.close()
        return
    }

    lastScanTime.set(now)
    Log.d(TAG, "Processing frame")

    scope.launch(Dispatchers.Default) {
        try {
            val recognizedText = textRecognizer.recognizeText(imageProxy)
            Log.d(TAG, "OCR result: '${recognizedText?.take(200)}'")
            if (!recognizedText.isNullOrBlank()) {
                // Only update scan rate when we got text — blank text means
                // nothing to read, so keep the current rate
                hasCoords.set(onTextRecognized(recognizedText))
            } else {
                // Clear the OCR preview line when no text is detected
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

private fun updatePermissionStates(context: Context, viewModel: GpsSpoofViewModel) {
    val cameraGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    val locationGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    val backgroundLocationGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    val mockLocationGranted = context.packageManager.checkPermission(
        "android.permission.ACCESS_MOCK_LOCATION", context.packageName,
    ) == PackageManager.PERMISSION_GRANTED

    viewModel.updatePermissionStates(
        cameraGranted = cameraGranted,
        locationGranted = locationGranted,
        backgroundLocationGranted = backgroundLocationGranted,
        mockLocationGranted = mockLocationGranted,
    )
}
