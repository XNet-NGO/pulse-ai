plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.ksp)
  alias(libs.plugins.hilt)
}

android {
  namespace = "com.xnet.pulse"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.xnet.pulse"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
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
  implementation(project(":core-model"))
  implementation(project(":core-network"))
  implementation(project(":core-designsystem"))
  implementation(project(":feature-chat"))
  implementation(project(":feature-library"))

  // UniversalMarkdown AARs
  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
  implementation("com.atlassian.commonmark:commonmark:0.15.2")
  implementation("com.atlassian.commonmark:commonmark-ext-gfm-tables:0.15.2")
  implementation("com.atlassian.commonmark:commonmark-ext-autolink:0.15.2")
  implementation("ru.noties:jlatexmath-android:0.2.0")
  implementation("ru.noties:jlatexmath-android-font-cyrillic:0.2.0")
  implementation("ru.noties:jlatexmath-android-font-greek:0.2.0")
  implementation(libs.androidx.appcompat)

  val bom = platform(libs.androidx.compose.bom)
  implementation(bom)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material.iconsExtended)
  implementation(libs.androidx.compose.ui.tooling.preview)
  debugImplementation(libs.androidx.compose.ui.tooling)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.hilt.navigation.compose)
  implementation(libs.androidx.lifecycle.runtimeCompose)
  implementation(libs.androidx.worker)
  implementation(libs.hilt.android)
  implementation(libs.hilt.worker)
  ksp(libs.hilt.compiler)
}
