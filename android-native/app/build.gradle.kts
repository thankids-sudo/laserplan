import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** Copies the LaserPlan web app (repository root) into the APK's assets/www, so the app runs fully offline. */
abstract class CopyWebAssets : DefaultTask() {
    @get:InputFiles
    abstract val webFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile.resolve("www")
        out.deleteRecursively()
        out.mkdirs()
        webFiles.files.filter { it.isFile }.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
        require(out.resolve("index.html").isFile) { "index.html missing from the repository root" }
    }
}

val webRoot = rootProject.layout.projectDirectory.dir("..")
val copyWebAssets = tasks.register<CopyWebAssets>("copyWebAssets") {
    webFiles.from(
        webRoot.file("index.html"), webRoot.file("three.min.js"), webRoot.file("xlsx.full.min.js"),
        webRoot.file("manifest.webmanifest"), webRoot.file("icon-192.png"), webRoot.file("icon-512.png"),
    )
}

val ksPath: String? = System.getenv("LP_KEYSTORE")
val ksPass: String? = System.getenv("LP_KEYSTORE_PW")
val ksAlias: String = System.getenv("LP_KEY_ALIAS") ?: "laserplan"

android {
    namespace = "io.github.thankids_sudo.laserplan"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.thankids_sudo.laserplan"
        minSdk = 24
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1300").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "13.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (ksPath != null && ksPass != null && file(ksPath).isFile) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { buildConfig = true }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyWebAssets, CopyWebAssets::outputDir)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
