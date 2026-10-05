import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.util.Properties

abstract class VerifyAndroidReleaseSigningTask : DefaultTask() {
    @get:Input
    abstract val missingKeys: ListProperty<String>

    @get:Input
    abstract val sourceName: Property<String>

    // Checked explicitly so partial configuration reports every missing key before Gradle's
    // generic input-file validation masks the actionable error.
    @get:Internal
    abstract val keystoreFile: RegularFileProperty

    @get:Internal
    abstract val storePassword: Property<String>

    @get:Input
    abstract val keyAlias: Property<String>

    @get:Internal
    abstract val keyPassword: Property<String>

    @get:Input
    abstract val expectedCertificateSha256: Property<String>

    @TaskAction
    fun verifySigningIdentity() {
        val missing = missingKeys.get()
        check(missing.isEmpty()) {
            buildString {
                append("Release signing is not configured. Missing ")
                append(missing.joinToString())
                append(" from ")
                append(sourceName.get())
                append(". Set all four ANDROID_* values in the environment, as Gradle properties, ")
                append("or in the ignored root release-signing.properties file; see docs/BUILD.md.")
            }
        }

        val keystore = keystoreFile.get().asFile
        check(keystore.isFile && keystore.length() > 0L) {
            "Release keystore does not exist or is empty: ${keystore.absolutePath}"
        }

        val storePasswordChars = storePassword.get().toCharArray()
        val alias = keyAlias.get()
        val keyPasswordChars = keyPassword.get().toCharArray()
        val failures = mutableListOf<String>()
        val keyStore = sequenceOf("JKS", "PKCS12").mapNotNull { type ->
            runCatching {
                KeyStore.getInstance(type).also { store ->
                    keystore.inputStream().use { store.load(it, storePasswordChars) }
                }
            }.onFailure { failures += "$type: ${it.javaClass.simpleName}" }.getOrNull()
        }.firstOrNull { it.containsAlias(alias) }
            ?: error(
                "Cannot load signing alias '$alias' from ${keystore.absolutePath} " +
                    "(${failures.joinToString()}). Check the keystore type, password, and alias.",
            )

        val signingKey = runCatching { keyStore.getKey(alias, keyPasswordChars) }.getOrElse {
            error("Cannot unlock signing key '$alias': ${it.javaClass.simpleName}")
        }
        check(signingKey is PrivateKey) { "Signing alias '$alias' is not a private-key entry." }

        val certificate = checkNotNull(keyStore.getCertificate(alias)) {
            "Signing alias '$alias' has no certificate."
        }
        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        val expectedSha256 = expectedCertificateSha256.get()
        check(actualSha256 == expectedSha256) {
            "Release certificate mismatch: expected $expectedSha256, found $actualSha256. " +
                "Local and GitHub release APKs must use the same key."
        }

        logger.lifecycle(
            "Verified DiPlay release certificate SHA-256 {} from {}.",
            actualSha256,
            sourceName.get(),
        )
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val androidSigningKeys = listOf(
    "ANDROID_KEYSTORE_PATH",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
)
val localSigningFile = layout.projectDirectory.file("release-signing.properties")
val localSigningProperties = Properties().apply {
    providers.fileContents(localSigningFile).asText.orNull?.reader()?.use(::load)
}
val signingSources = listOf(
    "environment variables" to androidSigningKeys.associateWith {
        providers.environmentVariable(it).orNull.orEmpty()
    },
    "Gradle properties" to androidSigningKeys.associateWith {
        providers.gradleProperty(it).orNull.orEmpty()
    },
    "release-signing.properties" to androidSigningKeys.associateWith {
        localSigningProperties.getProperty(it).orEmpty()
    },
)
val selectedSigningSource = signingSources.firstOrNull { (_, values) ->
    values.values.any(String::isNotEmpty)
}
val androidReleaseSigning = selectedSigningSource?.second.orEmpty()
val missingAndroidSigningKeys = androidSigningKeys.filter {
    androidReleaseSigning[it].isNullOrEmpty()
}

// Both application modules consume the same resolved values. A partially configured higher-priority
// source never borrows passwords or aliases from a lower-priority source.
extra["androidReleaseSigning.values"] = androidReleaseSigning
extra["androidReleaseSigning.missing"] = missingAndroidSigningKeys
extra["androidReleaseSigning.source"] = selectedSigningSource?.first ?: "none"

val expectedReleaseCertificateSha256 =
    "87b38b12788dcb202a961215f2572e30ec2dc9d8ef4bc070d05f77e49291a363"

val verifyAndroidReleaseSigning by tasks.registering(VerifyAndroidReleaseSigningTask::class) {
    group = "verification"
    description = "Verify that local/CI release signing uses the established DiPlay certificate."
    missingKeys.set(missingAndroidSigningKeys)
    sourceName.set(selectedSigningSource?.first ?: "all signing sources")
    androidReleaseSigning["ANDROID_KEYSTORE_PATH"]?.let {
        keystoreFile.fileValue(rootProject.file(it))
    }
    storePassword.set(androidReleaseSigning["ANDROID_KEYSTORE_PASSWORD"].orEmpty())
    keyAlias.set(androidReleaseSigning["ANDROID_KEY_ALIAS"].orEmpty())
    keyPassword.set(androidReleaseSigning["ANDROID_KEY_PASSWORD"].orEmpty())
    expectedCertificateSha256.set(expectedReleaseCertificateSha256)
}

listOf(":mobile", ":automotive").forEach { projectPath ->
    project(projectPath).tasks.configureEach {
        if (name == "validateSigningRelease") {
            dependsOn(verifyAndroidReleaseSigning)
        }
    }
}
