package cz.courierledger.backup

import android.content.Context
import cz.courierledger.security.DatabaseKeyManager
import java.io.*
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable backup format CLBK2.
 * The whole archive is AES-GCM encrypted with a user passphrase. Inside it we keep the SQLCipher
 * database, photos, selected settings and the raw SQLCipher passphrase. The latter is safe here
 * because it exists only inside the password-encrypted archive; on another phone it is re-wrapped
 * by that phone's Android Keystore before Room opens the restored database.
 */
class BackupManager(private val context: Context) {
    fun createPortableBackup(destination: File, passphrase: CharArray) {
        require(passphrase.size >= 6) { "Пароль backup должен содержать минимум 6 символов" }
        val temp = File(context.cacheDir, "backup_${System.currentTimeMillis()}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { zip ->
            zip.putNextEntry(ZipEntry("manifest/version.txt")); zip.write("2".toByteArray()); zip.closeEntry()
            val dbPass = DatabaseKeyManager(context).getOrCreateDatabasePassphrase()
            zip.putNextEntry(ZipEntry("security/db_passphrase.bin")); zip.write(dbPass); zip.closeEntry(); dbPass.fill(0)
            addIfExists(zip, context.getDatabasePath("courier-ledger.db"), "db/courier-ledger.db")
            addIfExists(zip, File(context.getDatabasePath("courier-ledger.db").path+"-wal"), "db/courier-ledger.db-wal")
            addIfExists(zip, File(context.getDatabasePath("courier-ledger.db").path+"-shm"), "db/courier-ledger.db-shm")
            addDir(zip, File(context.filesDir,"photos"), "photos")
            addPrefs(zip, "courier_ledger_settings.xml")
            addPrefs(zip, "developer_security.xml")
        }
        encrypt(temp, destination, passphrase)
        temp.delete()
    }

    /** Decrypts and validates a portable backup, then stages it for the next cold start. */
    fun stagePortableRestore(source: File, passphrase: CharArray) {
        require(passphrase.size >= 6) { "Введите пароль backup" }
        val tempZip = File(context.cacheDir, "restore_${System.currentTimeMillis()}.zip")
        decrypt(source, tempZip, passphrase)
        val pending = File(context.filesDir, RESTORE_DIR).apply { deleteRecursively(); mkdirs() }
        unzipSafely(tempZip, pending)
        tempZip.delete()
        require(File(pending, "manifest/version.txt").readText().trim() in setOf("1","2")) { "Неизвестная версия backup" }
        require(File(pending, "db/courier-ledger.db").exists()) { "В backup отсутствует база данных" }
        require(File(pending, "security/db_passphrase.bin").exists()) { "Backup не содержит переносимый ключ базы" }
        File(pending, READY_MARKER).writeText("ready")
    }

    fun automaticBackups(): List<File> = File(context.filesDir, "backups").listFiles()
        ?.filter { it.isFile }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

    private fun addPrefs(zip: ZipOutputStream, name: String) {
        val f = File(context.applicationInfo.dataDir, "shared_prefs/$name")
        addIfExists(zip, f, "prefs/$name")
    }

    private fun addIfExists(zip: ZipOutputStream, file: File, name: String) {
        if(file.exists()) { zip.putNextEntry(ZipEntry(name)); file.inputStream().use{it.copyTo(zip)}; zip.closeEntry() }
    }
    private fun addDir(zip: ZipOutputStream, dir: File, prefix: String) {
        if(!dir.exists()) return
        dir.walkTopDown().filter{it.isFile}.forEach{f -> addIfExists(zip,f,"$prefix/${f.relativeTo(dir).path.replace(File.separatorChar,'/')}") }
    }

