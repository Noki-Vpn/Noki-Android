import java.util.Properties
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

apply(plugin = "com.google.gms.google-services")

layout.buildDirectory.set(file("build-compose-2026"))

val releaseSigningPropertiesFile = rootProject.file("sha-256 key/release-signing.key")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.exists()) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}
val requiredReleaseSigningProperties = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword",
)
val defaultNokiApiBaseUrl = "https://api.noki.ykino.tech"
val nokiApiBaseUrl = providers.gradleProperty("noki.apiBaseUrl")
    .orElse(defaultNokiApiBaseUrl)
    .get()
    .trimEnd('/')
val nokiBackendProbeHost = providers.gradleProperty("noki.backendProbeHost")
    .orElse(URI(nokiApiBaseUrl).host ?: "api.noki.ykino.tech")
    .get()
val nokiBackendProbeHealthUrl = providers.gradleProperty("noki.backendProbeHealthUrl")
    .orElse("$nokiApiBaseUrl/v1/vpn/connectivity")
    .get()
val ruRelayDefaultEnabled = providers.gradleProperty("noki.ruRelayDefaultEnabled")
    .orElse("false")
    .get()
    .also { require(it == "true" || it == "false") { "noki.ruRelayDefaultEnabled must be true or false" } }
val googleWebClientId = providers.gradleProperty("noki.googleWebClientId")
    .orElse("")
    .get()
val libboxAarFile = layout.projectDirectory.file("libs/libbox.aar")
val libboxAarSha256 = "228E70B1B1C91817AFFC45E834B886D11F299862AF4E40C33D2B53E04EE1F94E"
val libboxSourceFile = layout.projectDirectory.file("libs/libbox.SOURCE.zip")
val libboxSourceSha256 = "B9253F9BDBC84964B4401D241A13A1009D8BBF9641E5F7212E093DCE7EFBDD2B"
val geositeFile = layout.projectDirectory.file("src/main/assets/geosite.dat")
val geositeSha256 = "1B9012AB376E142DA68506385E4B0590ABEB20A0DEAC0169293E1057FCE8D949"
val geositeSourceFile = layout.projectDirectory.file("../third_party/geosite/SOURCE.zip")
val geositeSourceSha256 = "09E547AB2E7B76A24081243EF8E7EF0432D70C2551F2E7EFCA1A3F290B731CEE"
val geositeLicenseFile = layout.projectDirectory.file("src/main/assets/licenses/geosite/LICENSE")
val prismalAarFile = layout.projectDirectory.file("libs/prismal-1.0.3.aar")
val prismalAarSha256 = "40D5EAFC3B68554E0D5E159EA02ECDA8CA86EB32A43DC1B3BAE5C65344C08C2A"
val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val expectedReleaseVersionCode = 317
val expectedReleaseVersionName = "2.3.1"

fun String.toBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

