package cz.litoj.grs

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import cz.litoj.grs.ui.GrsScreen
import cz.litoj.grs.ui.theme.GPSReadSpoofTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: GpsSpoofViewModel
    private lateinit var cameraReaderService: CameraReaderService
    private var snackbarHostState: SnackbarHostState by mutableStateOf(
        SnackbarHostState()
    )

    private var hasLocationPermission by mutableStateOf(false)
    private var hasCameraPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        hasLocationPermission =
            results[Manifest.permission.ACCESS_FINE_LOCATION] ?: hasLocationPermission
        hasCameraPermission =
            results[Manifest.permission.CAMERA] ?: hasCameraPermission
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel = GpsSpoofViewModel()
        snackbarHostState = SnackbarHostState()

        cameraReaderService = CameraReaderService(
            context = this,
            lifecycleOwner = this,
            onTextRecognized = viewModel::onTextRecognized,
        )

        // Check current permission states
        hasLocationPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        // Request both permissions together on first launch.
        // Launching two separate permission requests simultaneously causes the
        // second dialog to be silently dropped by the Activity Result API.
        val toRequest = mutableListOf<String>()
        if (!hasLocationPermission) toRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!hasCameraPermission) toRequest.add(Manifest.permission.CAMERA)
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }

        handleIntent(intent)

        setContent {
            GPSReadSpoofTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    GrsScreen(
                        viewModel = viewModel,
                        cameraReaderService = cameraReaderService,
                        hasLocationPermission = hasLocationPermission,
                        hasCameraPermission = hasCameraPermission,
                        onRequestCameraPermission = {
                            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                        },
                    )
                    SnackbarHost(
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                        hostState = snackbarHostState,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.action == ACTION_SCAN_AND_MOCK) {
            viewModel.setPendingScan(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraReaderService.stop()
    }

    companion object {
        const val ACTION_SCAN_AND_MOCK = "cz.litoj.grs.ACTION_SCAN_AND_MOCK"
    }
}
