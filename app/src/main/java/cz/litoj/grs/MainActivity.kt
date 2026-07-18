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
import cz.litoj.grs.ui.GpsSpoofScreen
import cz.litoj.grs.ui.theme.GPSReadSpoofTheme

class MainActivity : ComponentActivity() {
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val backgroundLocationGranted = permissions[Manifest.permission.ACCESS_BACKGROUND_LOCATION] ?: false
        
        // Update ViewModel with permission states
        @Suppress("DEPRECATION")
        val mockLocationGranted = packageManager.checkPermission(
            "android.permission.ACCESS_MOCK_LOCATION",
            packageName
        ) == PackageManager.PERMISSION_GRANTED
        
        viewModel.updatePermissionStates(
            cameraGranted = cameraGranted,
            locationGranted = locationGranted,
            backgroundLocationGranted = backgroundLocationGranted,
            mockLocationGranted = mockLocationGranted
        )
        
        // Check if we need to request background location separately
        if (locationGranted) {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestBackgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }
    
    private val requestBackgroundPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Update ViewModel with background permission state
        val cameraGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val locationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        @Suppress("DEPRECATION")
        val mockLocationGranted = packageManager.checkPermission(
            "android.permission.ACCESS_MOCK_LOCATION",
            packageName
        ) == PackageManager.PERMISSION_GRANTED
        
        viewModel.updatePermissionStates(
            cameraGranted = cameraGranted,
            locationGranted = locationGranted,
            backgroundLocationGranted = isGranted,
            mockLocationGranted = mockLocationGranted
        )
    }
    
    private lateinit var viewModel: GpsSpoofViewModel
    private var snackbarHostState: SnackbarHostState by mutableStateOf(SnackbarHostState())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        viewModel = GpsSpoofViewModel()
        viewModel.setAppContext(this)
        snackbarHostState = SnackbarHostState()
        
        // Request necessary permissions on startup
        requestPermissionsIfNeeded()
        
        setContent {
            GPSReadSpoofTheme {

                
                Box(modifier = Modifier.fillMaxSize()) {
                    GpsSpoofScreen(
                        viewModel = viewModel
                    )
                    SnackbarHost(
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                        hostState = snackbarHostState
                    )
                }
            }
        }
    }
    
    private fun requestPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // All basic permissions granted, check for background location
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestBackgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            
            // Update initial permission state
            @Suppress("DEPRECATION")
            val mockLocationGranted = packageManager.checkPermission(
                "android.permission.ACCESS_MOCK_LOCATION",
                packageName
            ) == PackageManager.PERMISSION_GRANTED
            
            viewModel.updatePermissionStates(
                cameraGranted = true,
                locationGranted = true,
                backgroundLocationGranted = false,
                mockLocationGranted = mockLocationGranted
            )
        }
    }
}
