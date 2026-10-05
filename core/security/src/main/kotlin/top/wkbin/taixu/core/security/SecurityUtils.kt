package top.wkbin.taixu.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Security utilities for encryption, key management, and secret handling.
 */
object SecurityUtils {

    private const val AES_KEY_SIZE = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "taixu_master_key"

    /**
     * Creates or retrieves the master key for encrypted preferences.
     */
    fun getOrCreateMasterKey(context: Context): String {
        return MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    }

    /**
     * Creates EncryptedSharedPreferences for storing sensitive data.
     */
    fun createEncryptedPrefs(context: Context, prefsName: String = "secure_prefs"): android.content.SharedPreferences {
        val masterKey = getOrCreateMasterKey(context)
        return EncryptedSharedPreferences.create(
            prefsName,
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Generates a random AES-256 key.
     */
    fun generateAesKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(AES_KEY_SIZE)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts data using AES-256-GCM.
     * Returns Base64 encoded string: IV + Ciphertext + AuthTag
     */
    fun encrypt(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = java.security.SecureRandom().apply { nextBytes(byteArrayOf()) }.let {
            val arr = ByteArray(GCM_IV_LENGTH)
            it.nextBytes(arr)
            arr
        }
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        return android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
    }

    /**
     * Decrypts data using AES-256-GCM.
     * Expects Base64 encoded string: IV + Ciphertext + AuthTag
     */
    fun decrypt(key: SecretKey, encrypted: String): String {
        val combined = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, java.nio.charset.StandardCharsets.UTF_8)
    }

    /**
     * Stores a secret key in Android Keystore.
     */
    fun storeKeyInKeystore(context: Context, alias: String, key: SecretKey): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val entry = KeyStore.SecretKeyEntry(key)
            val protection = KeyStore.ProtectionParameter.Builder().build()
            keyStore.setEntry(alias, entry, protection)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Retrieves a secret key from Android Keystore.
     */
    fun getKeyFromKeystore(context: Context, alias: String): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            entry?.secretKey
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Secret redactor for logging - removes sensitive information from logs.
 */
class SecretRedactor {
    private val patterns = mutableListOf<java.util.regex.Pattern>()

    init {
        // Common secret patterns
        addPattern("(?i)(api[_-]?key|token|secret|password|auth)[\"']?\\s*[:=]\\s*[\"']?([^\"'\\s,}]+)")
        addPattern("(?i)Bearer\\s+([^\\s]+)")
        addPattern("(?i)Authorization:\\s*([^\\s]+)")
    }

    fun addPattern(pattern: String) {
        patterns.add(java.util.regex.Pattern.compile(pattern))
    }

    fun redact(input: String): String {
        var result = input
        for (pattern in patterns) {
            val matcher = pattern.matcher(result)
            result = matcher.replaceAll { m ->
                val prefix = m.group(1)
                "${prefix}=***REDACTED***"
            }
        }
        return result
    }
}

/**
 * Certificate pinning helper for secure network communication.
 */
object CertificatePinner {
    fun create(sha256Hashes: List<String>): okhttp3.CertificatePinner {
        val builder = okhttp3.CertificatePinner.Builder()
        for (hash in sha256Hashes) {
            builder.add("*.taixu.app", "sha256/$hash")
        }
        return builder.build()
    }
}