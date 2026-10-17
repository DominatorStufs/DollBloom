import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Signing details are kept out of tracked source in the root-level
 * `keystore.properties` (see keystore.properties.example). CI creates this file
 * from GitHub Actions secrets at build time. Without it, release builds remain
 * unsigned rather than failing.
 */
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
/*
 * Last.fm application credentials: the API key of a registered Last.fm
 * application and its matching shared secret. Neither is an account password.
 *
 * Selected as COMPLETE PAIRS, one source at a time. The old
 * `localProps.getProperty(name) ?: System.getenv(name)` per-field fallback let
 * an *empty* local property hide a valid environment value (getProperty
 * answers "" rather than null for `LASTFM_API_KEY=`), and let one source's key
 * be combined with the other source's secret — a pair Last.fm answers with a
 * signature error that reads exactly like a rejected password. So:
 *
 *  - both fields are trimmed, and a null becomes empty;
 *  - a complete local.properties pair wins;
 *  - otherwise a complete process-environment pair is used, even when the
 *    local entries are blank or partial;
 *  - a partial pair from either source is rejected whole — never completed
 *    with the other source's half;
 *  - fully absent credentials are supported: the build proceeds and the app
 *    offers in-app Last.fm API setup (see LastfmCredentials / AccountAlerts).
 *
 * Only status and setting *names* are logged here, never values.
 */
fun lastfmTrim(value: String?): String = value?.trim().orEmpty()
val lastfmLocalKey = lastfmTrim(localProps.getProperty("LASTFM_API_KEY"))
val lastfmLocalSecret = lastfmTrim(localProps.getProperty("LASTFM_SECRET"))
val lastfmEnvKey = lastfmTrim(System.getenv("LASTFM_API_KEY"))
val lastfmEnvSecret = lastfmTrim(System.getenv("LASTFM_SECRET"))

/**
 * The first complete pair among (local, env), or an empty pair when neither
 * source is complete.
 *
 * A function returning a value rather than a `when` that assigns one: a Kotlin
 * *script* may not assign a top-level `val` from inside a branch ("Captured
 * member values initialization is forbidden due to possible reassignment"), and
 * the branch form is the natural thing to write here. See the comment above for
 * why the selection is per-source and complete-pair-only.
 */
fun lastfmCompletePair(
    localKey: String,
    localSecret: String,
    envKey: String,
    envSecret: String,
): Pair<String, String> =
    when {
        localKey.isNotEmpty() && localSecret.isNotEmpty() -> localKey to localSecret
        envKey.isNotEmpty() && envSecret.isNotEmpty() -> envKey to envSecret
        else -> "" to ""
    }

val (lastfmApiKey, lastfmSecret) =
    lastfmCompletePair(lastfmLocalKey, lastfmLocalSecret, lastfmEnvKey, lastfmEnvSecret)

if (lastfmApiKey.isNotEmpty() && lastfmSecret.isNotEmpty()) {
    logger.lifecycle("Last.fm credentials in APK: configured")
} else {
    val partialLocal = listOf(
        "LASTFM_API_KEY".takeIf { lastfmLocalKey.isNotEmpty() },
        "LASTFM_SECRET".takeIf { lastfmLocalSecret.isNotEmpty() },
    ).filterNotNull()
    val partialEnv = listOf(
        "LASTFM_API_KEY".takeIf { lastfmEnvKey.isNotEmpty() },
        "LASTFM_SECRET".takeIf { lastfmEnvSecret.isNotEmpty() },
    ).filterNotNull()
    if (partialLocal.isNotEmpty()) {
        logger.warn(
            "local.properties holds ${partialLocal.joinToString(", ")} without a complete pair; " +
                "ignoring the partial pair rather than mixing it with the environment. Values are not logged.",
        )
    }
    if (partialEnv.isNotEmpty()) {
        logger.warn(
            "The process environment holds ${partialEnv.joinToString(", ")} without a complete pair; ignoring it. " +
                "Values are not logged.",
        )
    }
    logger.warn(
        "Last.fm credentials in APK: not configured. The build still succeeds; the app offers in-app " +
            "Last.fm API setup, and CI can supply LASTFM_API_KEY/LASTFM_SECRET as repository secrets.",
    )
}

