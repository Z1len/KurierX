package cz.courierledger.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DatabaseKeyManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("secure_bootstrap", Context.MODE_PRIVATE)
    private val alias = "courier_ledger_db_wrap_key"

    fun getOrCreateDatabasePassphrase(): ByteArray {
        val encoded = prefs.getString("db_key", null)
        return if (encoded != null) decrypt(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP))
        else ByteArray(32).also { SecureRandom().nextBytes(it) }.also { raw ->
            prefs.edit().putString("db_key", android.util.Base64.encodeToString(encrypt(raw), android.util.Base64.NO_WRAP)).apply()
        }
    }

    /** Re-wrap an existing SQLCipher passphrase with this device's Android Keystore key.
     * Used only while applying a password-protected portable backup on a new phone. */
    fun importDatabasePassphrase(raw: ByteArray) {
        require(raw.size >= 16) { "Некорректный ключ базы в backup" }
        prefs.edit().putString("db_key", android.util.Base64.encodeToString(encrypt(raw), android.util.Base64.NO_WRAP)).commit()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private fun encrypt(raw: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(raw)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob.copyOfRange(12, blob.size))
    }
}
