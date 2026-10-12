package com.bennybar.mypapers.feature.documents

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.bennybar.mypapers.app
import com.bennybar.mypapers.data.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Sends papers to another app (WhatsApp, email, an accountant) under their titles. */
object Sharing {
    /** False when none of them has its file on this phone yet. */
    suspend fun share(context: Context, docs: List<Document>): Boolean {
        val files = withContext(Dispatchers.IO) {
            // Named copies: the stored files are called by their ids.
            val dir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
            val used = mutableSetOf<String>()
            docs.filter { it.fileLocal }.map { d ->
                val base = d.title().replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifBlank { "Paper" }
                var name = "$base.${extension(d)}"
                var n = 2
                while (!used.add(name)) name = "$base ($n).${extension(d)}".also { n++ }
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

    private fun extension(d: Document) = when (d.mimeType) {
        "application/pdf" -> "pdf"
        "image/png" -> "png"
        "image/heic" -> "heic"
        "image/webp" -> "webp"
        else -> if (d.mimeType?.startsWith("image/") == true) "jpg" else d.fileName?.substringAfterLast('.', "")?.ifEmpty { "bin" } ?: "bin"
    }
}
