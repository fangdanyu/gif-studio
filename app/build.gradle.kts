import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gifstudio.editor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gifstudio.editor"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        resourceConfigurations += listOf("zh", "en")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名信息不写进仓库：从 local.properties（已被 .gitignore 忽略）或环境变量读取
    val localProps = Properties()
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { stream -> localProps.load(stream) }
    }
    fun secret(propKey: String, envKey: String): String? =
        (localProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

    val storePath = secret("gifstudio.storeFile", "GIFSTUDIO_STORE_FILE")
    val keystoreFile = if (storePath != null) rootProject.file(storePath) else rootProject.file("keystore/gifstudio-release.jks")
    val storePass = secret("gifstudio.storePassword", "GIFSTUDIO_STORE_PASSWORD")
    val keyAliasValue = secret("gifstudio.keyAlias", "GIFSTUDIO_KEY_ALIAS") ?: "gifstudio"
    val keyPass = secret("gifstudio.keyPassword", "GIFSTUDIO_KEY_PASSWORD") ?: storePass
    val hasSigning = keystoreFile.exists() && storePass != null

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = keyAliasValue
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
