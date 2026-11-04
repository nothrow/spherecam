plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "cz.nothrow.spherecam"
  compileSdk = 36
  defaultConfig {
    applicationId = "cz.nothrow.spherecam"
    minSdk = 29
    targetSdk = 36
    versionCode = 2
    versionName = "1.1"
    ndk { abiFilters += "arm64-v8a" }
  }

  // Credentials live in ~/.gradle/gradle.properties (spherecam.keyAlias, spherecam.storePassword,
  // spherecam.keyPassword), never in the repo. Without them the release build is simply unsigned.
  val signing = signingConfigs.create("release") {
    storeFile = file(System.getProperty("user.home") + "/.ssh/signing.jks")
    storePassword = providers.gradleProperty("spherecam.storePassword").orNull
    keyAlias = providers.gradleProperty("spherecam.keyAlias").orNull
    keyPassword = providers.gradleProperty("spherecam.keyPassword").orNull
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      if (signing.storePassword != null) signingConfig = signing
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(project(":capture"))
  implementation(platform(libs.androidx.compose.bom))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.camera.view)
  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit)
}
