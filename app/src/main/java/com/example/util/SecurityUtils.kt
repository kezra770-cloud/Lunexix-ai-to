package com.example.util

import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

object SecurityUtils {
    fun generateSalt(): String {
        val random = SecureRandom()
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun hashPassword(password: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val saltedPassword = password + salt
        val hashBytes = digest.digest(saltedPassword.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hashBytes, Base64.NO_WRAP)
    }
}
