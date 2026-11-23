plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "io.github.lumpilabs.beamline.airplay"
  compileSdk = 37
  // Pinned so CI and every developer build the native code with the same toolchain; the
  // workflow installs exactly these.
  ndkVersion = "30.0.16248370"

  defaultConfig {
    // Fire OS 6 (Android 7.1) and newer, like the TV app.
    minSdk = 25
    ndk {
      // Fire OS 7 runs a 32-bit userland on 64-bit chips.
      abiFilters += listOf("armeabi-v7a", "arm64-v8a")
    }
    externalNativeBuild {
      cmake {
        arguments += listOf("-DANDROID_STL=c++_static")
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "4.1.2"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  testImplementation(libs.kotlin.test.junit)
}
