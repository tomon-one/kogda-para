import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

base {
    archivesName = "kogda-para"
}

android {
    namespace = "ru.whensclass"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.whensclass"
        minSdk = 26
        targetSdk = 37
        // Правила имени — в docs/versions.md. versionCode просто растёт:
        // по нему приложение узнаёт о новой сборке на сервере.
        versionCode = 76
        versionName = "b-Сон.0.9.6"
    }

    signingConfigs {
        create("release") {
            // Ключ и пароли лежат вне репозитория: ~/WhensClass-keys на
            // линуксе, C:/WhensClass-keys на прежней машине с Windows.
            // Потеря ключа означает, что обновить приложение у одногруппников
            // уже нельзя, — папку не удалять и держать в копии.
            // Потерять его нельзя — с другим ключом обновление не встанет
            // поверх уже установленного приложения.
            val keysDir = listOf(
                File(System.getProperty("user.home"), "WhensClass-keys"),
                File("C:/WhensClass-keys"),
            ).firstOrNull { it.isDirectory } ?: File("C:/WhensClass-keys")
            val props = gradleLocalProperties(keysDir, "keystore.properties")
            val store = props.getProperty("storeFile")
            if (store != null) {
                // В keystore.properties путь к ключу записан с той машины, где
                // файл заводили. Нет такого пути — ищем ключ по имени рядом.
                storeFile = File(store).takeIf { it.isFile } ?: File(keysDir, File(store).name)
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
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
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
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

/** Читает файл настроек рядом с проектом: адрес сервера, ключ подписи. */
fun gradleLocalProperties(root: File, name: String = "local.properties"): Properties {
    val props = Properties()
    val file = File(root, name)
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
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.glance.appwidget)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
