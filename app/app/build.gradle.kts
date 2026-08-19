import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copies an AAR, rewriting its `classes.jar` without the `go/` package.
 *
 * An AAR is a zip holding a zip, so the inner jar has to be unpacked and
 * rebuilt rather than filtered in place. Everything else — the manifest, the
 * native libraries under `jni/`, R.txt — is copied through untouched.
 */
fun stripAar(source: File, target: File) {
    ZipFile(source).use { aar ->
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            for (entry in aar.entries()) {
                out.putNextEntry(ZipEntry(entry.name))
                if (entry.name == "classes.jar") {
                    stripJar(aar.getInputStream(entry), out)
                } else if (!entry.isDirectory) {
                    aar.getInputStream(entry).use { it.copyTo(out) }
                }
                out.closeEntry()
            }
        }
    }
}

/** Rebuilds a jar stream without the gomobile binding classes. */
fun stripJar(source: java.io.InputStream, sink: java.io.OutputStream) {
    // Not closed: the sink is the enclosing AAR's stream and closing it here
    // would end the outer archive after one entry.
    val inner = ZipOutputStream(sink)
    ZipInputStream(source).use { jar ->
        while (true) {
            val entry = jar.nextEntry ?: break
            if (entry.name.startsWith("go/")) continue
            inner.putNextEntry(ZipEntry(entry.name))
            if (!entry.isDirectory) jar.copyTo(inner)
            inner.closeEntry()
        }
    }
    inner.finish()
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.jaredsburrows.license")
}

