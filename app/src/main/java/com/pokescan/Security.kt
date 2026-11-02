package com.pokescan

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

object Crypto {
    private const val HASH_ITER = 120_000
    private const val BACKUP_ITER = 150_000
    private val MAGIC = "PSB1".toByteArray()
    private val rnd = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }
    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    private fun pbkdf2(pass: CharArray, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(pass, salt, iter, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun hashPassword(pass: String, salt: ByteArray): String = b64(pbkdf2(pass.toCharArray(), salt, HASH_ITER))

    fun verify(pass: String, saltB64: String, hashB64: String): Boolean {
        val calc = pbkdf2(pass.toCharArray(), unb64(saltB64), HASH_ITER)
        return MessageDigest.isEqual(calc, unb64(hashB64))
    }

    /** Backup: "PSB1" + salt(16) + iv(12) + AES-256-GCM(dados), chave derivada da senha com PBKDF2. */
    fun encrypt(plain: ByteArray, pass: String): ByteArray {
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val key = pbkdf2(pass.toCharArray(), salt, BACKUP_ITER)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return MAGIC + salt + iv + c.doFinal(plain)
    }

    fun decrypt(data: ByteArray, pass: String): ByteArray {
        require(data.size > 4 + 16 + 12 + 16 && data.copyOfRange(0, 4).contentEquals(MAGIC)) { "formato" }
        val salt = data.copyOfRange(4, 20)
        val iv = data.copyOfRange(20, 32)
        val ct = data.copyOfRange(32, data.size)
        val key = pbkdf2(pass.toCharArray(), salt, BACKUP_ITER)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return c.doFinal(ct)
    }
}

object Biometrics {
    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, title: String, onOk: () -> Unit, onErr: (String) -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val bp = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onOk()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val silent = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                if (!silent) onErr(errString.toString())
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText("Usar senha")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        bp.authenticate(info)
    }
}
