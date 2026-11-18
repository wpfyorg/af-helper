import java.util.Properties

plugins {
    id("com.android.application")
}

val localConfig = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

val releaseKeystoreFile = rootProject.file("release.keystore")
val releaseStorePassword = localConfig.getProperty("RELEASE_STORE_PASSWORD", "")
val releaseKeyAlias = localConfig.getProperty("RELEASE_KEY_ALIAS", "")
val releaseKeyPassword = localConfig.getProperty("RELEASE_KEY_PASSWORD", "")
val hasReleaseSigning = releaseKeystoreFile.isFile && releaseStorePassword.isNotBlank() &&
    releaseKeyAlias.isNotBlank() && releaseKeyPassword.isNotBlank()

val clientKeystorePath = localConfig.getProperty("AF_BLE_CLIENT_KEYSTORE", "").trim()
val clientKeystorePassword = localConfig.getProperty("AF_BLE_CLIENT_PASSWORD", "").trim()
val oduAdminUser = localConfig.getProperty("AF_ODU_ADMIN_USER", "").trim()
val oduAdminPassword = localConfig.getProperty("AF_ODU_ADMIN_PASSWORD", "").trim()
val clientKeystoreFile = if (clientKeystorePath.isEmpty()) null else file(clientKeystorePath)
val hasClientIdentity = clientKeystoreFile?.isFile == true && clientKeystorePassword.isNotEmpty()
val generatedClientAssetsDir = layout.buildDirectory.dir("generated/clientIdentityAssets").get().asFile
val stageClientIdentity = tasks.register<Copy>("stageClientIdentity") {
    description = "Stage the git-ignored ODU mTLS client identity into packaged assets."
    val source = clientKeystoreFile
    if (hasClientIdentity && source != null) {
        from(source) { rename { "af_ble_client.p12" } }
        into(generatedClientAssetsDir)
    }
}

fun javaStringLiteral(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            else -> if (character.code < 0x20) append("\\%03o".format(character.code))
            else append(character)
        }
    }
    append('"')
}

tasks.configureEach {
    if (name == "packageRelease") {
        doFirst {
            check(hasReleaseSigning) {
                "Release packaging requires release.keystore and RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD in local.properties."
            }
        }
    }
    if (name.matches(Regex("(?i)^(assemble|bundle|package|merge.*assets).*"))) {
        dependsOn(stageClientIdentity)
    }
}

android {
    namespace = "io.github.wpfyorg.afhelper"
    compileSdk = 36

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    defaultConfig {
        applicationId = "io.github.wpfyorg.afhelper"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "1.0.10"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        buildConfigField("boolean", "HAS_CLIENT_IDENTITY", hasClientIdentity.toString())
        buildConfigField("String", "CLIENT_IDENTITY_PASSWORD", javaStringLiteral(clientKeystorePassword))
        buildConfigField("String", "ODU_ADMIN_USER", javaStringLiteral(oduAdminUser))
        buildConfigField("String", "ODU_ADMIN_PASSWORD", javaStringLiteral(oduAdminPassword))
    }

    sourceSets["main"].assets.srcDir(generatedClientAssetsDir)

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
