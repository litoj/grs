package cz.litoj.grs.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.litoj.grs.CameraReaderService
import cz.litoj.grs.CoordinateField
import cz.litoj.grs.CoordinateFormat
import cz.litoj.grs.GpsEvent
import cz.litoj.grs.GpsSpoofViewModel
import cz.litoj.grs.R

/** Max height of the preview image in the OCR failure dialog. */
private val DIALOG_IMAGE_MAX_HEIGHT = 260.dp

/**
 * Main screen for GPS Read & Spoof app.
 * Camera scans continuously, coordinates auto-detect and auto-mock.
 * All controls are consolidated in an overflow menu anchored to the coordinate input row.
 */
@Composable
fun GrsScreen(
    viewModel: GpsSpoofViewModel,
    cameraReaderService: CameraReaderService,
    hasLocationPermission: Boolean,
    hasCameraPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    onLoadImage: (Uri) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    // Pre-resolve string resources in composable scope for use in coroutines
    val mockingNotActiveMsg = stringResource(R.string.mocking_not_active)
    val settingsLabel = stringResource(R.string.settings)
    val setLabel = stringResource(R.string.set)
    val tapForCoordsFmt = stringResource(R.string.tap_for_coordinates)
    val invalidLatMsg = stringResource(R.string.invalid_latitude_format)
    val invalidLonMsg = stringResource(R.string.invalid_longitude_format)

    // Retry-dialog state: editable text + optional preview bitmap (null = hidden)
    var ocrEditText by remember { mutableStateOf<String?>(null) }
    var ocrEditPreview by remember { mutableStateOf<Bitmap?>(null) }

    // Camera is active when: an explicit burst scan is running (shortcut or "Scan Now"),
    // or continuous scanning is enabled AND the camera isn't suspended. Suspension
    // happens after loading coords from a file so Camera OCR can't overwrite them
    // until the user explicitly scans again.
    val autoScan by cameraReaderService.autoScan.collectAsState()
    val burstScanActive by cameraReaderService.scanState.collectAsState()
    val isCameraActive = uiState.pendingScan || burstScanActive ||
        (autoScan && !uiState.isCameraSuspended)

    // Image picker launcher
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let(onLoadImage)
    }

    // An explicit scan request via the launcher shortcut also lifts suspension,
    // so scanning resumes per the user's autoScan setting.
    LaunchedEffect(uiState.pendingScan) {
        if (uiState.pendingScan) viewModel.setCameraSuspended(false)
    }

    // Collect one-time events from the ViewModel
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is GpsEvent.MockError -> {
                    val result = snackbarHostState.showSnackbar(
                        message = mockingNotActiveMsg,
                        actionLabel = settingsLabel,
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
                        message = tapForCoordsFmt.format(event.displayText),
                        actionLabel = setLabel,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.applyCoordinates(event.coordinates)
                    }
                }

                is GpsEvent.ImageOcrFailure -> {
                    // OCR produced text (or nothing) but no coordinates — let
                    // the user correct OCR mistakes and re-match manually.
                    ocrEditText = event.rawText ?: ""
                    ocrEditPreview = event.preview
                }

                is GpsEvent.InvalidInput -> {
                    val message = when (event.field) {
                        CoordinateField.LATITUDE -> invalidLatMsg
                        CoordinateField.LONGITUDE -> invalidLonMsg
                    }
                    snackbarHostState.showSnackbar(
                        message = message,
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    // Retry dialog for failed image OCR — shows the loaded image zoomed into
    // the detected text region (when available) above the editable text field
    ocrEditText?.let { recognized ->
        var editedText by remember(recognized) { mutableStateOf(recognized) }
        AlertDialog(
            onDismissRequest = {
                ocrEditText = null
                ocrEditPreview = null
            },
            title = { Text(stringResource(R.string.no_coordinates_found)) },
            text = {
                Column {
                    ocrEditPreview?.let { preview ->
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = stringResource(R.string.loaded_image_description),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = DIALOG_IMAGE_MAX_HEIGHT),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    OutlinedTextField(
                        value = editedText,
                        onValueChange = { editedText = it },
                        label = { Text(stringResource(R.string.edit_recognized_text)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onOcrTextEdited(editedText)
                        ocrEditText = null
                        ocrEditPreview = null
                    },
                ) { Text(stringResource(R.string.match)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    ocrEditText = null
                    ocrEditPreview = null
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                CoordinateInputSection(
                    viewModel = viewModel,
                    hasLocationPermission = hasLocationPermission,
                    onMenuClick = { showMenu = true },
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
                    isCameraActive = isCameraActive,
                    onScanNow = {
                        // The user explicitly started scanning again: lift any file-load
                        // suspension and kick off an immediate burst. If continuous scan
                        // is enabled in settings, it resumes and the camera stays on;
                        // otherwise the camera turns back off after the first valid result.
                        viewModel.setCameraSuspended(false)
                        cameraReaderService.startScanning()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds(),
                )
            }

            // Menu dropdown - positioned relative to the button in CoordinateInputSection
            // The button is at the end of the first row, so we position the menu there
            if (showMenu) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 12.dp, top = 50.dp),
                ) {
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        // Format header with icon
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.coordinate_format),
                                    style = MaterialTheme.typography.titleSmall
                                )
                            },
                            onClick = { },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_format),
                                    contentDescription = null,
                                )
                            },
                        )

                        CoordinateFormat.entries.forEach { format ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(format.displayNameRes),
                                        color = if (format == uiState.selectedFormat)
                                            MaterialTheme.colorScheme.primary
                                        else androidx.compose.ui.graphics.Color.Unspecified
                                    )
                                },
                                onClick = {
                                    viewModel.setCoordinateFormat(format)
                                    showMenu = false
                                },
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // Continuous scan toggle with state-based icon
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.continuous_scan)) },
                            onClick = {
                                cameraReaderService.toggleAutoScan()
                                if (!autoScan) {
                                    // Toggled ON explicitly → lift file-load suspension
                                    viewModel.setCameraSuspended(false)
                                }
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(
                                        if (autoScan) R.drawable.ic_scan_on else R.drawable.ic_scan_off
                                    ),
                                    contentDescription = null,
                                )
                            },
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // Load from file with icon
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.load_from_file)) },
                            onClick = {
                                // Suspend the camera without touching the autoScan setting,
                                // so camera OCR can't overwrite the loaded coords.
                                // Resumed by "Scan Now", the scan shortcut, or re-enabling
                                // the continuous-scan toggle.
                                viewModel.setCameraSuspended(true)
                                imagePicker.launch("image/*")
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_load),
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
