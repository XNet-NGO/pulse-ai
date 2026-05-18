pluginManagement {
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    flatDir { dirs("${rootProject.projectDir}/app/libs") }
  }
}
rootProject.name = "aio-pulse"
include(":app")
include(":core-model")
include(":core-network")
include(":core-designsystem")
include(":feature-chat")
include(":feature-library")
