import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "se.kinosthlm.app"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "se.kinosthlm.app"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Trakt credentials. These are a public app identifier, not a secret in the security sense
    // (every Trakt client ships them), but they are per-developer: register your own app at
    // https://trakt.tv/oauth/applications and set them in local.properties, which is gitignored.
    // Leaving them empty simply disables the Trakt tab; CSV import still works.
    val secrets = Properties().apply {
      val file = rootProject.file("local.properties")
      if (file.exists()) file.inputStream().use { load(it) }
    }
    buildConfigField(
      "String",
      "TRAKT_CLIENT_ID",
      "\"${System.getenv("TRAKT_CLIENT_ID") ?: secrets.getProperty("TRAKT_CLIENT_ID", "")}\"",
    )
    buildConfigField(
      "String",
      "TRAKT_CLIENT_SECRET",
      "\"${System.getenv("TRAKT_CLIENT_SECRET") ?: secrets.getProperty("TRAKT_CLIENT_SECRET", "")}\"",
    )
    // TMDB identifies bare titles from a Google TV export: film or series, which year, which of
    // two same-named films. Free key from https://www.themoviedb.org/settings/api. Without it
    // the app still imports and matches; those titles just stay unidentified.
    buildConfigField(
      "String",
      "TMDB_API_KEY",
      "\"${System.getenv("TMDB_API_KEY") ?: secrets.getProperty("TMDB_API_KEY", "")}\"",
    )
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Debug-signed by default so anyone can build an installable APK without a
      // Play Console account or a keystore. See README.
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      // android.util.Log and friends are stubs on the JVM; without this every call throws
      // instead of quietly doing nothing, which fails tests over a log line.
      isReturnDefaultValues = true
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.coil.compose)
  implementation(libs.jsoup)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.json.jvm)
  // Room needs a real SQLite to test DAOs against; Robolectric provides one on the JVM so the
  // watchlist-provenance tests don't need a device or emulator.
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.room.testing)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)

  "ksp"(libs.androidx.room.compiler)
}

// Without this, Kotlin follows whatever JDK runs Gradle (25 in CI) and emits class files
// Robolectric's bundled ASM cannot parse ("IllegalArgumentException" from ClassReader).
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}

// Robolectric's instrumenting classloader also parses the *running* JDK's own class files (to
// resolve common superclasses) — on JDK 25 those are class file version 69, newer than
// Robolectric 4.14.1's bundled ASM understands. Run unit tests on an older, provisioned JDK
// instead of whatever JDK happens to run Gradle.
tasks.withType<Test>().configureEach {
  javaLauncher.set(
    javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
  )
}
