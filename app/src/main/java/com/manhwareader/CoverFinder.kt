package com.manhwareader

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looks a manhwa up by name on AniList, then MangaDex (both free manga databases), and downloads its cover.
 * Only the cleaned-up name is sent. Runs on a background thread. Returns null if nothing fits.
 * [log] collects a plain-words reason for every step, so a failure can be understood.
 */
object CoverFinder {
    private const val API = "https://graphql.anilist.co"
    private const val UA = "NuxReader/2.0 (Android manhwa reader)"
    private const val MAX_IMAGE = 8L * 1024 * 1024

    private val QUERY = """
        query (${'$'}s: String) {
          Page(perPage: 8) {
            media(search: ${'$'}s, type: MANGA) {
              title { romaji english native }
              synonyms
              coverImage { extraLarge large }
            }
          }
        }
    """.trimIndent()

    private val NOISE = Regex(
        "\\b(chapters?|chap|episodes?|pdf|manhwa|manga|manhua|webtoon|hq|hd|complete|completed|" +
            "season\\s*\\d+|vol(ume)?\\s*\\d+|raw|eng|english|official)\\b"
    )

    /** "Solo_Leveling (Season 1) [HQ]" -> "solo leveling" */
    fun clean(name: String): String {
        var s = name.lowercase()
        s = s.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}"), " ")
        s = s.replace(Regex("['’`]"), "")
        s = s.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        s = s.replace(NOISE, " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun words(s: String): Set<String> =
        clean(s).split(' ').filter { it.isNotEmpty() }.toSet()

    /** 0..1: how well two names match. All words of the shorter name inside the longer one counts as a good match. */
    private fun similarity(a: String, b: String): Double {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val common = x.intersect(y).size.toDouble()
        val jaccard = common / x.union(y).size
        val contained = common == minOf(x.size, y.size).toDouble() && minOf(x.size, y.size) >= 2
        return if (contained) maxOf(jaccard, 0.7) else jaccard
    }

    /** Cover as image bytes, or null. [strict] skips doubtful matches (used by the automatic search). */
    fun find(name: String, strict: Boolean, log: MutableList<String>): ByteArray? {
        val query = clean(name)
        log.add("Searched for: \"$query\"")
        if (query.length < 2) {
            log.add("That name is too short to search.")
            return null
        }
        val needed = if (strict) 0.6 else 0.3
        return fromAniList(query, needed, log) ?: fromMangaDex(query, needed, log)
    }

    private class Hit(val score: Double, val title: String, val url: String)

    private fun best(query: String, titles: List<String>, url: String, current: Hit?): Hit? {
        val t = titles.filter { it.isNotBlank() && it != "null" }
        val top = t.maxByOrNull { similarity(query, it) } ?: return current
        val score = similarity(query, top)
        return if (current == null || score > current.score) Hit(score, top, url) else current
    }

    private fun finish(source: String, hit: Hit?, needed: Double, log: MutableList<String>): ByteArray? {
        if (hit == null) {
            log.add("$source: no results.")
            return null
        }
        val pct = (hit.score * 100).toInt()
        if (hit.score < needed) {
            log.add("$source: closest was \"${hit.title}\" ($pct% match), too different.")
            return null
        }
        val bytes = download(hit.url, log, source)
        if (bytes != null) log.add("$source: used \"${hit.title}\".")
        return bytes
    }

    private fun fromAniList(query: String, needed: Double, log: MutableList<String>): ByteArray? {
        val body = JSONObject()
            .put("query", QUERY)
            .put("variables", JSONObject().put("s", query))
            .toString()
        val json = request(API, body, "AniList", log) ?: return null
        val media = json.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
        if (media == null) {
            log.add("AniList: unexpected answer.")
            return null
        }
        var hit: Hit? = null
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            val titles = ArrayList<String>()
            m.optJSONObject("title")?.let { t ->
                for (k in listOf("romaji", "english", "native")) titles.add(t.optString(k, ""))
            }
            m.optJSONArray("synonyms")?.let { a -> for (j in 0 until a.length()) titles.add(a.optString(j)) }
            val img = m.optJSONObject("coverImage")
            val url = listOf("extraLarge", "large")
                .map { img?.optString(it, "") ?: "" }
                .firstOrNull { it.startsWith("https://") } ?: continue
            hit = best(query, titles, url, hit)
        }
        return finish("AniList", hit, needed, log)
    }

    private fun fromMangaDex(query: String, needed: Double, log: MutableList<String>): ByteArray? {
        val url = "https://api.mangadex.org/manga?limit=10&order%5Brelevance%5D=desc" +
            "&includes%5B%5D=cover_art&title=" + URLEncoder.encode(query, "UTF-8")
        val json = request(url, null, "MangaDex", log) ?: return null
        val data = json.optJSONArray("data")
        if (data == null) {
            log.add("MangaDex: unexpected answer.")
            return null
        }
        var hit: Hit? = null
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id", "")
            val attrs = m.optJSONObject("attributes") ?: continue
            val titles = ArrayList<String>()
            attrs.optJSONObject("title")?.let { t -> for (k in t.keys()) titles.add(t.optString(k)) }
            attrs.optJSONArray("altTitles")?.let { alts ->
                for (j in 0 until alts.length()) {
                    val o = alts.optJSONObject(j) ?: continue
                    for (k in o.keys()) titles.add(o.optString(k))
                }
            }
            var file = ""
            m.optJSONArray("relationships")?.let { rels ->
                for (j in 0 until rels.length()) {
                    val r = rels.optJSONObject(j) ?: continue
                    if (r.optString("type") == "cover_art") {
                        file = r.optJSONObject("attributes")?.optString("fileName", "") ?: ""
                    }
                }
            }
            if (id.isEmpty() || file.isBlank()) continue
            hit = best(query, titles, "https://uploads.mangadex.org/covers/$id/$file.512.jpg", hit)
        }
        return finish("MangaDex", hit, needed, log)
    }

    /** POST when [body] is given, GET otherwise. Logs why it failed. */
    private fun request(url: String, body: String?, source: String, log: MutableList<String>): JSONObject? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            if (code != 200) {
                log.add("$source: the site answered with error $code.")
                null
            } else {
                JSONObject(c.inputStream.use { String(it.readBytes()) })
            }
        } catch (e: java.net.UnknownHostException) {
            log.add("$source: can't reach the site. Check your internet connection.")
            null
        } catch (e: java.net.SocketTimeoutException) {
            log.add("$source: the site took too long to answer.")
            null
        } catch (e: Exception) {
            log.add("$source: failed (${e.javaClass.simpleName}: ${e.message}).")
            null
        } finally {
            c?.disconnect()
        }
    }

    private fun download(url: String, log: MutableList<String>, source: String): ByteArray? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            if (code != 200) {
                log.add("$source: the picture couldn't be downloaded (error $code).")
                return null
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            c.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_IMAGE) {
                        log.add("$source: the picture is too big.")
                        return null
                    }
                }
            }
            if (out.size() < 1000) {
                log.add("$source: the picture was empty.")
                null
            } else out.toByteArray()
        } catch (e: Exception) {
            log.add("$source: the picture download failed (${e.javaClass.simpleName}).")
            null
        } finally {
            c?.disconnect()
        }
    }
}
