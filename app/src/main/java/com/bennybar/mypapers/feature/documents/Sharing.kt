package com.bennybar.mypapers.feature.documents

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.bennybar.mypapers.app
import com.bennybar.mypapers.data.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bennybar.mypapers.data.Export
import com.bennybar.mypapers.search.Filters
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Sends papers to another app (WhatsApp, email, an accountant) under their titles. */
object Sharing {
    /** False when none of them has its file on this phone yet. */
    suspend fun share(context: Context, docs: List<Document>): Boolean {
        val files = withContext(Dispatchers.IO) {
            // Named copies: the stored files are called by their ids.
            val dir = shareDir(context)
            fileNames(docs.filter { it.fileLocal }).map { (d, name) ->
                File(dir, name).also { app.repository.files.file(d.id).copyTo(it, overwrite = true) } to (d.mimeType ?: "application/octet-stream")
            }
        }
        if (files.isEmpty()) return false
        val uris = files.map { (f, _) -> FileProvider.getUriForFile(context, "${context.packageName}.files", f) }
        val types = files.map { it.second }.toSet()
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }.apply {
            type = types.singleOrNull() ?: if (types.all { it.startsWith("image/") }) "image/*" else "*/*"
            // Read access for the receiving app, for every file.
            clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /**
     * One ZIP with papers.csv (see [Export.csv]) and the files under their titles (with a .md
     * of the text where it was extracted), sent to
     * wherever the user picks (Drive, email, Files). Papers whose file isn't on the phone
     * yet are in the CSV without a file.
     */
    suspend fun export(context: Context, docs: List<Document>) {
        val zip = withContext(Dispatchers.IO) {
            val sorted = docs.sortedBy { Filters.paperDay(it) }
            val all = fileNames(sorted)
            val names = all.filter { it.first.fileLocal }
            val zip = File(shareDir(context), "My Papers ${LocalDate.now()}.zip")
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                out.putNextEntry(ZipEntry("papers.csv"))
                out.write(Export.csv(sorted, names.associate { (d, name) -> d.id to "files/$name" }).toByteArray())
                out.closeEntry()
                names.forEach { (d, name) ->
                    out.putNextEntry(ZipEntry("files/$name"))
                    app.repository.files.file(d.id).inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
                all.filter { it.first.transcript.isNotBlank() }.forEach { (d, name) ->
                    out.putNextEntry(ZipEntry("files/${name.substringBeforeLast('.')}.md"))
                    out.write(d.transcript.toByteArray())
                    out.closeEntry()
                }
            }
            zip
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        val intent = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .setType("application/zip")
            .apply { clipData = ClipData.newRawUri(null, uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * A new folder for this share: the app shared to earlier may not have read its file yet
     * (an email not sent). Folders older than a day are removed.
     */
    private fun shareDir(context: Context): File {
        val root = File(context.cacheDir, "share")
        root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.deleteRecursively() }
        return File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
    }

    /** Each paper's file named by its title ("Shufersal receipt.pdf"), unique within the batch. */
    private fun fileNames(docs: List<Document>): List<Pair<Document, String>> {
        val used = mutableSetOf<String>()
        return docs.map { d ->
            val base = d.title().replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifBlank { "Paper" }
            var name = "$base.${extension(d)}"
            var n = 2
            while (!used.add(name)) name = "$base ($n).${extension(d)}".also { n++ }
            d to name
        }
    }

    private fun extension(d: Document) = when (d.mimeType) {
        "application/pdf" -> "pdf"
        "image/png" -> "png"
        "image/heic" -> "heic"
        "image/webp" -> "webp"
        else -> if (d.mimeType?.startsWith("image/") == true) "jpg" else d.fileName?.substringAfterLast('.', "")?.ifEmpty { "bin" } ?: "bin"
    }
}
