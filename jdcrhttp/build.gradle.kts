plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.jdcr.jdcrhttp"
    compileSdk = 33

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    val kotVersion = "1.6.0"
    api("androidx.core:core-ktx:$kotVersion")
    // 2. 引入协程 (建议使用 1.6.4)
    val coroutinesVersion = "1.6.4"
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    val ktorVersion = "2.3.12"
    api("io.ktor:ktor-client-core:$ktorVersion")
    api("io.ktor:ktor-client-cio:$ktorVersion") // 纯 Kotlin 引擎

    // JSON 序列化（官方）
    api("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    api("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    api("io.ktor:ktor-client-logging:$ktorVersion")

    api("com.github.ljwx:jdcrlog:1.2.2-SNAPSHOT")

    val ktor_version = "2.2.4"
    api("io.ktor:ktor-client-core:$ktor_version")
    api("io.ktor:ktor-client-cio:$ktor_version")
    api("io.ktor:ktor-client-content-negotiation:$ktor_version")
    api("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
    api("io.ktor:ktor-client-logging:$ktor_version")
    api("io.ktor:ktor-client-websockets:$ktor_version")
    api("io.ktor:ktor-client-encoding:$ktor_version")
    api("io.ktor:ktor-client-auth:$ktor_version")
}