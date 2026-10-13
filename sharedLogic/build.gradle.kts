import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.buildkonfig)
    // kotlinx-serialization codegen for `DocumentAttachment` (persisted via Room JSON
    // TypeConverter). The json *runtime* was already on the classpath via openai-client;
    // this is the compiler plugin that makes `@Serializable` work in commonMain.
    alias(libs.plugins.kotlinSerialization)
}

buildkonfig {
    packageName = "ai.roleswitch.android"

    defaultConfigs {
    }
}

kotlin {
    compilerOptions {
        // Room's KSP processor generates `actual object` companions for
        // `expect object ... : RoomDatabaseConstructor<...>`. expect/actual
        // for classes is still Beta in Kotlin, so the generated file would
        // otherwise emit KT-61573 on every target. See:
        // https://youtrack.jetbrains.com/issue/KT-61573
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        namespace = "ai.roleswitch.android.shared"
    }

    val xcFramework = XCFramework("SharedLogic")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedLogic"
            isStatic = true
            // Export dependencies so Swift can access their types directly.
            export(libs.kotlinx.coroutines.core)
            export(libs.kotlinx.datetime)
            export(libs.kermit)
            xcFramework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api + export(...) (see the framework block): types from these
            // appear in the exported ObjC header and in public signatures.
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            api(libs.kermit)
            // api (not exported): AppDatabase is a public RoomDatabase subclass,
            // so Android consumers need room-runtime on their compile classpath.
            api(libs.room.runtime)
            // Internal implementation details — not part of the public surface.
            implementation(libs.datastore.preferences.core)
            implementation(libs.datastore.core.okio)
            implementation(libs.openai.client)
            implementation(libs.ktor.client.core)
            // JSON for the hand-written MCP client (McpService). Already on the
            // classpath via openai-client; declared explicitly because we build and
            // parse JSON-RPC envelopes directly rather than borrowing its transitive.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.okhttp)
            // PDF text extraction — androidMain actual of `PdfTextExtractor`. The iOS actual
            // is the system PDFKit framework, so no dependency is needed there.
            implementation(libs.pdfbox.android)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqlite.bundled)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}

ksp {
    arg("room.schemaLocation", "${projectDir}/schemas")
}
