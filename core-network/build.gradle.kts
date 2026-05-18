plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.ksp)
  alias(libs.plugins.hilt)
}

android {
  namespace = "com.xnet.pulse.core.network"
  compileSdk = 36
  defaultConfig { minSdk = 26 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation(project(":core-model"))
  implementation(libs.okhttp)
  implementation(libs.okhttp.sse)
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)
  implementation(libs.kotlinx.coroutines.android)
}
