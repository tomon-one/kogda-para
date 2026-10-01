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
        versionCode = 7
        versionName = "r-Байкал.1.1.0"
    }
    // Tested-сборка (docs/versions.md, «Tested»): отдельное приложение рядом с
    // основным, на нём автор проверяет новое до выкладки всем. Её номер —
    // versionCode × 100 + TESTED_TRY, имя — versionName-tTESTED_TRY: поднять
    // versionCode и versionName до будущей основной, а TESTED_TRY — с каждой
    // выложенной tested-сборкой.

    signingConfigs {
        create("release") {
            // Ключ и пароли лежат вне репозитория: ~/WhensClass-keys на
            // линуксе, C:/WhensClass-keys на Windows. Потерять ключ нельзя: с
            // другим обновление не встанет поверх установленного приложения.
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
            // Отдельный идентификатор: отладочная ставится рядом с рабочей и
            // не трогает её данные.
            applicationIdSuffix = ".debug"
            // По умолчанию отладочная сборка ходит туда же, куда и рабочая.
            // Чтобы отлаживать против сервера на своём компьютере, пропишите
            // WHENSCLASS_BASE_URL=http://localhost:8081 в local.properties и
            // сделайте `adb reverse tcp:8081 tcp:8081`: http к localhost
            // разрешает src/debug/res/xml/network_security_config.xml.
            val local = gradleLocalProperties(rootDir)
            val url = local.getProperty("WHENSCLASS_BASE_URL")
                ?: "https://kogda-para-nsk.ru"
            buildConfigField("String", "BASE_URL", "\"$url\"")
            buildConfigField("String", "CHANNEL", "\"main\"")
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
                "\"https://kogda-para-nsk.ru\"",
            )
            buildConfigField("String", "CHANNEL", "\"main\"")
        }
        create("candidate") {
            initWith(getByName("release"))
            // Свой идентификатор — ставится рядом с основным, данные у них
            // раздельные; название и виджеты — из src/candidate/res. Имя типа — не
            // «tested»: имена на «test» Gradle не разрешает.
            applicationIdSuffix = ".tested"
            // Обновления — своим каналом сервера, без GitHub.
            buildConfigField("String", "CHANNEL", "\"tested\"")
            matchingFallbacks += "release"
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

    // Интерфейс только русский: строки библиотек на 80 языках — около
    // 100 КБ в каждой загрузке.
    androidResources {
        localeFilters += listOf("ru", "en")
    }

    // Тесты — в поясе, который не колледжа: в Asia/Novosibirsk они не
    // заметили бы место, где время колледжа подменили часами телефона.
    testOptions {
        unitTests.all { it.systemProperty("user.timezone", "UTC") }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Их читает только kotlin-reflect, а его в сборке нет.
        resources.excludes += listOf("kotlin/**.kotlin_builtins", "DebugProbesKt.bin")
        // Загрузчик этой библиотеки (многопроцессный DataStore) R8 вырезает,
        // и .so лежали бы в APK впустую.
        jniLibs.excludes += "**/libdatastore_shared_counter.so"
    }
}

kotlin {
    jvmToolchain(21)
}

/** Попытка tested-сборки; см. комментарий у versionCode. */
val TESTED_TRY = 3

androidComponents {
    onVariants(selector().withBuildType("candidate")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(output.versionCode.get()!! * 100 + TESTED_TRY)
            output.versionName.set(output.versionName.get() + "-t$TESTED_TRY")
        }
    }
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
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.androidx.glance.appwidget)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
