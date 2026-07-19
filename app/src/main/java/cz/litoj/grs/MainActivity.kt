package cz.litoj.grs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cz.litoj.grs.ui.GrsScreen
import cz.litoj.grs.ui.theme.GPSReadSpoofTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: GpsSpoofViewModel
    private lateinit var cameraReaderService: CameraReaderService
    private var snackbarHostState: SnackbarHostState by mutableStateOf(
        SnackbarHostState()
    )

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

        handleIntent(intent)

        setContent {
            GPSReadSpoofTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    GrsScreen(
                        viewModel = viewModel,
                        cameraReaderService = cameraReaderService,
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