    private fun encrypt(input: File, output: File, passphrase: CharArray) {
        val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}
        val iv=ByteArray(12).also{SecureRandom().nextBytes(it)}
        val keyBytes=deriveKey(passphrase,salt)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(keyBytes,"AES"),GCMParameterSpec(128,iv))
        output.outputStream().buffered().use { out ->
            out.write(MAGIC.toByteArray()); out.write(salt); out.write(iv)
            CipherOutputStream(out,cipher).use { cos -> input.inputStream().buffered().use { it.copyTo(cos) } }
        }
        keyBytes.fill(0)
    }

    private fun decrypt(input: File, output: File, passphrase: CharArray) {
        input.inputStream().buffered().use { source ->
            val magic = ByteArray(MAGIC.length).also { require(source.read(it) == it.size) { "Повреждённый backup" } }
            require(String(magic) == MAGIC) { "Это не CourierLedger backup или формат устарел" }
            val salt=ByteArray(16).also { require(source.read(it) == it.size) { "Повреждённый backup" } }
            val iv=ByteArray(12).also { require(source.read(it) == it.size) { "Повреждённый backup" } }
            val keyBytes=deriveKey(passphrase,salt)
            try {
                val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(keyBytes,"AES"),GCMParameterSpec(128,iv))
                CipherInputStream(source,cipher).use { cis -> output.outputStream().buffered().use { cis.copyTo(it) } }
            } catch (t: Throwable) {
                output.delete()
                throw IllegalArgumentException("Неверный пароль или backup повреждён", t)
            } finally { keyBytes.fill(0) }
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase,salt,210_000,256)).encoded

    private fun unzipSafely(zipFile: File, target: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(target, entry.name).canonicalFile
                require(out.path.startsWith(target.canonicalPath + File.separator)) { "Опасный путь в backup" }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs(); out.outputStream().buffered().use { zin.copyTo(it) }
                }
                zin.closeEntry()
            }
        }
    }

    companion object {
        private const val MAGIC = "CLBK2"
        private const val RESTORE_DIR = "restore_pending"
        private const val READY_MARKER = ".ready"

        /** Called before Room/SQLCipher is opened. */
        fun applyPendingRestore(context: Context): Boolean {
            val pending = File(context.filesDir, RESTORE_DIR)
            if (!File(pending, READY_MARKER).exists()) return false
            val keyFile = File(pending, "security/db_passphrase.bin")
            val dbFile = File(pending, "db/courier-ledger.db")
            require(keyFile.exists() && dbFile.exists()) { "Неполный staged backup" }

            val dbPass = keyFile.readBytes()
            DatabaseKeyManager(context).importDatabasePassphrase(dbPass)
            dbPass.fill(0)

            val liveDb = context.getDatabasePath("courier-ledger.db")
            liveDb.parentFile?.mkdirs()
            listOf(liveDb, File(liveDb.path+"-wal"), File(liveDb.path+"-shm")).forEach { it.delete() }
            dbFile.copyTo(liveDb, overwrite = true)
            File(pending, "db/courier-ledger.db-wal").takeIf { it.exists() }?.copyTo(File(liveDb.path+"-wal"), overwrite = true)
            File(pending, "db/courier-ledger.db-shm").takeIf { it.exists() }?.copyTo(File(liveDb.path+"-shm"), overwrite = true)

            val stagedPhotos = File(pending,"photos")
            if (stagedPhotos.exists()) {
                val livePhotos = File(context.filesDir,"photos").apply { deleteRecursively(); mkdirs() }
                stagedPhotos.walkTopDown().filter { it.isFile }.forEach { src ->
                    val dst = File(livePhotos, src.relativeTo(stagedPhotos).path); dst.parentFile?.mkdirs(); src.copyTo(dst, overwrite=true)
                }
            }
            val prefsDir = File(pending,"prefs")
            if (prefsDir.exists()) {
                val livePrefs = File(context.applicationInfo.dataDir,"shared_prefs").apply { mkdirs() }
                prefsDir.listFiles()?.filter { it.isFile && it.name != "secure_bootstrap.xml" }?.forEach { it.copyTo(File(livePrefs,it.name),overwrite=true) }
            }
            pending.deleteRecursively()
            return true
        }
    }
}
