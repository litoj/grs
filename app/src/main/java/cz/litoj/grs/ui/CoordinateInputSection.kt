package cz.litoj.grs.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.core.content.ContextCompat
import cz.litoj.grs.CoordinateFormat
import cz.litoj.grs.GpsSpoofViewModel
import cz.litoj.grs.LocationMocker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinateInputSection(
    viewModel: GpsSpoofViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPermission = granted
    }

    // Auto-request location permission on first composition if not yet granted
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val locationMocker = remember { LocationMocker(context) }
    var isMocking by remember { mutableStateOf(false) }

    // Start mocking once, then update on every coordinate change
    LaunchedEffect(uiState.currentCoordinates, hasLocationPermission) {
        val coords = uiState.currentCoordinates ?: return@LaunchedEffect
        if (!hasLocationPermission) return@LaunchedEffect
        if (!isMocking) {
            val success = locationMocker.startMocking(coords.latitude, coords.longitude)
            if (success) {
                isMocking = true
            } else {
                viewModel.emitMockError(
                    "Mock location not set up. Select this app as mock location app in Developer Options."
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
            value = uiState.latitudeText,
            onValueChange = viewModel::updateLatitudeText,
            label = { Text("Lat (N/S)") },
            singleLine = true,
            enabled = hasLocationPermission,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
        )

        OutlinedTextField(
            value = uiState.longitudeText,
            onValueChange = viewModel::updateLongitudeText,
            label = { Text("Lon (E/W)") },
            singleLine = true,
            enabled = hasLocationPermission,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.width(130.dp),
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
                    .menuAnchor()
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
