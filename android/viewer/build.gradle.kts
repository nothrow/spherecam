plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "cz.nothrow.spherecam.viewer"
  compileSdk = 36
  defaultConfig {
    minSdk = 29
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material3)

  testImplementation(libs.junit)
}
