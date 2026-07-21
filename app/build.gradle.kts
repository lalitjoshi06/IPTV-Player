plugins {
    id("com.android.application")
}

import java.util.Properties
import java.io.FileInputStream

android {
    namespace = "com.mpdplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mpdplayer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val propFile = project.file("key.properties")
            if (propFile.exists()) {
                props.load(FileInputStream(propFile))
                storeFile = project.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (project.file("key.properties").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            // dex2oat hints: baseline-prof.txt at src/main/ is auto-packaged into the APK
            // Platform reads it at install time to AOT-compile critical paths
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.10.1")
    implementation("androidx.leanback:leanback:1.2.0")
    implementation("androidx.leanback:leanback-preference:1.2.0")
    implementation("com.github.bumptech.glide:glide:5.0.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}

