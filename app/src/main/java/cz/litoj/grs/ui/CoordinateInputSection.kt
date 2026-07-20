package cz.litoj.grs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cz.litoj.grs.CoordinateFormat
import cz.litoj.grs.GpsSpoofViewModel
import cz.litoj.grs.LocationMocker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinateInputSection(
    viewModel: GpsSpoofViewModel,
    hasLocationPermission: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    val locationMocker = remember { LocationMocker(context) }
    var isMocking by remember { mutableStateOf(false) }

    // Display text derived from coordinates + selected format.
    // Local state allows free typing; re-syncs when coordinates change (e.g. from OCR).
    val coords = uiState.currentCoordinates
    val displayFormat =
        if (uiState.selectedFormat == CoordinateFormat.AUTO) coords?.format
            ?: CoordinateFormat.DEGREES else uiState.selectedFormat

    var latText by remember(coords?.latitude, displayFormat) {
        mutableStateOf(coords?.latitudeString(displayFormat) ?: "")
    }
    var lonText by remember(coords?.longitude, displayFormat) {
        mutableStateOf(coords?.longitudeString(displayFormat) ?: "")
    }

    // Start mocking once, then update on every coordinate change
    LaunchedEffect(uiState.currentCoordinates, hasLocationPermission) {
        val coords = uiState.currentCoordinates ?: return@LaunchedEffect
        if (!hasLocationPermission) return@LaunchedEffect
        if (!isMocking) {
            val success =
                locationMocker.startMocking(coords.latitude, coords.longitude)
            if (success) {
                isMocking = true
            } else {
                viewModel.emitMockError(
                    "Mocking not active. Click to open Settings to choose this app for location mocking."
                )
                return@LaunchedEffect
            }
        }
        locationMocker.updateMockLocation(coords.latitude, coords.longitude)
    }

    // Periodically refresh mock location so it persists for apps that poll GPS
    // (setTestProviderLocation only delivers to active listeners at call time)
    LaunchedEffect(isMocking, uiState.currentCoordinates) {
        if (!isMocking) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1000)
            val coords = uiState.currentCoordinates ?: break
            locationMocker.updateMockLocation(coords.latitude, coords.longitude)
        }
    }

    // Stop mocking when the composable leaves composition
    DisposableEffect(locationMocker) {
        onDispose { locationMocker.cleanup() }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OutlinedTextField(
            value = latText,
            onValueChange = {
                latText = it
                viewModel.updateLatitude(it)
            },
            label = { Text("Lat (N/S)") },
            singleLine = true,
            enabled = hasLocationPermission,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
        )

        OutlinedTextField(
            value = lonText,
            onValueChange = {
                lonText = it
                viewModel.updateLongitude(it)
            },
            label = { Text("Lon (E/W)") },
            singleLine = true,
            enabled = hasLocationPermission,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = uiState.selectedFormat.displayName,
                onValueChange = {},
                label = { Text("Format") },
                readOnly = true,
                enabled = hasLocationPermission,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            )

            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                CoordinateFormat.entries.forEach { format ->
                    DropdownMenuItem(
                        text = { Text(format.displayName) },
                        onClick = {
                            viewModel.setCoordinateFormat(format)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