/** A Kotlin value as a generated-Java string literal, escaping what would break one. */
fun javaStringLiteral(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t") + "\""

/*
 * Where Listen Together's party server lives. Not a credential — it is a public
 * URL, and every device in a party has to be pointed at the same one — but it is
 * deployment-specific rather than a property of the source, which is what puts
 * it here beside the others instead of in a constant.
 *
 * Empty is a supported state, not a broken build: the field below is only the
 * *default* the address box on the Listen Together screen starts with, and
 * anything typed there wins and persists. So a fresh checkout without this line
 * builds and runs, and simply asks for an address the first time somebody opens
 * the screen. See ListenTogether.DEFAULT_SERVER.
 *
 * The value shipped here is the upstream BitChord project's own party server,
 * and DollBloom points at it because a party needs *somewhere* to meet and this
 * fork has not deployed a backend of its own. It is not a DollBloom service and
 * carries no promise from anyone: deploy your own (backend/ has it, and
 * render.yaml describes the shape) and put the address here or in
 * local.properties, and the dialog that asks whether to switch servers will offer
 * it from then on.
 */
val listenTogetherServer: String = (
    localProps.getProperty("LISTEN_TOGETHER_SERVER")?.takeIf { it.isNotBlank() }
        ?: System.getenv("LISTEN_TOGETHER_SERVER")?.takeIf { it.isNotBlank() }
        ?: "https://bitchord-listen-together.onrender.com"
    ).trim().trimEnd('/')

/*
 * Direct/local builds keep these defaults. The manual Android workflow passes
 * appVersionCode and appVersionName as Gradle properties. Version name stays a
 * string, so values such as "1.8-beta2" or "DollBloom2026" are supported.
 */
val configuredVersionCode = providers.gradleProperty("appVersionCode").orNull
val appVersionCode = if (configuredVersionCode == null) {
    22
} else {
    val parsed = configuredVersionCode.toIntOrNull()
        ?: throw GradleException("appVersionCode must be a positive integer")
    if (parsed !in 1..2_100_000_000) {
        throw GradleException("appVersionCode must be between 1 and 2100000000")
    }
    parsed
}
val configuredVersionName = providers.gradleProperty("appVersionName").orNull
val appVersionName = configuredVersionName ?: "1.7"
if (!Regex("[A-Za-z0-9][A-Za-z0-9 ._+-]{0,63}").matches(appVersionName)) {
    throw GradleException("appVersionName must be 1-64 letters/digits, spaces, dots, underscores, pluses, or hyphens")
}
val betaSuffix = ""

android {
    namespace = "com.doll.bloom"
    // InnerTubeX's AAR requires compiling against 37; targetSdk (runtime behaviour) stays 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.doll.bloom"
        // 26 keeps reach wide; real-time blur (RenderEffect) kicks in on API 31+,
        // Haze falls back to a translucent scrim below that.
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Last.fm credentials are supplied locally and never committed. The
        // literal escape covers newlines/tabs too: a properties file may carry
        // them in escaped form, and a raw one inside a generated Java string
        // literal is a broken BuildConfig, not a credential.
        buildConfigField("String", "LASTFM_API_KEY", javaStringLiteral(lastfmApiKey))
        buildConfigField("String", "LASTFM_SECRET", javaStringLiteral(lastfmSecret))
        buildConfigField(
            "String",
            "LISTEN_TOGETHER_SERVER",
            javaStringLiteral(listenTogetherServer),
        )
    }

    lint {
        // These manifest-registered Kotlin components correctly extend their AndroidX
        // component base classes, but the current lint run misreports them as invalid.
        // Suppress only this false-positive issue; keep the remaining release lint checks.
        disable += "Instantiatable"
    }

    splits {
        abi {
            isEnable = true
            reset()
            // Phone builds only: 32-bit ARM, 64-bit ARM, plus one universal
            // APK containing both. Desktop x86_64 belongs to BitChord's
            // desktop targets and is deliberately not shipped by DollBloom.
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Dev and prod remain separate resource/configuration flavors, but every
    // app variant intentionally keeps the same application ID: com.doll.bloom.
    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            resValue("string", "app_name", "DollBloom 🌸 Dev")
        }
        create("prod") {
            dimension = "env"
            // Matches defaultConfig — this is the package already shipped/installed.
        }
    }

    signingConfigs {
        // Both halves have to be there, not just the properties file: it *names*
        // the keystore rather than containing it, and both are gitignored
        // separately, so a checkout can easily end up with the one and not the
        // other. A signing config pointing at a keystore that is not on disk
        // fails the release build outright at validateSigningRelease — which is
        // exactly the failure the unsigned fallback above exists to avoid, so
        // the keystore has to be looked for rather than assumed.
        val store = signing.getProperty("storeFile")?.let { rootProject.file(it) }
        if (store != null && store.exists()) {
            create("release") {
                storeFile = store
                storeType = signing.getProperty("storeType") ?: "JKS"
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (betaSuffix.isNotEmpty()) versionNameSuffix = "-$betaSuffix"
        }
        release {
            // Carried here too — see [betaSuffix]. A sideloaded beta is a
            // release build, and it is the one that most needs the marker.
            if (betaSuffix.isNotEmpty()) versionNameSuffix = "-$betaSuffix"
            /*
             * On for what it does to speed, not size. Compose is written to be
             * run through R8 — without it every composable keeps the debug-era
             * shape the compiler emits, and the whole UI runs measurably slower.
             *
             * Nothing is renamed (-dontobfuscate), and every library that reaches
             * for classes by name — Rhino running YouTube's player JavaScript,
             * NewPipe, InnerTubeX, QuickJS, SMBJ and BouncyCastle, ONNX's JNI,
             * protobuf-lite, Ktor — is kept whole: see proguard-rules.pro. What R8
             * is left to optimise is Compose, Media3, coroutines and our own
             * code, which is where the time goes. Checked on a device through the
             * `benchmark` build type below before it ships.
             */
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Null without a keystore to sign with: the build then produces
            // app-release-unsigned.apk instead of failing outright.
            signingConfig = signingConfigs.findByName("release")
        }
        /*
         * A release-like benchmark build of the dev flavor. It uses the same
         * application ID (`com.doll.bloom`) as every other variant, so installing
         * it replaces whichever DollBloom variant is already on the device. It
         * uses the debug key and non-debuggable runtime to check R8 and measure
         * startup. `./gradlew installDevBenchmark`.
         */
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            // Compress native libraries inside APKs to reduce sideload download size.
            // Android extracts them at install time; all native libraries and ABIs remain included.
            useLegacyPackaging = true
        }
        resources {
            // SMBJ's BouncyCastle and jspecify both ship this descriptor.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Unit tests run against a stub android.jar whose methods throw
            // rather than return. That is the right default for anything whose
            // behaviour depends on the framework, and wrong for android.util.Log
            // — which [TrackLog] calls on every decision the source layer makes,
            // so a test of that layer fails on the logging rather than on the
            // logic it was written to check.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/*
 * NewPipeExtractor ships its own org.schabi.newpipe.extractor.utils.Utils, and
 * app/src/main/java carries a patched copy at the same package path (see that
 * file for why it exists). A debug build keeps project and library dex separate,
 * so the project copy simply wins at class-load time and the two coexist; a
 * release build merges every input into one dex set, where D8 rejects the
 * duplicate type outright ("Utils is defined multiple times"). So the library's
 * copy is stripped from its jar before it reaches dexing, leaving exactly one
 * definition of the class in the build.
 *
 * The artifact is resolved on its own and non-transitive purely to re-jar it;
 * the transitive dependencies it would otherwise have carried are declared by
 * hand in the dependencies block below, since dropping the module drops them too.
 */
val newPipeExtractorRaw: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}
dependencies {
    newPipeExtractorRaw("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
}
val newPipeExtractorStripped = tasks.register<org.gradle.api.tasks.bundling.Jar>(
    "stripNewPipeExtractorUtils"
) {
    archiveFileName.set("NewPipeExtractor-v0.26.3-noutils.jar")
    destinationDirectory.set(layout.buildDirectory.dir("stripped-libs"))
    from(provider { newPipeExtractorRaw.map { zipTree(it) } }) {
        // The class itself, plus any nested or synthetic siblings the upstream
        // compiler emitted alongside it, so nothing from the jar's Utils survives.
        exclude("org/schabi/newpipe/extractor/utils/Utils.class")
        exclude("org/schabi/newpipe/extractor/utils/Utils\$*.class")
    }
}

dependencies {
    // ---- Compose (Material 3) ----
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    // Pinned above the BOM's 1.7.6: [IosOverscroll] uses OverscrollFactory,
    // which that version doesn't have. Newer foundation alongside the BOM's
    // older ui/material3 is a combination Compose supports deliberately —
    // foundation depends on ui, not the reverse — and this exact pairing was
    // already in effect (foundation was reaching 1.10.0 transitively through
    // the liquid-glass library before that dependency was removed).
    implementation("androidx.compose.foundation:foundation:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- Media playback: Media3 / ExoPlayer ----
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.media3:media3-common:1.11.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
    // Audio is progressive, but Apple serves its motion artwork as HLS — this
    // is what lets the animated sleeve play it. See CanvasArtworkPlayer.
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    // Source modules hand back manifests rather than files, and which kind is
    // the backend's choice, not ours: the Tidal one served `.m3u8` until
    // September 2026 and `.mpd` after it, for the same track and the same
    // request. Without this artifact a DASH manifest is not merely unplayed —
    // DefaultMediaSourceFactory cannot build a source for it, falls back to
    // progressive, and the extractors try to sniff XML as audio
    // (ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED). See withResolvedStreamType.
    implementation("androidx.media3:media3-exoplayer-dash:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")

    // ---- Images: Coil 3 + Palette (dominant colors for the mesh gradient) ----
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("androidx.palette:palette-ktx:1.0.0")

    // ---- Frosted glass / progressive blur (Telegram-style bars) ----
    implementation("dev.chrisbanes.haze:haze:1.3.1")
    implementation("dev.chrisbanes.haze:haze-materials:1.3.1")

    // ---- QR encoding, for the party invite ----
    // `core` only: the `android-core`/`zxing-android-embedded` artifacts bring
    // a camera scanner and an Activity with it, and nothing here reads a code —
    // a party is joined by tapping somebody else's link or typing six
    // characters. This produces the bit matrix; the drawing is ours, in
    // [com.doll.bloom.ui.components.QrCode], so the result is styled like
    // the rest of the app rather than a stock black-and-white bitmap.
    implementation("com.google.zxing:core:3.5.3")

    // ---- Markdown rendering (release notes in the update dialog) ----
    // Pure Compose, not an AndroidView wrapper — needed so the text composes
    // correctly under the dialog's Haze blur.
    implementation("com.halilibo.compose-richtext:richtext-ui-material3:0.20.0")
    implementation("com.halilibo.compose-richtext:richtext-commonmark:0.20.0")

    // ---- Innertube (YouTube Music) client: Ktor + kotlinx.serialization ----
    // Ktor and serialization are held at InnerTubeX's versions (below) so the
    // upgrade it forces is explicit rather than resolved behind our backs.
    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // ---- Discord Rich Presence: the gateway is a WebSocket, so Ktor needs the plugin ----
    implementation("io.ktor:ktor-client-websockets:3.5.2")

    // ---- YouTube stream extraction: live-benchmarked client catalog + cipher tiers ----
    implementation("com.github.MetrolistGroup.innertubex:innertubex-android:v0.7.0")

    // ---- Stream resolution: NewPipe solves YouTube's signature + `n` throttling ----
    // Pinned to v0.26.3, not the newer v0.26.4: v0.26.4's player-JS parser fails with
    // "Could not parse deobfuscation function" on the current player build, which blocks
    // WEB_REMIX's ciphered formats entirely. v0.26.3 solves the same signatures cleanly
    // against the same player JS — confirmed side by side against PixelMusic-ref, which
    // pins v0.26.3 and doesn't hit the parse failure.
    //
    // Consumed as a stripped jar rather than as the module, so its own
    // Utils.class does not reach dexing. See newPipeExtractorStripped above; the
    // transitive dependencies the module would have brought are listed here
    // because dropping its artifact drops them too. If the version changes,
    // re-derive this list with
    //   ./gradlew :app:dependencies --configuration prodReleaseRuntimeClasspath
    implementation(files(newPipeExtractorStripped))
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.protobuf:protobuf-javalite:4.35.0")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")

    // ---- Auth/session storage ----
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ---- JS module execution: QuickJS VM for style source plugins ----
    // Held at InnerTubeX's version; the same VM runs QuickJsExecutor's module sources.
    implementation("io.github.dokar3:quickjs-kt-android:1.0.14")

    // ---- SMB file shares: pure-Java SMB2/3 client (listing + streaming) ----
    implementation("com.hierynomus:smbj:0.15.0")

    // ---- Automix: on-device beat/downbeat model (Beat This!, MIT-licensed) ----
    // The full android artifact, not onnxruntime-mobile: mobile only loads .ort
    // files, which would put an offline conversion step between the model and
    // the app for a saving that does not matter in a self-distributed APK.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")

    testImplementation("junit:junit:4.13.2")
    // A real HTTP server for the addon tests. The addon protocol is entirely
    // "what does this app send, and what does it do with what comes back", and
    // a hand-rolled fake of the client would be a test of the fake. Pinned to
    // the OkHttp version already on the runtime classpath.
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

/*
 * A debug APK lands on the device uncompiled — `dumpsys package dexopt` reports
 * it as run-from-apk — so every launch verifies the whole app's classes at
 * runtime before a line of our code runs. Measured on the BlueStacks box, that
 * was about two seconds of every cold start and most of why the dev build felt
 * so much slower than a release one. `verify` is the cheapest filter that
 * removes it (~15s once per install), and unlike `speed` it leaves the debug
 * build debuggable exactly as before.
 *
 * Runs after `installDevDebug` from the command line. Android Studio's Run
 * button deploys on its own and never reaches this task, so from there run
 * `./gradlew verifyDevInstall` after installing.
 */
val verifyDevInstall = tasks.register("verifyDevInstall") {
    group = "install"
    description = "Pre-verifies the installed dev build on every connected device."
    val adb = androidComponents.sdkComponents.adb
    doLast {
        val adbPath = adb.get().asFile.absolutePath
        val serials = ProcessBuilder(adbPath, "devices").start()
            .inputStream.bufferedReader().readLines()
            .drop(1)
            .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 && it[1] == "device" }?.get(0) }
        serials.forEach { serial ->
            logger.lifecycle("verifyDevInstall: compiling com.doll.bloom on $serial")
            ProcessBuilder(
                adbPath, "-s", serial, "shell", "cmd", "package", "compile",
                "-m", "verify", "-f", "com.doll.bloom",
            ).inheritIO().start().waitFor()
        }
    }
}
tasks.matching { it.name == "installDevDebug" }.configureEach { finalizedBy(verifyDevInstall) }
