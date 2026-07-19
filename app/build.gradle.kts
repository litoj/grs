import java.io.FileInputStream
import java.util.Properties

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cz.litoj.grs"
	compileSdk {
		version = release(37) {
			minorApiLevel = 1
		}
	}

	defaultConfig {
		applicationId = "cz.litoj.grs"
		minSdk = 23
		targetSdk = 37
		versionCode = 1
		versionName = "1.0"

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	// Release signing config — loaded from keystore.properties (not in VCS).
	// If the file is absent (e.g. CI / F-Droid builds), the release type
	// falls back to the debug signing config so the build still works.
	val keystoreProperties = Properties()
	val keystoreFile = rootProject.file("keystore.properties")
	if (keystoreFile.exists()) {
		keystoreProperties.load(FileInputStream(keystoreFile))
	}

	signingConfigs {
		create("release") {
			if (keystoreProperties.containsKey("storeFile")) {
				storeFile = file(keystoreProperties.getProperty("storeFile"))
				storePassword = keystoreProperties.getProperty("storePassword")
				keyAlias = keystoreProperties.getProperty("keyAlias")
				keyPassword = keystoreProperties.getProperty("keyPassword")
			}
		}
	}

	buildTypes {
		release {
			optimization {
				enable = true
			}
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
			)
			signingConfig = signingConfigs.getByName("release")
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}
	buildFeatures {
		compose = true
	}
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.compose.material3)

	// CameraX
	implementation(libs.androidx.camera.camera2)
	implementation(libs.androidx.camera.lifecycle)
	implementation(libs.androidx.camera.view)
	
	// ML Kit Text Recognition
	implementation(libs.mlkit.text.recognition)
	
	testImplementation(libs.junit)
}