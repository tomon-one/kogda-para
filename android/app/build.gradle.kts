import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ru.whensclass"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.whensclass"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        debug {
            // По умолчанию отладочная сборка ходит туда же, куда и рабочая.
            // Чтобы отлаживать против сервера на своём компьютере, пропишите
            // WHENSCLASS_BASE_URL в local.properties — тогда пригодится
            // разрешение на http из src/debug/res/xml/network_security_config.xml.
            val local = gradleLocalProperties(rootDir)
            val url = local.getProperty("WHENSCLASS_BASE_URL")
                ?: "https://schedule.edelweiss-alpine-confederation.ru"
            buildConfigField("String", "BASE_URL", "\"$url\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField(
                "String",
                "BASE_URL",
                "\"https://schedule.edelweiss-alpine-confederation.ru\"",
            )
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(21)
}

/** Читает local.properties: адрес сервера на время отладки в домашней сети. */
fun gradleLocalProperties(root: File): Properties {
    val props = Properties()
    val file = File(root, "local.properties")
    if (file.exists()) file.inputStream().use { props.load(it) }
    return props
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
