package io.github.sennahdev.funflowplay.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A video in the library, with the scripts found next to it (axis suffix -> document uri). */
data class VideoEntry(
    val uri: String,
    val name: String,
    val folder: String,
    val sizeBytes: Long,
    val modified: Long,
    val scripts: Map<String, String>,
) {
    val title: String get() = name.substringBeforeLast('.')
    val hasScript: Boolean get() = scripts.isNotEmpty()

    /** Main script (video.funscript), else the vibration axis, else any. */
    val mainScript: String? get() = scripts[""] ?: scripts["vib"] ?: scripts.values.firstOrNull()
    val mainScriptName: String get() = if (scripts.containsKey("") || !scripts.containsKey("vib")) "$title.funscript" else "$title.vib.funscript"
}

object FileNames {
    val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "ts", "m2ts", "mpg", "mpeg", "flv")
    private val AXES = setOf("surge", "sway", "twist", "roll", "pitch", "vib", "pump", "stroke", "valve", "suck", "lube")

    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO_EXT

    /** "clip.vib.funscript" -> ("clip", "vib"); "clip.funscript" -> ("clip", ""); else null. */
    fun splitScript(name: String): Pair<String, String>? {
        if (!name.lowercase().endsWith(".funscript")) return null
        val stem = name.dropLast(".funscript".length)
        val axis = stem.substringAfterLast('.', "").lowercase()
        return if (axis in AXES) stem.dropLast(axis.length + 1) to axis else stem to ""
    }
}

/**
 * Scans a folder picked with the system folder picker (Storage Access Framework).
 * Uses DocumentsContract queries directly - much faster than DocumentFile on big folders.
 * The result is cached, so the library shows instantly on the next start.
 */
class LibraryRepository(private val context: Context) {
    private val cacheFile = File(context.filesDir, "library.json")
    private val _entries = MutableStateFlow(loadCache())
    val entries: StateFlow<List<VideoEntry>> = _entries.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    suspend fun scan(treeUri: Uri) = withContext(Dispatchers.IO) {
        if (_scanning.value) return@withContext
        _scanning.value = true
        try {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val videos = mutableListOf<Pair<String, Row>>() // parent folder name + row
            val scripts = HashMap<String, MutableMap<String, String>>() // "parentId|base" -> axis -> uri
            walk(treeUri, rootId, "", 0) { parentId, folderName, row ->
                if (FileNames.isVideo(row.name)) {
                    videos += "$parentId\u0000$folderName" to row
                } else {
                    FileNames.splitScript(row.name)?.let { (base, axis) ->
                        scripts.getOrPut("$parentId|${base.lowercase()}") { HashMap() }[axis] =
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, row.id).toString()
                    }
                }
            }
            val result = videos.map { (parent, row) ->
                val (parentId, folderName) = parent.split('\u0000', limit = 2)
                val base = row.name.substringBeforeLast('.').lowercase()
                VideoEntry(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, row.id).toString(),
                    name = row.name,
                    folder = folderName,
                    sizeBytes = row.size,
                    modified = row.modified,
                    scripts = scripts["$parentId|$base"] ?: emptyMap(),
                )
            }
            _entries.value = result
            saveCache(result)
        } catch (e: Exception) {
            Log.w("Library", "scan failed", e)
        } finally {
            _scanning.value = false
        }
    }

    fun clear() {
        _entries.value = emptyList()
        cacheFile.delete()
    }

    private class Row(val id: String, val name: String, val mime: String, val size: Long, val modified: Long)

    private fun walk(tree: Uri, docId: String, folderName: String, depth: Int, visit: (String, String, Row) -> Unit) {
        if (depth > 12) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val rows = mutableListOf<Row>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                rows += Row(
                    c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "",
                    if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        }
        for (row in rows) {
            if (row.name.startsWith(".")) continue
            if (row.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                walk(tree, row.id, row.name, depth + 1, visit)
            } else {
                visit(docId, folderName, row)
            }
        }
    }

    private fun saveCache(list: List<VideoEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("uri", e.uri).put("name", e.name).put("folder", e.folder)
                    .put("size", e.sizeBytes).put("modified", e.modified)
                    .put("scripts", JSONObject(e.scripts as Map<*, *>)),
            )
        }
        runCatching { cacheFile.writeText(arr.toString()) }
    }

    private fun loadCache(): List<VideoEntry> = runCatching {
        val arr = JSONArray(cacheFile.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val s = o.getJSONObject("scripts")
            VideoEntry(
                o.getString("uri"), o.getString("name"), o.optString("folder"),
                o.optLong("size"), o.optLong("modified"),
                s.keys().asSequence().associateWith { s.getString(it) },
            )
        }
    }.getOrDefault(emptyList())
}
