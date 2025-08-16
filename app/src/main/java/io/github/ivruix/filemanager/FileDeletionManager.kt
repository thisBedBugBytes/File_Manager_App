package io.github.ivruix.filemanager

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/**
 * Manager that attempts to delete ANY file shown by the file manager.
 * It does not itself show UI; instead it returns a DeleteResult that the Activity handles.
 */
class FileDeletionManager(private val context: Context) {

    sealed class DeleteResult {
        object Deleted : DeleteResult()
        data class RequireMediaConfirmation(val intentSender: PendingIntent, val uris: List<Uri>) : DeleteResult()
        object RequireTreePicker : DeleteResult() // Activity should ask user to pick a folder (SAF)
        data class Failed(val reason: String) : DeleteResult()
    }

    /** Quick check: is file under app-specific external dir? (fast path) */
    fun isInAppExternalDir(file: File): Boolean {
        val appDir = context.getExternalFilesDir(null) ?: return false
        return try {
            file.canonicalPath.startsWith(appDir.canonicalPath)
        } catch (e: IOException) {
            false
        }
    }

    /** Delete a file if it's app-owned (direct delete). Returns true if deleted. */
    fun deleteDirect(file: File): Boolean {
        return try {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Main entry. Try direct -> MediaStore -> SAF hint.
     * Synchronous and quick to call from UI thread for quick checks (actual deletion via ContentResolver is quick).
     */
    fun tryDeleteAny(file: File): DeleteResult {
        // 1) Direct (app dir) fast path
        if (isInAppExternalDir(file)) {
            val ok = deleteDirect(file)
            return if (ok) DeleteResult.Deleted else DeleteResult.Failed("direct delete failed")
        }

        // 2) Try to resolve a MediaStore Uri for the file
        val mediaUri = findMediaStoreUriForFile(file)
        if (mediaUri != null) {
            // On Android 11+ deletion of media you don't own usually requires system confirmation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return try {
                    val sender = MediaStore.createDeleteRequest(context.contentResolver, listOf(mediaUri))
                    DeleteResult.RequireMediaConfirmation(sender, listOf(mediaUri))
                } catch (e: Exception) {
                    // fallback: try to delete directly via contentResolver
                    return try {
                        val rows = context.contentResolver.delete(mediaUri, null, null)
                        if (rows > 0) DeleteResult.Deleted else DeleteResult.Failed("contentResolver delete returned 0")
                    } catch (ex: Exception) {
                        DeleteResult.Failed("media delete failed: ${ex.message}")
                    }
                }
            } else {
                // Older Android: try delete via ContentResolver immediately
                return try {
                    val rows = context.contentResolver.delete(mediaUri, null, null)
                    if (rows > 0) DeleteResult.Deleted else DeleteResult.Failed("contentResolver delete returned 0")
                } catch (e: Exception) {
                    DeleteResult.Failed("media delete failed: ${e.message}")
                }
            }
        }

        // 3) Not app-owned and not in MediaStore -> tell caller to use SAF (ask user to pick folder)
        return DeleteResult.RequireTreePicker
    }

    /**
     * Attempt to resolve a MediaStore Uri for the file path.
     * Good pragmatic compatibility: checks Images, Video, Audio, then Files collection.
     * Note: uses DATA/_data when available (deprecated) but practical for many devices.
     */
    fun findMediaStoreUriForFile(file: File): Uri? {
        val absPath = file.absolutePath
        val cr = context.contentResolver

        // helper
        fun query(collection: Uri): Uri? {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DATA} = ?"
            val args = arrayOf(absPath)
            cr.query(collection, projection, selection, args, null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    return Uri.withAppendedPath(collection, id.toString())
                }
            }
            return null
        }

        // try images, video, audio, generic files
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)?.let { return it }
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)?.let { return it }
        query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)?.let { return it }
        query(MediaStore.Files.getContentUri("external"))?.let { return it }
        return null
    }

    /**
     * After the Activity obtains a persisted tree URI (SAF), you can call this to perform a SAF delete.
     * Returns true if deleted.
     *
     * The implementation does a name-based search under the tree. For best results, the Activity should
     * pick the actual parent folder of the file. You can improve this by computing relative path under the tree.
     */
    fun performSafDelete(treeUri: Uri, file: File): Boolean {
        val docTree = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        // try to find a DocumentFile that matches the file name (simple DFS)
        val candidates = findDocumentFileRecursively(docTree, file.name ?: return false)
        val target = candidates.firstOrNull { it.name == file.name && !it.isDirectory }
        return target?.delete() ?: false
    }

    // DFS find by filename (could return multiple; we pick the first)
    private fun findDocumentFileRecursively(root: DocumentFile, fileName: String): List<DocumentFile> {
        val found = mutableListOf<DocumentFile>()
        if (!root.isDirectory) {
            if (root.name == fileName) found.add(root)
            return found
        }
        val children = root.listFiles()
        for (child in children) {
            if (child.isDirectory) {
                found.addAll(findDocumentFileRecursively(child, fileName))
            } else {
                if (child.name == fileName) found.add(child)
            }
        }
        return found
    }
}
