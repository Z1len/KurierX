package cz.courierledger.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupWorker(ctx: Context, params: WorkerParameters): CoroutineWorker(ctx,params) {
    override suspend fun doWork(): Result = runCatching {
        val backupDir = File(applicationContext.filesDir, "backups").apply { mkdirs() }
        backupDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(14)?.forEach { it.delete() }
        val out = File(backupDir, "auto-${System.currentTimeMillis()}.clb.zip")
        ZipOutputStream(FileOutputStream(out).buffered()).use { zip ->
            val db = applicationContext.getDatabasePath("courier-ledger.db")
            listOf(db, File(db.path+"-wal"), File(db.path+"-shm")).filter { it.exists() }.forEach { f ->
                zip.putNextEntry(ZipEntry("db/${f.name}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            val photos = File(applicationContext.filesDir,"photos")
            if (photos.exists()) photos.walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry("photos/${f.relativeTo(photos).path}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        Result.success()
    }.getOrElse { Result.retry() }
}
