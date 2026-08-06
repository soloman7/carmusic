package com.carmusic.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SMTP 授权码加解密工具。
 *
 * 背景：smtp_pass 此前明文落盘 DataStore，任何能读到 preferences 文件的人都能拿到邮箱授权码。
 * 现改为使用 Android Keystore 持有的 AES 密钥加密后再存储：
 * - 密钥别名 "carmusic_smtp"，存于硬件/TEE  backed 的 AndroidKeyStore，不可导出；
 * - 算法 AES/GCM/NoPadding（GCM 自带完整性校验，篡改会解密失败）；
 * - DataStore 中存 Base64(iv + ciphertext)，iv 每次加密随机生成 12 字节。
 *
 * 兼容性：旧版本存的是明文，[decrypt] 解密失败时调用方应把原始字符串当明文直接返回，
 * 待用户下次保存时自然升级为密文（见 SettingsRepository）。
 */
object SmtpCrypto {

    private const val KEY_ALIAS = "carmusic_smtp"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12        // GCM 推荐 96 位 iv
    private const val TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** 加密明文，返回 Base64(iv + ciphertext)。 */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    /**
     * 解密 Base64(iv + ciphertext)。
     * 解密失败（旧明文数据、密钥失效、数据被篡改等）会抛异常，由调用方兜底。
     */
    fun decrypt(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        require(raw.size > IV_LEN) { "stored value too short to be ciphertext" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN))
        return String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
    }
}
