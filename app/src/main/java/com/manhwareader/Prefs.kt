package com.manhwareader

import android.content.Context
import android.net.Uri

/** Small on-device storage: chosen folder, selection, reading position, cached page counts. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("manhwa_reader", Context.MODE_PRIVATE)

    /** Every folder the user has added. */
    var trees: List<Uri>
        get() {
            val raw = sp.getString("trees", null)
            if (raw == null) {
                // Older versions stored a single folder.
                val old = sp.getString("tree", null)
                return if (old != null) listOf(Uri.parse(old)) else emptyList()
            }
            return raw.split('\n').filter { it.isNotBlank() }.map { Uri.parse(it) }
        }
        set(value) {
            sp.edit().putString("trees", value.joinToString("\n") { it.toString() }).apply()
        }

    var selection: Set<String>
        get() = (sp.getStringSet("selection", null) ?: emptySet()).toSet()
        set(value) {
            sp.edit().putStringSet("selection", value).apply()
        }

    fun resume(): Pair<String, Int>? {
        val uri = sp.getString("resume_uri", null) ?: return null
        return uri to sp.getInt("resume_page", 0)
    }

    fun saveResume(uri: String, page: Int) {
        sp.edit().putString("resume_uri", uri).putInt("resume_page", page).apply()
    }

    fun pageInfo(key: String): PdfInfo? {
        val raw = sp.getString("pi_$key", null) ?: return null
        val parts = raw.split(';')
        if (parts.size != 2) return null
        val pages = parts[0].toIntOrNull() ?: return null
        val ratio = parts[1].toFloatOrNull() ?: return null
        return PdfInfo(pages, ratio)
    }

    fun savePageInfos(infos: Map<String, PdfInfo>) {
        val e = sp.edit()
        for ((key, info) in infos) e.putString("pi_$key", "${info.pageCount};${info.ratio}")
        e.apply()
    }
}