/**
 * Release signing credentials, from keystore.properties (untracked) or the
 * environment for CI. Never inline in this file: the signing key is the app's
 * identity to the panel, and a build file is the one place in the project that
 * gets pasted into chats and issue reports.
 *
 * A build with no credentials configured still works — debug builds are signed
 * with the local debug key as always — but assembling a release is refused
 * below rather than quietly producing an unsigned APK that no panel in
 * production would ever let register.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingSecret(property: String, environment: String): String? =
    (keystoreProperties.getProperty(property) ?: System.getenv(environment))?.takeIf { it.isNotBlank() }

val releaseKeystore = signingSecret("storeFile", "RAHGOZAR_KEYSTORE_FILE")?.let { rootProject.file(it) }
val hasReleaseSigning = releaseKeystore?.exists() == true &&
    signingSecret("storePassword", "RAHGOZAR_KEYSTORE_PASSWORD") != null &&
    signingSecret("keyAlias", "RAHGOZAR_KEY_ALIAS") != null &&
    signingSecret("keyPassword", "RAHGOZAR_KEY_PASSWORD") != null

android {
    namespace = "com.rahgozar.app"
    compileSdk = 37
    // Pinned to the version CI builds the hev-socks5-tunnel libraries with, so
    // the prebuilt .so files in app/libs match what Gradle links against.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.rahgozar.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 742
        versionName = "2.3.2"

        // English only, and enforced at build time rather than left to the
        // resource folders being empty. Libraries bring their own translations
        // in — androidx alone ships dozens — and without this the app would keep
        // answering a Persian phone in Persian through strings we never wrote.
        androidResources {
            localeFilters += "en"
        }

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')

        // Per-ABI APKs and an app bundle cannot both be asked for: AGP fails
        // the bundle with "Multiple shrunk-resources files found" rather than
        // choosing for you (issuetracker 402800800). A bundle needs no splits
        // anyway — Play does the per-device slicing itself, from the one
        // artefact — so the split is switched off exactly for the task that
        // cannot take it, and every APK build keeps behaving as before.
        val buildingBundle = gradle.startParameter.taskNames.any {
            it.contains("bundle", ignoreCase = true)
        }
        splits {
            abi {
                isEnable = !buildingBundle
                reset()
                if (!abiFilterList.isNullOrEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The AdMob SDK reads its application id from the manifest at process
        // start and throws if it is absent, so unlike every other ad setting
        // this one CANNOT come from the panel — it is fixed at build time.
        //
        // Defaults to Google's public test application id. Override for a real
        // release with -PadmobAppId=ca-app-pub-xxx~yyy, and keep the panel's
        // admob_app_id in step: the panel value is what the app reports and
        // checks against, the manifest value is what the SDK actually uses.
        manifestPlaceholders["admobAppId"] =
            (properties["admobAppId"] as? String) ?: "ca-app-pub-3940256099942544~3347511713"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingSecret("storePassword", "RAHGOZAR_KEYSTORE_PASSWORD")
                keyAlias = signingSecret("keyAlias", "RAHGOZAR_KEY_ALIAS")
                keyPassword = signingSecret("keyPassword", "RAHGOZAR_KEY_PASSWORD")

                // v1 as well as v2/v3: minSdk is 24, and API 24-25 verify only
                // the JAR signature.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Null when no credentials are configured. The guard below turns
            // that into a failed build rather than an unsigned APK.
            signingConfig = signingConfigs.findByName("release")
            // On for obfuscation rather than for size. The server list is this
            // project's asset, and the cheapest way to write a tool that lifts
            // it off a rooted phone is to read the class and method names in
            // the APK. Renaming them turns that into reverse engineering.
            //
            // Every reflective and JNI surface this breaks is enumerated in
            // proguard-rules.pro; read that before adding a library.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        // One flavor, because Google Play is the only distribution channel. The
        // F-Droid flavor that used to sit beside it carried its own
        // applicationId suffix, which meant a second row in the panel's
        // signature allowlist and a second build to test for every release.
        create("playstore") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
        getByName("test") {
            // The panel generates the cross-language crypto vectors
            // (`go run ./cmd/testvectors`). Read straight from its testdata
            // rather than copied in: a duplicate would let the two
            // implementations be tested against different files, which is
            // exactly the drift these vectors exist to catch.
            resources.srcDir(rootProject.file("../../panel/internal/cryptobox/testdata"))
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        // Only affects the APKs produced by assemble*, which are for direct
        // installation while testing. The AAB that goes to Play carries the
        // plain versionCode and Play generates the per-device splits.
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
            .forEach { output ->
                val abi = output.getFilter("ABI") ?: "universal"
                output.outputFileName = "rahgozar_${variant.versionName}_${abi}.apk"
            }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            // Without this, every android.jar method in a unit test throws
            // "not mocked" instead of returning a default. That bit UtilsTest:
            // its invalid-input cases reach a catch block, the catch block logs,
            // and android.util.Log.e blew up — so a test asserting that bad
            // input is rejected failed for a reason unrelated to the assertion.
            isReturnDefaultValues = true
        }
    }

}

/**
 * An unsigned release APK is not a build problem, it is a launch problem: it
 * installs, it runs, and then every device it reaches is refused at
 * /v1/device/register because its certificate is not in the panel's allowlist.
 * That is a long way to travel before anything says why, so the build stops
 * here instead.
 */
/**
 * Whether git says the tree is clean and this commit is on the public remote.
 *
 * Null when the answer cannot be had — no git, no remote, no network. That is
 * deliberately not a failure: a source tree unpacked from a tarball has no git
 * at all and must still build.
 */
fun publishedCommitOrNull(): String? = runCatching {
    fun git(vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args)
            .directory(rootDir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        return if (p.waitFor() == 0) out else error(out)
    }
    if (git("status", "--porcelain").isNotEmpty()) return null
    val head = git("rev-parse", "HEAD")
    // `branch -r --contains` is the question that matters: is this exact commit
    // reachable from something the remote already has.
    if (git("branch", "-r", "--contains", head).isBlank()) return null
    head
}.getOrNull()

