package com.acadbot.app

import java.security.MessageDigest

/** Verifies tokens made by the desktop app (format: sha256hex:expiryEpochSeconds). */
object Auth {
    private const val SALT = "w7vQw7nQw5bQw6vQw4bQw7rQw5nQw6zQw4pQw7tQw5qQw6xQw4sQw7uQw5oQw6yQw4qQw7sQw5p"

    fun verify(token: String, email: String): Boolean {
        val parts = token.trim().split(":")
        if (parts.size != 2) return false
        val expiry = parts[1].toLongOrNull() ?: return false
        if (System.currentTimeMillis() / 1000 > expiry) return false
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${email.trim()}$expiry$SALT".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex == parts[0]
    }
}
