package com.sometenn.callcaptions.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface KeyVault {
    fun read(provider: Provider): String
    fun save(text: String, provider: Provider)
}

class InMemoryKeyVault(
    initial: Map<Provider, String> = emptyMap(),
) : KeyVault {
    private val keys = initial.toMutableMap()

    override fun read(provider: Provider): String = keys[provider].orEmpty()

    override fun save(text: String, provider: Provider) {
        if (text.isEmpty()) {
            keys.remove(provider)
        } else {
            keys[provider] = text
        }
    }
}

/**
 * Local API key storage backed by Android Keystore AES-256-GCM + private SharedPreferences.
 */
class AndroidKeystoreVault(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    prefsName: String = DEFAULT_PREFS_NAME,
) : KeyVault {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun read(provider: Provider): String {
        val encoded = prefs.getString(prefKey(provider), null) ?: return ""
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= GCM_IV_LENGTH) {
                throw CaptionError("无法读取本地密钥存储。")
            }
            val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
            val cipherText = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
        } catch (e: CaptionError) {
            throw e
        } catch (_: Throwable) {
            throw CaptionError("无法读取本地密钥存储。请解锁设备后重试。")
        }
    }

    override fun save(text: String, provider: Provider) {
        if (text.isEmpty()) {
            prefs.edit().remove(prefKey(provider)).apply()
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
            val payload = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, payload, 0, iv.size)
            System.arraycopy(cipherText, 0, payload, iv.size, cipherText.size)
            val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
            val committed = prefs.edit().putString(prefKey(provider), encoded).commit()
            if (!committed) {
                throw CaptionError("无法保存密钥。")
            }
        } catch (e: CaptionError) {
            throw e
        } catch (_: Throwable) {
            throw CaptionError("无法保存密钥。")
        }
    }

    private fun prefKey(provider: Provider): String = "api_key_${provider.rawValue}"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val DEFAULT_KEY_ALIAS = "com.sometenn.callcaptions.vault.aes"
        private const val DEFAULT_PREFS_NAME = "callcaptions_vault"
    }
}
