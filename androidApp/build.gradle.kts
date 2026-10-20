plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "ai.roleswitch.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "ai.roleswitch.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 12
        versionName = "1.1"

        // Live2D Cubism Core ships as static libs for these two ABIs only.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    signingConfigs {
        create("release") {
            // Credentials live in ~/.gradle/gradle.properties (never in the repo):
            //   roleswitchStoreFile / roleswitchStorePassword / roleswitchKeyAlias /
            //   roleswitchKeyPassword. The keystore itself also lives outside the
            // repo. Without these properties (fresh clone) the release bundle is
            // simply left unsigned.
            (findProperty("roleswitchStoreFile") as String?)?.let { storePath ->
                val store = rootProject.file(storePath)
                if (store.exists()) {
                    storeFile = store
                    storePassword = findProperty("roleswitchStorePassword") as String
                    keyAlias = findProperty("roleswitchKeyAlias") as String
                    keyPassword = findProperty("roleswitchKeyPassword") as String
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates the stress seed in SeedDefaults.kt. Off by default
        // since AGP 8, so it has to be requested explicitly.
        buildConfig = true
    }

    // Live2D Cubism (characters). The SDK is vendored at the repo root — the same
    // layout the RolesWitchAndroid repo used, which the CMakeLists' relative path
    // (`../../../../cubism/...`) depends on. Pinned NDK/CMake match that repo.
    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":sharedLogic"))

    implementation(libs.androidx.core.ktx)
    // themes.xml uses Theme.AppCompat; previously satisfied only transitively.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // LocalLifecycleOwner (androidx.lifecycle.compose) in CameraPreview.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    // Direct dependency: the whole UI layer launches coroutines; don't rely on
    // :sharedLogic exposing a transitive version.
    implementation(libs.kotlinx.coroutines.android)
    // McpLiteRtTools renders McpToolSpec.parametersSchema (a kotlinx JsonObject) into
    // the OpenApiTool description, so the type must be on this module's classpath too.
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Markdown Renderer
    implementation(libs.compose.markdown)

    // Navigation
    implementation(libs.navigation.compose)

    // ExifInterface
    implementation(libs.exifinterface)

    // Image cropper (square avatar crop)
    implementation(libs.android.image.cropper)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Vosk offline speech recognition
    implementation(libs.vosk.android)

    // LiteRT-LM on-device inference. Tools are declared via OpenApiTool, not
    // `@Tool`-method reflection, so kotlin-reflect is not needed on the classpath.
    implementation(libs.litertlm)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
