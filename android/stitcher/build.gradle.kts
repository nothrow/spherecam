plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "cz.nothrow.spherecam.stitcher"
  compileSdk = 36
  ndkVersion = "30.0.16248370"
  defaultConfig {
    minSdk = 29
    ndk { abiFilters += "arm64-v8a" }
    consumerProguardFiles("consumer-rules.pro")
  }
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "4.1.2"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}
