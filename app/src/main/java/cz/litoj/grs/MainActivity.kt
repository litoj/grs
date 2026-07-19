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
import cz.litoj.grs.ui.GpsSpoofScreen
import cz.litoj.grs.ui.theme.GPSReadSpoofTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: GpsSpoofViewModel
    private lateinit var cameraController: CameraController
    private var snackbarHostState: SnackbarHostState by mutableStateOf(
        SnackbarHostState()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel = GpsSpoofViewModel()
        snackbarHostState = SnackbarHostState()

        cameraController = CameraController(
            context = this,
            lifecycleOwner = this,
            onTextRecognized = viewModel::onTextRecognized,
        )

        setContent {
            GPSReadSpoofTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    GpsSpoofScreen(
                        viewModel = viewModel,
                        cameraController = cameraController,
                    )
                    SnackbarHost(
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                        hostState = snackbarHostState,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraController.stop()
    }
}
