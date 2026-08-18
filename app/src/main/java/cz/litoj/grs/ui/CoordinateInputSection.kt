package cz.litoj.grs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
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
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cz.litoj.grs.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.TextButton
import cz.litoj.grs.CoordinateFormat
import cz.litoj.grs.GpsSpoofViewModel
import cz.litoj.grs.LocationMocker

@Composable
fun CoordinateInputSection(
    viewModel: GpsSpoofViewModel,
    hasLocationPermission: Boolean,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

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
                viewModel.emitMockError()
                return@LaunchedEffect
            }
        }
        locationMocker.updateMockLocation(coords.latitude, coords.longitude)
        // Launch the user's selected app so they can verify the mocked location
        uiState.targetApp?.let { targetApp ->
            val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                component = targetApp
            }
            context.startActivity(launchIntent)
        }
    }

    // Periodically refresh mock location so it persists for apps that poll GPS
    // (setTestProviderLocation only delivers to active listeners at call time)
    LaunchedEffect(isMocking, uiState.currentCoordinates) {
        if (!isMocking) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1000L)
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = latText,
            onValueChange = {
                latText = it
                viewModel.updateLatitude(it)
            },
            label = { Text(stringResource(R.string.lat_label)) },
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
            label = { Text(stringResource(R.string.lon_label)) },
            singleLine = true,
            enabled = hasLocationPermission,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
        )

        // Overflow menu button
        TextButton(
            onClick = onMenuClick,
            enabled = hasLocationPermission,
        ) {
            Text(
                text = "⋮",
                style = TextStyle(fontSize = 24.sp),
            )
        }
    }
}


