package com.carmusic.source.crypto

import android.util.Base64
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 weapi 加密：AES-128-CBC + RSA
 * 移植自 Listen 1 / NeteaseCloudMusicApi
 */
object NeteaseCrypto {

    private const val NONCE = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val PUB_KEY = "010001"
    private const val MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"

    private val random = SecureRandom()

    /** RSA 公钥/模数只 parse 一次（原来每次请求重复构造） */
    private val pubKeyBigInt = BigInteger(PUB_KEY, 16)
    private val modulusBigInt = BigInteger(MODULUS, 16)

    /**
     * 加密请求体，返回 (params, encSecKey)
     */
    fun encrypt(payload: String): Pair<String, String> {
        val secretKey = generateSecretKey(16)
        val params = aesEncrypt(aesEncrypt(payload, NONCE), secretKey)
        val encSecKey = rsaEncrypt(secretKey.reversed())
        return params to encSecKey
    }

    private fun aesEncrypt(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(IV.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun rsaEncrypt(text: String): String {
        val textHex = text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        val textBigInt = BigInteger(textHex, 16)
        val encrypted = textBigInt.modPow(pubKeyBigInt, modulusBigInt)
        return encrypted.toString(16).padStart(256, '0')
    }

    private fun generateSecretKey(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }
}
