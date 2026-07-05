plugins {
    alias(jdcr.plugins.android.library)
    alias(jdcr.plugins.kotlin.android)
    `maven-publish`
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
    api(jdcr.jdcr.dev.base)

    api(jdcr.ktor.client.core)
    api(jdcr.ktor.client.cio) // 纯 Kotlin 引擎

    // JSON 序列化（官方）
    api(jdcr.ktor.client.negotiation)
    api(jdcr.ktor.client.serialization)

    api(jdcr.ktor.client.logging)

    api(jdcr.ktor.client.encoding)
    api(jdcr.ktor.client.auth)

    api(jdcr.jdcr.log)

    api("io.ktor:ktor-client-okhttp:2.2.4")
    api("com.squareup.okhttp3:okhttp:4.2.2")

}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"]) //release debug
                // JitPack 会自动填充 groupId 和 version，
                // 但为了本地测试，你可以保留这些：
                groupId = "com.github.jdcr"
                artifactId = "jdcrhttp"
                version = "1.0.0-SNAPSHOT"
            }
        }
    }
}