android {
    namespace = "com.noki.vpn"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.noki.vpn"
        minSdk = 26
        targetSdk = 36
        versionCode = expectedReleaseVersionCode
        versionName = expectedReleaseVersionName
        manifestPlaceholders["nokiAppLabel"] = "Noki Vpn"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "NOKI_API_BASE_URL", nokiApiBaseUrl.toBuildConfigString())
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", googleWebClientId.toBuildConfigString())
        buildConfigField("String", "NOKI_BACKEND_PROBE_HOST", nokiBackendProbeHost.toBuildConfigString())
        buildConfigField("boolean", "RU_RELAY_DEFAULT_ENABLED", ruRelayDefaultEnabled)
        buildConfigField("boolean", "DIAGNOSTIC_LOGGING", "false")
        buildConfigField(
            "String",
            "TELEGRAM_LOGIN_REDIRECT_URI",
            "https://app601443831-login.tg.dev/tglogin".toBuildConfigString(),
        )
        buildConfigField(
            "String",
            "NOKI_BACKEND_PROBE_HEALTH_URL",
            nokiBackendProbeHealthUrl.toBuildConfigString(),
        )
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*supportedAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("releaseLocal") {
            val storeFilePath = releaseSigningProperties.getProperty("storeFile").orEmpty()
            if (storeFilePath.isNotBlank()) {
                storeFile = rootProject.file(storeFilePath)
            }
            storePassword = releaseSigningProperties.getProperty("storePassword")
            keyAlias = releaseSigningProperties.getProperty("keyAlias")
            keyPassword = releaseSigningProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        create("diagnostic") {
            initWith(getByName("debug"))
            isDebuggable = true
            isMinifyEnabled = false
            versionNameSuffix = "-diagnostic"
            buildConfigField("boolean", "DIAGNOSTIC_LOGGING", "true")
            if (releaseSigningPropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("releaseLocal")
            }
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            if (releaseSigningPropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("releaseLocal")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        // Dependencies also ship x86, but libbox supports the three declared ABIs.
        jniLibs.excludes += "/lib/x86/**"
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.register("validateLocalReleaseSigning") {
    doLast {
        require(releaseSigningPropertiesFile.exists()) {
            "Release signing properties must exist at sha-256 key/release-signing.key"
        }
        val missing = requiredReleaseSigningProperties.filter { key ->
            releaseSigningProperties.getProperty(key).isNullOrBlank()
        }
        require(missing.isEmpty()) {
            "Release signing properties are missing: ${missing.joinToString(", ")}"
        }
        val storeFilePath = releaseSigningProperties.getProperty("storeFile").orEmpty()
        require(rootProject.file(storeFilePath).exists()) {
            "Release keystore must exist at $storeFilePath"
        }
    }
}

tasks.register("verifyLibboxAar") {
    inputs.files(libboxAarFile, libboxSourceFile)
    doLast {
        val aar = libboxAarFile.asFile
        for ((artifact, expected) in listOf(aar to libboxAarSha256, libboxSourceFile.asFile to libboxSourceSha256)) {
            require(artifact.exists()) { "${artifact.name} must exist at ${artifact.relativeTo(projectDir)}" }
            val actual = sha256Hex(artifact)
            require(actual.equals(expected, ignoreCase = true)) {
                "${artifact.name} checksum mismatch: expected $expected, got $actual"
            }
        }
        ZipFile(aar).use { archive ->
            for (abi in supportedAbis) {
                require(archive.getEntry("jni/$abi/libbox.so") != null) { "libbox.aar missing ABI: $abi" }
            }
            for (notice in listOf("LICENSE", "COPYING", "NOTICE")) {
                require(archive.getEntry("assets/licenses/libbox/$notice") != null) {
                    "libbox.aar missing license notice: $notice"
                }
            }
        }
    }
}

tasks.register("verifyPrismalAar") {
    inputs.file(prismalAarFile)
    doLast {
        val aar = prismalAarFile.asFile
        require(aar.exists()) { "Prismal AAR must exist at ${aar.relativeTo(projectDir)}" }
        val actual = sha256Hex(aar)
        require(actual.equals(prismalAarSha256, ignoreCase = true)) {
            "Prismal AAR checksum mismatch: expected $prismalAarSha256, got $actual"
        }
    }
}

tasks.register("verifyGeositeSource") {
    inputs.files(geositeFile, geositeSourceFile, geositeLicenseFile)
    doLast {
        for ((artifact, expected) in listOf(geositeFile.asFile to geositeSha256, geositeSourceFile.asFile to geositeSourceSha256)) {
            require(artifact.exists()) { "${artifact.name} must exist at ${artifact.relativeTo(projectDir)}" }
            require(sha256Hex(artifact).equals(expected, ignoreCase = true)) {
                "${artifact.name} checksum mismatch"
            }
        }
        ZipFile(geositeSourceFile.asFile).use { archive ->
            for (member in listOf("build-inputs.json", "src/main.go", "src/go.mod", "src/go.sum", "src/data/category-ru", "src/vendor/modules.txt")) {
                require(archive.getEntry(member) != null) { "Geosite source missing: $member" }
            }
            require(geositeLicenseFile.asFile.exists()) { "Geosite license notice is missing" }
            val license = requireNotNull(archive.getEntry("src/LICENSE")) { "Geosite source license is missing" }
            require(archive.getInputStream(license).use { it.readBytes() }.contentEquals(geositeLicenseFile.asFile.readBytes())) {
                "Geosite bundled license differs from source"
            }
        }
    }
}

tasks.register("verifyContractFixtures") {
    val names = listOf("multihop_session_v2.json", "ru_wireguard_session_v2.json")
    val canonicalDirectory = rootProject.file("../contracts")
    val packagedDirectory = file("src/test/resources/contracts")
    inputs.files(names.flatMap { listOf(canonicalDirectory.resolve(it), packagedDirectory.resolve(it)) })
    doLast {
        for (name in names) {
            val canonical = canonicalDirectory.resolve(name)
            val packaged = packagedDirectory.resolve(name)
            require(canonical.isFile) { "Canonical contracts/$name is required for fixture verification" }
            require(packaged.isFile && canonical.readBytes().contentEquals(packaged.readBytes())) {
                "Packaged $name must match canonical contracts/$name byte for byte"
            }
        }
    }
}

tasks.register("verifyReleaseVersion") {
    doLast {
        require(android.defaultConfig.versionCode == expectedReleaseVersionCode) {
            "Expected release versionCode $expectedReleaseVersionCode, got ${android.defaultConfig.versionCode}"
        }
        require(android.defaultConfig.versionName == expectedReleaseVersionName) {
            "Expected release versionName $expectedReleaseVersionName, got ${android.defaultConfig.versionName}"
        }
    }
}

tasks.register<Copy>("copyNamedReleaseApks") {
    val namedVariantDirectory = layout.buildDirectory.dir(
        "outputs/apk/release-named/${if (ruRelayDefaultEnabled == "true") "on" else "off"}",
    )
    dependsOn("packageRelease")
    from(layout.buildDirectory.dir("outputs/apk/release"))
    include("*.apk")
    exclude("app-x86-release.apk")
    into(namedVariantDirectory)
    doFirst {
        delete(namedVariantDirectory)
    }
    rename { fileName ->
        val abi = when {
            fileName.contains("arm64-v8a") -> "arm64-v8a"
            fileName.contains("armeabi-v7a") -> "armeabi-v7a"
            fileName.contains("x86_64") -> "x86_64"
            fileName.contains("universal") -> "universal"
            else -> null
        }
        abi?.let { "Noki Vpn-${android.defaultConfig.versionName}-$it.apk" } ?: fileName
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn("verifyLibboxAar")
    dependsOn("verifyReleaseVersion")
    dependsOn("validateLocalReleaseSigning")
    finalizedBy("copyNamedReleaseApks")
}

tasks.register<Copy>("copyDiagnosticApks") {
    dependsOn("packageDiagnostic")
    from(layout.buildDirectory.dir("outputs/apk/diagnostic"))
    include("*.apk")
    into(layout.buildDirectory.dir("outputs/diagnostic-package"))
    doFirst {
        delete(layout.buildDirectory.dir("outputs/diagnostic-package"))
    }
    rename { fileName ->
        val abi = when {
            fileName.contains("arm64-v8a") -> "arm64-v8a"
            fileName.contains("armeabi-v7a") -> "armeabi-v7a"
            fileName.contains("x86_64") -> "x86_64"
            fileName.contains("universal") -> "universal"
            else -> "unknown"
        }
        "Noki-Vpn-${android.defaultConfig.versionName}-diagnostic-$abi.apk"
    }
}

tasks.matching { it.name == "assembleDiagnostic" }.configureEach {
    finalizedBy("copyDiagnosticApks")
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("verifyLibboxAar")
    dependsOn("verifyPrismalAar")
    dependsOn("verifyGeositeSource")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    val firebaseBom = platform("com.google.firebase:firebase-bom:34.14.0")

    implementation(files("libs/libbox.aar"))
    implementation(files(prismalAarFile))
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    testImplementation("androidx.work:work-testing:2.11.2")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")

    implementation(composeBom)
    implementation(firebaseBom)
    debugImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.camera:camera-core:1.6.0")
    implementation("androidx.camera:camera-camera2:1.6.0")
    implementation("androidx.camera:camera-lifecycle:1.6.0")
    implementation("androidx.camera:camera-view:1.6.0")

    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-gif:3.4.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("com.google.firebase:firebase-messaging")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
}
