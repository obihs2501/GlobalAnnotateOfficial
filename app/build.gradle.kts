plugins {
    id("com.android.application")
}

android {
    namespace = "com.globalannotate.official"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.globalannotate.official"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // MinimizedBallView/BrushDrawable use ContextCompat; keep the app Java-only
    // but link the core artifact the parallel views already depend on.
    implementation("androidx.core:core:1.19.0")
}
