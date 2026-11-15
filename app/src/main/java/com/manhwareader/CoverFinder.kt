package com.manhwareader

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looks a manhwa up by name on AniList, then MangaDex (both free manga databases), and downloads its cover.
 * Only the cleaned-up name is sent. Runs on a background thread. Returns null if nothing fits.
 */
object CoverFinder {
    private const val API = "https://graphql.anilist.co"
    private const val UA = "NuxReader/1.9 (Android manhwa reader)"
    private const val MAX_IMAGE = 8L * 1024 * 1024

    private val QUERY = """
        query (${'$'}s: String) {
          Page(perPage: 6) {
            media(search: ${'$'}s, type: MANGA, sort: SEARCH_MATCH) {
              title { romaji english native }
              synonyms
              coverImage { extraLarge large }
            }
          }
        }
    """.trimIndent()

    private val NOISE = Regex(
        "\\b(chapters?|chap|ch|episodes?|ep|pdf|manhwa|manga|manhua|webtoon|hq|hd|complete|completed|" +
            "season\\s*\\d+|s\\d+|vol(ume)?\\s*\\d+|all|raw|eng|english|official)\\b"
    )

    /** "Solo_Leveling (Season 1) [HQ]" -> "solo leveling" */
    fun clean(name: String): String {
        var s = name.lowercase()
        s = s.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}"), " ")
        s = s.replace(Regex("[_.\\-:,!?'\"]+"), " ")
        s = s.replace(NOISE, " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun words(s: String): Set<String> =
        clean(s).split(' ').filter { it.isNotEmpty() }.toSet()

    /** 0..1: how much two names share. */
    private fun similarity(a: String, b: String): Double {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val common = x.intersect(y).size.toDouble()
        return common / x.union(y).size
    }

    /**
     * Returns the cover as image bytes, or null. [strict] is used for the automatic search,
     * so a doubtful match is skipped instead of giving a wrong cover.
     */
    fun find(name: String, strict: Boolean): ByteArray? {
        val query = clean(name)
        if (query.length < 2) return null
        return findAniList(query, strict) ?: findMangaDex(query, strict)
    }

    private fun findMangaDex(query: String, strict: Boolean): ByteArray? {
        val url = "https://api.mangadex.org/manga?limit=8&order%5Brelevance%5D=desc" +
            "&includes%5B%5D=cover_art&title=" + URLEncoder.encode(query, "UTF-8")
        val json = get(url) ?: return null
        val data = json.optJSONArray("data") ?: return null

        var bestScore = 0.0
        var bestUrl: String? = null
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id", "")
            val attrs = m.optJSONObject("attributes") ?: continue
            val titles = ArrayList<String>()
            attrs.optJSONObject("title")?.let { t ->
                for (k in t.keys()) titles.add(t.optString(k))
            }
            attrs.optJSONArray("altTitles")?.let { alts ->
                for (j in 0 until alts.length()) {
                    val o = alts.optJSONObject(j) ?: continue
                    for (k in o.keys()) titles.add(o.optString(k))
                }
            }
            var file: String? = null
            m.optJSONArray("relationships")?.let { rels ->
                for (j in 0 until rels.length()) {
                    val r = rels.optJSONObject(j) ?: continue
                    if (r.optString("type") == "cover_art") {
                        file = r.optJSONObject("attributes")?.optString("fileName", "")?.takeIf { it.isNotBlank() }
                    }
                }
            }
            val f = file ?: continue
            if (id.isEmpty()) continue
            val score = titles.maxOfOrNull { similarity(query, it) } ?: 0.0
            if (score > bestScore) {
                bestScore = score
                bestUrl = "https://uploads.mangadex.org/covers/$id/$f.512.jpg"
            }
        }
        val needed = if (strict) 0.6 else 0.3
        if (bestUrl == null || bestScore < needed) return null
        return download(bestUrl)
    }

    private fun get(url: String): JSONObject? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) return@runCatching null
            JSONObject(c.inputStream.use { String(it.readBytes()) })
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun findAniList(query: String, strict: Boolean): ByteArray? {

        val body = JSONObject()
            .put("query", QUERY)
            .put("variables", JSONObject().put("s", query))
            .toString()
        val json = post(body) ?: return null
        val media = json.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return null

        var bestScore = 0.0
        var bestUrl: String? = null
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            val titles = ArrayList<String>()
            m.optJSONObject("title")?.let { t ->
                for (k in listOf("romaji", "english", "native")) {
                    t.optString(k, "").takeIf { it.isNotBlank() && it != "null" }?.let { titles.add(it) }
                }
            }
            m.optJSONArray("synonyms")?.let { a ->
                for (j in 0 until a.length()) titles.add(a.optString(j))
            }
            val score = titles.maxOfOrNull { similarity(query, it) } ?: 0.0
            val img = m.optJSONObject("coverImage")
            val url = img?.optString("extraLarge", "")?.takeIf { it.startsWith("http") }
                ?: img?.optString("large", "")?.takeIf { it.startsWith("http") }
            if (url != null && score > bestScore) {
                bestScore = score
                bestUrl = url
            }
        }
        val needed = if (strict) 0.6 else 0.3
        if (bestUrl == null || bestScore < needed) return null
        return download(bestUrl)
    }

    private fun post(body: String): JSONObject? = runCatching {
        val c = URL(API).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) return@runCatching null
            JSONObject(c.inputStream.use { String(it.readBytes()) })
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun download(url: String): ByteArray? = runCatching {
        if (!url.startsWith("https://")) return@runCatching null
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode != 200) return@runCatching null
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            c.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_IMAGE) return@runCatching null
                }
            }
            out.toByteArray().takeIf { it.size > 1000 }
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}
