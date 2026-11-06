plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
val appVersion = (findProperty("appVersion") as String?) ?: "1.0"
require(Regex("""\d+\.\d""").matches(appVersion)) { "Version must be one decimal place" }
val appVersionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull()
    ?: appVersion.split('.').let { it[0].toInt() * 10 + it[1].toInt() }
val appVersionSuffix = (findProperty("appVersionSuffix") as String?) ?: ""

android {
    namespace = "dev.n2k02.localadb"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    defaultConfig {
        applicationId = "dev.n2k02.localadb"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion + appVersionSuffix
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        getByName("debug") { versionNameSuffix = "-debug" }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("dev.n2k02:theme:0.1.5")
    implementation("dev.n2k02:updater:0.1.20")
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.86")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.86")
    implementation("org.conscrypt:conscrypt-android:2.8.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
val verifySharedUpdaterVersion = tasks.register("verifySharedUpdaterVersion") {
    group = "verification"
    doLast {
        val versions = configurations.filter { it.name == "implementation" }
            .flatMap { it.dependencies }
            .filter { it.group == "dev.n2k02" && it.name == "updater" }
            .mapNotNull { it.version }
        check(versions.isNotEmpty() && versions.all {
            val v = it.split('.').map(String::toIntOrNull)
            v.size == 3 && v.all { n -> n != null } &&
                (v[0]!! > 0 || v[1]!! > 1 || (v[1] == 1 && v[2]!! >= 19))
        }) { "Use pinned updater >= 0.1.19" }
    }
}
tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach {
    dependsOn(verifySharedUpdaterVersion)
}


// Bundle the upstream Apache-2.0 server inside LocalAdb; no companion install.
val prepareEmbeddedShizuku = tasks.register<Exec>("prepareEmbeddedShizuku") {
    group = "build setup"
    description = "Download checksum-pinned Shizuku-Next server payload"
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/fetch-embedded-shizuku.sh")
    outputs.file(layout.projectDirectory.file("src/main/assets/embedded-shizuku-server.apk"))
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(prepareEmbeddedShizuku)
}
