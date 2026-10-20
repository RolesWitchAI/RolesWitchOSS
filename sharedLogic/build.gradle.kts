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
    // Line coverage of the JVM-compiled shared code (commonMain + jvmMain), measured
    // from jvmTest. Kover cannot be used here: it requires the classic `android`
    // extension, which the AGP Kotlin Multiplatform library plugin does not create.
    // JaCoCo cannot instrument Kotlin/Native either, so the iOS targets' tests run
    // green but report no numbers; androidMain is executed by androidHostTest.
    jacoco
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
        // Host (JVM) unit tests for the androidMain actuals — without this block the
        // AGP KMP library plugin creates no android host test compilation at all, and
        // commonTest would be the only shared test source set.
        withHostTest {
            isReturnDefaultValues = true
        }
    }

    // JVM target for the shared tests: commonTest runs here (fast, no simulator) and
    // jvmTest additionally exercises the real Room database through the bundled SQLite
    // driver — the same setup iosMain uses. jvmMain carries only the PdfTextExtractor
    // stub, so the JVM compilation is effectively "commonMain, measured".
    jvm()

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
            implementation(libs.kotlinx.coroutines.test)
            // MockEngine — the fake OpenAI-compatible / MCP / TTS / HuggingFace servers
            // the service tests drive. Multiplatform, so the same fixtures run on JVM
            // and iOS.
            implementation(libs.ktor.client.mock)
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
        jvmMain.dependencies {
            // The default Ktor engine for this target (the same choice androidMain
            // makes), so a bare `HttpClient { }` resolves outside Android/iOS too.
            implementation(libs.ktor.okhttp)
            // Driver for the Room tests in jvmTest (same role sqlite-bundled plays for
            // iosMain; the Android target uses the framework driver instead).
            implementation(libs.sqlite.bundled)
        }
        // Platform actuals: iOS (PDFKit extractor, NSData interop, factories) run in
        // iosSimulatorArm64Test; Android (PDFBox extractor) in the android host test.
        // Neither is Kover-measurable — they exist to execute the actuals, not to feed
        // the coverage number.
        iosTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val androidHostTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
    // Room's generated `AppDatabaseConstructor` actual for the JVM target (jvmTest
    // opens real databases).
    add("kspJvm", libs.room.compiler)
}

ksp {
    arg("room.schemaLocation", "${projectDir}/schemas")
}

// Line coverage of the shared code as compiled for the JVM target (commonMain +
// jvmMain merged — KMP compiles both into one classes directory). Room's KSP-generated
// DAO/schema adapters (`*_Impl`, insert/delete adapters, open delegates) are excluded:
// they are generated SQL plumbing, not source, and their per-query bind switches would
// dominate the denominator. The report lives at
// build/reports/jacoco/sharedCoverage/html/index.html; the XML is for tooling.
//
// Platform actuals are deliberately outside the measurement: androidMain/iosMain are
// not part of the JVM compilation, androidHostTest/iosSimulatorArm64Test execute them
// separately, and Kotlin/Native has no JaCoCo support at all.
val sharedCoverage = tasks.register<JacocoReport>("sharedCoverage") {
    dependsOn(tasks.named("jvmTest"))
    executionData(file("${layout.buildDirectory.get()}/jacoco/jvmTest.exec"))
    classDirectories.setFrom(
        fileTree("${layout.buildDirectory.get()}/classes/kotlin/jvm/main") {
            exclude(
                // Room's KSP-generated SQL plumbing.
                "**/*_Impl*.class", "**/*EntityInsertAdapter*.class",
                "**/*EntityDeletionAdapter*.class", "**/*EntityUpdateAdapter*.class",
                // Compiler-generated bridges that no Kotlin 2.x call site can reach:
                // interface default-arg synthetics and the expect-object's platform
                // bridge (the KSP-generated actual is what runs).
                "**/InferenceEngine\$DefaultImpls.class", "**/AppDatabaseConstructor.class"
            )
        }
    )
    sourceDirectories.setFrom(files("src/commonMain/kotlin", "src/jvmMain/kotlin"))
    reports {
        xml.required.set(true)
        csv.required.set(true)
        html.required.set(true)
    }
}
