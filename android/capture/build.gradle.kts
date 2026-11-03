plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "cz.nothrow.spherecam.capture"
  compileSdk = 36
  defaultConfig {
    minSdk = 29
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(libs.kotlinx.serialization.json)
  api(libs.kotlinx.coroutines.android)
  api(libs.androidx.camera.core)
  implementation(libs.androidx.concurrent.futures.ktx)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)

  testImplementation(libs.junit)
}
