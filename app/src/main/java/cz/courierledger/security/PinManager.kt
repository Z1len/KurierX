package cz.courierledger.security

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinManager(context: Context) {
    private val prefs = context.getSharedPreferences("developer_security", Context.MODE_PRIVATE)

    fun hasPin(): Boolean = prefs.contains("pin_hash")

    fun setPin(pin: String) {
        require(pin.matches(Regex("\\d{6}"))) { "PIN должен содержать ровно 6 цифр" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin.toCharArray(), salt)
        prefs.edit()
            .putString("pin_salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
            .putString("pin_hash", android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP))
            .apply()
        hash.fill(0)
    }

    fun verify(pin: String): Boolean {
        if (!pin.matches(Regex("\\d{6}"))) return false
        val salt = prefs.getString("pin_salt", null)?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) } ?: return false
        val expected = prefs.getString("pin_hash", null)?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) } ?: return false
        val actual = derive(pin.toCharArray(), salt)
        val ok = MessageDigest.isEqual(expected, actual)
        actual.fill(0)
        return ok
    }

    private fun derive(pin: CharArray, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin, salt, 180_000, 256)).encoded
}
