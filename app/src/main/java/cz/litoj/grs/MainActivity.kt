package cz.litoj.grs

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.lifecycle.lifecycleScope
import cz.litoj.grs.ui.GrsScreen
import cz.litoj.grs.ui.theme.GPSReadSpoofTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: GpsSpoofViewModel by viewModels()
    private lateinit var cameraReaderService: CameraReaderService
    private lateinit var imageOcrAssistant: ImageOcrAssistant
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

        snackbarHostState = SnackbarHostState()

        cameraReaderService = CameraReaderService(
            context = this,
            lifecycleOwner = this,
            onTextRecognized = viewModel::onTextRecognized,
        )
        imageOcrAssistant = ImageOcrAssistant(
            context = this,
            onTextRecognized = viewModel::onTextRecognized,
            onOcrFailure = viewModel::onImageOcrFailure,
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

        // Only handle the launch intent on a genuine fresh start. On activity
        // recreation (config change / process death) savedInstanceState != null,
        // and re-processing a stale ACTION_SEND would fail — its URI grant is gone.
        if (savedInstanceState == null) {
            handleIntent(intent)
        }

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
                        onLoadImage = ::loadImage,
                    )
                    SnackbarHost(
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                        hostState = snackbarHostState,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Decode + OCR an image from the gallery picker or a share intent. */
    private fun loadImage(uri: Uri) {
        lifecycleScope.launch {
            imageOcrAssistant.recognizeFromUri(uri)
        }
    }

    private fun handleIntent(intent: Intent?) {
        when {
            intent?.action == ACTION_SCAN_AND_MOCK ->
                viewModel.setPendingScan(true)

            intent?.action == Intent.ACTION_SEND &&
                intent.type?.startsWith("image/") == true -> {
                extractSharedImageUri(intent)?.let(::loadImage)
                    ?: Log.w(TAG, "ACTION_SEND without image stream")
                // Consume the intent so a later re-delivery can't re-trigger it.
                intent.action = null
            }
        }
    }

    private fun extractSharedImageUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    override fun onDestroy() {
        super.onDestroy()
        cameraReaderService.stop()
        imageOcrAssistant.close()
    }

    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_SCAN_AND_MOCK = "cz.litoj.grs.ACTION_SCAN_AND_MOCK"
    }
}
