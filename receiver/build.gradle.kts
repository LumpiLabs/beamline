plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "io.github.lumpilabs.beamline.receiver"
  compileSdk = 37

  defaultConfig {
    applicationId = "io.github.lumpilabs.beamline.receiver"
    // Fire OS 6 (Android 7.1) and newer.
    minSdk = 25
    targetSdk = 37
    versionCode = 1
    versionName = "0.0.1"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  buildFeatures {
    compose = true
  }
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation(project(":protocol"))

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.tv.material)
  implementation(libs.androidx.compose.ui.tooling.preview)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
