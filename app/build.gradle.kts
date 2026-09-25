import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 统一签名：读取仓库内 keystore/keystore.properties，让 debug 与 release 共用同一把密钥，
// 保证每次 CI 构建产出的 APK 签名一致、可互相覆盖安装。缺失配置时回退到默认行为。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.azcode.bridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.azcode.bridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 69
        versionName = "1.9.14"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("azcode") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("azcode")
        }
        debug {
            // 与 release 使用同一签名，避免每次构建都生成随机 debug 密钥导致无法覆盖安装。
            signingConfigs.findByName("azcode")?.let { signingConfig = it }
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
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:inline-parser:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:ext-latex:4.6.2")
    implementation("ru.noties:jlatexmath-android:0.2.0")
    implementation("ru.noties:jlatexmath-android-font-greek:0.2.0")
    implementation("ru.noties:jlatexmath-android-font-cyrillic:0.2.0")
}
