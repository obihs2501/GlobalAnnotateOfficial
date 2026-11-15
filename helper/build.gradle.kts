plugins {
    id("com.android.application")
}

android {
    namespace = "com.globalannotate.official.helper"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.globalannotate.official.helper"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // libxposed API 102 reads java_init.list / module.prop / scope.list from
    // META-INF/xposed inside the APK. AGP must be told to keep those Java
    // resources; this mirrors the old module and the official libxposed example.
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    // Provided by the framework at runtime; never bundled into the APK.
    compileOnly("io.github.libxposed:api:102.0.0")
}
