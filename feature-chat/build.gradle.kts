plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.ksp)
  alias(libs.plugins.hilt)
}

android {
  namespace = "com.xnet.pulse.feature.chat"
  compileSdk = 36
  defaultConfig { minSdk = 26 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
}

dependencies {
  implementation(project(":core-model"))
  implementation(project(":core-network"))
  implementation(project(":core-designsystem"))

  val bom = platform(libs.androidx.compose.bom)
  implementation(bom)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material.iconsExtended)
  implementation(libs.androidx.lifecycle.viewModelCompose)
  implementation(libs.androidx.lifecycle.runtimeCompose)
  implementation(libs.androidx.hilt.navigation.compose)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  implementation(libs.androidx.datastore)
  implementation("androidx.media3:media3-exoplayer:1.6.1")
  implementation("androidx.media3:media3-ui:1.6.1")
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)
  implementation(libs.okhttp)
  implementation(libs.okhttp.sse)
  implementation(libs.jtokkit)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.androidx.worker)
  implementation(libs.hilt.worker)
  implementation(libs.coil.compose)
  implementation(libs.coil.svg)
  implementation("com.caverock:androidsvg-aar:1.4")
  implementation(libs.pdfbox.android)
  implementation(libs.poi.ooxml)
  implementation(libs.opencsv)

  // UniversalMarkdown (AARs in :app, classes available via transitive)
  compileOnly(files("../app/libs/universal-markdown-compose.aar"))
  compileOnly(files("../app/libs/markwon-core.aar"))
  implementation("com.atlassian.commonmark:commonmark:0.15.2")
  implementation(libs.androidx.appcompat)
}
