plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.ksp)
  alias(libs.plugins.hilt)
}

import java.util.Properties

val secrets = Properties().apply {
  rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
  namespace = "com.xnet.pulse"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.xnet.pulse"
    minSdk = 26
    targetSdk = 35
    versionCode = 3
    versionName = "1.2.0"
    buildConfigField("String", "GATEWAY_KEY", "\"${secrets.getProperty("GATEWAY_KEY", "")}\"")
  }

  buildFeatures {
    buildConfig = true
    compose = true
  }

  signingConfigs {
    create("release") {
      storeFile = rootProject.file("../xnet-keystore/xnet-upload.keystore")
      storePassword = "UploadXnet2026!"
      keyAlias = "xnet-upload"
      keyPassword = "UploadXnet2026!"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      isShrinkResources = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
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

dependencies {
  implementation(project(":core-model"))
  implementation(project(":core-network"))
  implementation(project(":core-designsystem"))
  implementation(project(":feature-chat"))
  implementation(libs.coil.compose)
  implementation(libs.coil.svg)
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
  implementation("androidx.core:core-splashscreen:1.0.1")

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
