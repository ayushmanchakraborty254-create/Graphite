package com.graphite.app.core.sharing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.graphite.app.core.drive.NotePdfGenerator
import com.graphite.app.data.model.Book
import com.graphite.app.data.model.NotebookItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object FileSharingHelper {

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Document" }
    }

    private fun getFileUri(context: Context, file: File, displayName: String? = null): Uri {
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            // If the file path is not covered by FileProvider roots, copy to cache/shared_temp
            val tempDir = File(context.cacheDir, "shared_temp").apply { mkdirs() }
            val ext = file.extension.ifBlank { "pdf" }
            val safeName = sanitizeFileName(displayName ?: file.nameWithoutExtension)
            val destFile = File(tempDir, "$safeName.$ext")
            file.copyTo(destFile, overwrite = true)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
        }
    }

    /**
     * Shares a single Book from the Bookshelf tab (PDF, EPUB, TXT, etc.)
     */
    fun shareBook(context: Context, book: Book) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val uri: Uri? = if (!book.filePath.isNullOrBlank()) {
                    val file = File(book.filePath)
                    if (file.exists() && file.length() > 0) {
                        getFileUri(context, file, book.title)
                    } else null
                } else if (book.content.isNotBlank()) {
                    val tempDir = File(context.cacheDir, "shared_books").apply { mkdirs() }
                    val safeName = sanitizeFileName(book.title)
                    val txtFile = File(tempDir, "$safeName.txt")
                    txtFile.writeText(book.content)
                    getFileUri(context, txtFile, book.title)
                } else null

                withContext(Dispatchers.Main) {
                    if (uri != null) {
                        val mime = if (book.mimeType.isNotBlank()) book.mimeType else "application/pdf"
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, book.title)
                            putExtra(Intent.EXTRA_TEXT, book.title)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = Intent.createChooser(intent, "Share \"${book.title}\" via").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(chooser)
                    } else {
                        val msg = if (book.isCloudOnly) {
                            "\"${book.title}\" is in Google Drive. Please download it first to share."
                        } else {
                            "Unable to find local file for \"${book.title}\"."
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error sharing book: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Shares multiple Books from the Bookshelf tab
     */
    fun shareBooks(context: Context, books: List<Book>) {
        if (books.isEmpty()) return
        if (books.size == 1) {
            shareBook(context, books.first())
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val uris = ArrayList<Uri>()
                for (book in books) {
                    if (!book.filePath.isNullOrBlank()) {
                        val file = File(book.filePath)
                        if (file.exists() && file.length() > 0) {
                            uris.add(getFileUri(context, file, book.title))
                        }
                    } else if (book.content.isNotBlank()) {
                        val tempDir = File(context.cacheDir, "shared_books").apply { mkdirs() }
                        val safeName = sanitizeFileName(book.title)
                        val txtFile = File(tempDir, "$safeName.txt")
                        txtFile.writeText(book.content)
                        uris.add(getFileUri(context, txtFile, book.title))
                    }
                }

                withContext(Dispatchers.Main) {
                    if (uris.isNotEmpty()) {
                        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "*/*"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            putExtra(Intent.EXTRA_SUBJECT, "Shared Books from Graphite")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = Intent.createChooser(intent, "Share ${uris.size} Books via").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(chooser)
                    } else {
                        Toast.makeText(context, "No local book files available to share.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error sharing books: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Shares a single Notebook or imported PDF from the Notes tab.
     * If the note is an imported PDF, the original PDF is shared directly.
     * If it's a handwritten Graphite notebook, it is exported to standard PDF and shared.
     */
    fun shareNotebook(context: Context, notebook: NotebookItem) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val uri: Uri = if (!notebook.filePath.isNullOrBlank()) {
                    val file = File(notebook.filePath)
                    if (file.exists() && file.length() > 0) {
                        getFileUri(context, file, notebook.title)
                    } else {
                        generateNotePdfUri(context, notebook)
                    }
                } else {
                    generateNotePdfUri(context, notebook)
                }

                withContext(Dispatchers.Main) {
                    val mime = if (!notebook.mimeType.isNullOrBlank()) notebook.mimeType else "application/pdf"
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, notebook.title)
                        putExtra(Intent.EXTRA_TEXT, notebook.title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(intent, "Share \"${notebook.title}\" via").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error sharing note: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Shares multiple Notebooks or PDFs from the Notes tab.
     */
    fun shareNotebooks(context: Context, notebooks: List<NotebookItem>) {
        if (notebooks.isEmpty()) return
        if (notebooks.size == 1) {
            shareNotebook(context, notebooks.first())
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val uris = ArrayList<Uri>()
                for (notebook in notebooks) {
                    try {
                        val uri = if (!notebook.filePath.isNullOrBlank()) {
                            val file = File(notebook.filePath)
                            if (file.exists() && file.length() > 0) {
                                getFileUri(context, file, notebook.title)
                            } else {
                                generateNotePdfUri(context, notebook)
                            }
                        } else {
                            generateNotePdfUri(context, notebook)
                        }
                        uris.add(uri)
                    } catch (_: Exception) {}
                }

                withContext(Dispatchers.Main) {
                    if (uris.isNotEmpty()) {
                        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "*/*"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            putExtra(Intent.EXTRA_SUBJECT, "Shared Notes from Graphite")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = Intent.createChooser(intent, "Share ${uris.size} Notes via").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(chooser)
                    } else {
                        Toast.makeText(context, "Unable to prepare notes for sharing.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error sharing notes: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun generateNotePdfUri(context: Context, notebook: NotebookItem): Uri {
        val shareDir = File(context.cacheDir, "shared_notes").apply { mkdirs() }
        val safeName = sanitizeFileName(notebook.title)
        val pdfFile = File(shareDir, "$safeName.pdf")
        val pdfBytes = NotePdfGenerator.generatePdf(notebook)
        pdfFile.writeBytes(pdfBytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
    }
}
