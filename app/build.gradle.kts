plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.adaptivecodex.quest.manager"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.adaptivecodex.quest.manager"
    minSdk = 34
    targetSdk = 34
    versionCode = 1
    versionName = "0.1"
    ndk { abiFilters += "arm64-v8a" }
  }

  externalNativeBuild {
    cmake { path = file("src/main/cpp/CMakeLists.txt") }
  }

  buildFeatures { compose = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.material3)
}