tasks.configureEach {
    if ((name.startsWith("assemble") || name.startsWith("bundle")) && name.contains("Release")) {
        doFirst {
            // The app is a v2rayNG fork and therefore GPLv3: every recipient is
            // owed the source this binary was built from. The About screen and
            // the licence notices both publish a URL for it, and a release that
            // shipped with the placeholder still in place would be making a
            // promise to a page that does not exist. Cheaper to fail here than
            // to find out from a takedown.
            val branding = file("src/main/java/com/rahgozar/app/Branding.kt").readText()
            if (branding.contains("REPLACE-ME")) {
                throw GradleException(
                    "Branding.SOURCE_URL is still the placeholder. Set it to the public " +
                        "repository, then regenerate src/main/assets/open_source_licenses.html " +
                        "which repeats the same URL. See docs/LICENSING.md.",
                )
            }

            // The GPL obligation, enforced instead of remembered.
            //
            // A released binary owes its recipients *the source it was built
            // from*. If the tree has uncommitted changes, or the commit is not
            // on the public remote, then no published commit corresponds to
            // this artefact and a tag pointing at one would be a false claim.
            //
            // `bundle` fails and `assemble` only warns, which is the same line
            // docs/RELEASE.md already draws: the AAB is what goes to Play, the
            // APK is for trying on a phone.
            if (publishedCommitOrNull() == null) {
                val message = "the working tree is not committed and pushed, so no public " +
                    "commit corresponds to this build. Commit, push, then build — " +
                    "release.ps1 does all of it in order. See docs/LICENSING.md."
                if (name.startsWith("bundle")) {
                    throw GradleException("refusing to build a release bundle: $message")
                }
                logger.warn("WARNING: $message")
            }

            if (!hasReleaseSigning) {
                throw GradleException(
                    "release signing is not configured — copy keystore.properties.example to " +
                        "keystore.properties and fill it in (or set RAHGOZAR_KEYSTORE_FILE, " +
                        "RAHGOZAR_KEYSTORE_PASSWORD, RAHGOZAR_KEY_ALIAS, RAHGOZAR_KEY_PASSWORD). " +
                        "See docs/RELEASE.md.",
                )
            }
        }
    }
}

/**
 * Strips the gomobile binding classes out of the prebuilt AARs.
 *
 * `go.Seq` decides which native library to load, and this app needs that
 * decision to depend on the process: sing-box's two processes get `libbox`,
 * everything else gets Xray's `gojni`. Two Go runtimes cannot share one
 * process, so the copy in `src/main/java/go/` is patched to read
 * /proc/self/cmdline and choose — see docs/CORES.md.
 *
 * The AARs carry their own unpatched `go/` classes. With dexing alone the
 * duplicate passed silently and whichever won was luck; R8 refuses outright
 * ("Type go.error is defined multiple times"), which is the better behaviour
 * and is what surfaced this. Removing them from the AARs leaves exactly one
 * definition — the patched one — instead of relying on the winner being right.
 *
 * The files in `libs/` are never modified; this writes stripped copies.
 */
val strippedLibsDir = layout.buildDirectory.dir("stripped-libs")

val stripDuplicateGoBindings = tasks.register("stripDuplicateGoBindings") {
    val source = layout.projectDirectory.dir("libs")
    inputs.dir(source).withPropertyName("prebuiltLibs")
    outputs.dir(strippedLibsDir).withPropertyName("strippedLibs")

    doLast {
        val outDir = strippedLibsDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        source.asFile.listFiles()?.sortedBy { it.name }?.forEach { file ->
            when {
                file.name.endsWith(".aar") -> stripAar(file, File(outDir, file.name))
                file.name.endsWith(".jar") -> file.copyTo(File(outDir, file.name), overwrite = true)
            }
        }
    }
}

dependencies {
    // Core Libraries
    implementation(
        fileTree(mapOf("dir" to strippedLibsDir, "include" to listOf("*.aar", "*.jar")))
            .builtBy(stripDuplicateGoBindings)
    )

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)

    // Not used directly — no screen in this app is a Fragment. Constrained
    // because play-services-basement pulls fragment 1.1.0, whose
    // FragmentActivity mishandles the ActivityResult APIs the app relies on.
    constraints {
        implementation(libs.androidx.fragment) {
            because("play-services-basement pins a fragment older than the ActivityResult APIs need")
        }
    }

    // Compose Libraries
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Reorderable list
    implementation(libs.reorderable)

    // Panel protocol crypto. Ed25519 (response signatures) and X25519 (per-device
    // key agreement) are absent from java.security below API 33; the rest of the
    // scheme — HKDF and AES-GCM — comes from the platform.
    implementation(libs.tink.android)

    // Ads
    implementation(libs.play.services.ads)

    // Testing Libraries
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
