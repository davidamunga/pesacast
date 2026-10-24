plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pesacast.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pesacast.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 701
        versionName = "0.7.1"
    }

    signingConfigs {
        create("release") {
            storeFile = System.getenv("ANDROID_KEYSTORE_PATH")?.let { file(it) }
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // PesaCast is a phone-only app that requires SMS; declaring telephony as optional
        // would widen Play Store availability to devices that cannot receive M-PESA SMS.
        disable += "PermissionImpliesUnsupportedChromeOsHardware"
    }

    testOptions {
        // Parser code logs through android.util.Log; return defaults instead of throwing on the JVM.
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.recyclerview)
    implementation(libs.activity.ktx)
    implementation(libs.okhttp)
    implementation(libs.java.websocket)

    testImplementation(libs.junit)
}
