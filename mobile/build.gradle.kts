plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional local-only debug input. Release never imports this accessory identity.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shihab.diplay"
        minSdk = 28
        targetSdk = 37
        versionCode = 20
        versionName = "0.2.0"

    }


    localAuthenticationAssets?.let { sourceSets.getByName("debug").assets.srcDir(it) }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".hudtest"
            versionNameSuffix = "-hud-test"
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Debug permits only the two explicitly selected inputs. Release permits no credential assets.
for (buildType in listOf("debug", "release")) {
    val variant = buildType.replaceFirstChar { it.uppercaseChar() }
    val filesToCheck = files(listOf("main", buildType).flatMap { name ->
        android.sourceSets.getByName(name).assets.directories.map { directory ->
            fileTree(directory) {
                include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                    "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
            }
        }
    })
    val allowed = if (buildType == "debug") localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet() else emptySet()
    val rejectCredentials = tasks.register("reject${variant}BundledCredentials") {
        group = "verification"
        description = "Reject unexpected credential files in $buildType APK assets."
        inputs.files(filesToCheck)
        doLast {
            check(allowed.all { it.isFile && it.length() > 0L }) {
                "Explicit debug authentication assets are incomplete"
            }
            check(filesToCheck.files.all { it.canonicalFile in allowed }) {
                "Unexpected credential files in $buildType APK assets"
            }
        }
    }
    tasks.matching { it.name == "pre${variant}Build" }.configureEach { dependsOn(rejectCredentials) }
}
