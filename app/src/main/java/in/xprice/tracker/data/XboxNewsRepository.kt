package `in`.xprice.tracker.data

import android.content.Context
import android.text.Html
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class XboxWireArticle(
    val id: String,
    val title: String,
    val category: String,
    val publishedDate: String,
    val summary: String,
    val fullBody: String,
    val imageUrl: String,
    val articleUrl: String,
    val extractedGameQuery: String
)

class XboxNewsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("rupeevault_x_news_cache", Context.MODE_PRIVATE)

    companion object {
        private const val XBOX_WIRE_RSS_URL = "https://news.xbox.com/en-us/feed/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        private val IMG_SRC_REGEX = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

        fun extractGameSearchKeyword(headline: String): String {
            val cleaned = headline
                .replace(Regex("^(Available Now|Coming Soon|Next Week on Xbox|Free Play Days|Announcing|Inside Xbox|Review)[:\\-–]\\s*", RegexOption.IGNORE_CASE), "")
                .substringBefore(":")
                .substringBefore("–")
                .substringBefore(" - ")
                .trim()
            return cleaned.split(" ").take(4).joinToString(" ")
        }
    }

    fun getInstantCachedNews(): List<XboxWireArticle> {
        val raw = prefs.getString("cached_articles", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val t = o.getString("title")
                XboxWireArticle(
                    id = o.getString("id"),
                    title = t,
                    category = o.optString("category", "Xbox Wire"),
                    publishedDate = o.optString("pubDate", "Recent"),
                    summary = o.optString("summary", ""),
                    fullBody = o.optString("fullBody", ""),
                    imageUrl = o.optString("imageUrl", ""),
                    articleUrl = o.optString("articleUrl", "https://news.xbox.com/en-us/"),
                    extractedGameQuery = extractGameSearchKeyword(t)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveArticlesCache(articles: List<XboxWireArticle>) {
        val arr = JSONArray()
        articles.take(30).forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("title", a.title)
                put("category", a.category)
                put("pubDate", a.publishedDate)
                put("summary", a.summary)
                put("fullBody", a.fullBody)
                put("imageUrl", a.imageUrl)
                put("articleUrl", a.articleUrl)
            })
        }
        prefs.edit().putString("cached_articles", arr.toString()).apply()
    }

    suspend fun fetchLiveXboxWireNews(): Result<List<XboxWireArticle>> = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(XBOX_WIRE_RSS_URL).openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 9000
                readTimeout = 9000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
            }

            if (conn.responseCode in 200..299) {
                val xmlContent = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = parseXboxWireRss(xmlContent)
                if (parsed.isNotEmpty()) {
                    saveArticlesCache(parsed)
                    return@withContext Result.success(parsed)
                }
            }
            Result.success(getInstantCachedNews())
        } catch (e: Exception) {
            Result.success(getInstantCachedNews())
        }
    }

    private fun stripHtml(rawHtml: String): String {
        if (rawHtml.isBlank()) return ""
        val plain = Html.fromHtml(rawHtml, Html.FROM_HTML_MODE_COMPACT).toString()
        return plain.replace(Regex("\\s+"), " ").trim()
    }

    private fun parseXboxWireRss(xmlStr: String): List<XboxWireArticle> {
        val articles = mutableListOf<XboxWireArticle>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xmlStr))

        var insideItem = false
        var title = ""
        var link = ""
        var pubDate = ""
        var description = ""
        var encodedContent = ""
        var category = ""
        var imageUrl = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name ?: ""
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (tagName.equals("item", ignoreCase = true)) {
                        insideItem = true
                        title = ""; link = ""; pubDate = ""; description = ""
                        encodedContent = ""; category = ""; imageUrl = ""
                    } else if (insideItem) {
                        when {
                            tagName.equals("title", ignoreCase = true) -> title = parser.nextText().trim()
                            tagName.equals("link", ignoreCase = true) -> link = parser.nextText().trim()
                            tagName.equals("pubDate", ignoreCase = true) -> {
                                val rawDate = parser.nextText().trim()
                                pubDate = if (rawDate.length >= 16) rawDate.substring(0, 16) else rawDate
                            }
                            tagName.equals("category", ignoreCase = true) && category.isBlank() -> {
                                category = parser.nextText().trim()
                            }
                            tagName.equals("description", ignoreCase = true) -> description = parser.nextText()
                            tagName.equals("content:encoded", ignoreCase = true) -> encodedContent = parser.nextText()
                            tagName.equals("media:content", ignoreCase = true) ||
                            tagName.equals("media:thumbnail", ignoreCase = true) ||
                            tagName.equals("enclosure", ignoreCase = true) -> {
                                val urlAttr = parser.getAttributeValue(null, "url") ?: ""
                                if (urlAttr.isNotBlank() && imageUrl.isBlank()) imageUrl = urlAttr
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (tagName.equals("item", ignoreCase = true) && insideItem) {
                        insideItem = false
                        if (title.isNotBlank()) {
                            if (imageUrl.isBlank()) {
        private val IMG_SRC_REGEX = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                                imageUrl = imgMatch?.groupValues?.getOrNull(1) ?: ""
                            }
                            val cleanTitle = stripHtml(title)
                            val cleanSummary = stripHtml(description).take(220)
                            val cleanFullBody = stripHtml(encodedContent.ifBlank { description }).take(1800)
                            val finalCategory = category.ifBlank {
                                when {
                                    cleanTitle.contains("Game Pass", ignoreCase = true) -> "Xbox Game Pass"
                                    cleanTitle.contains("Free Play Days", ignoreCase = true) -> "Free Play Days"
                                    cleanTitle.contains("Next Week on Xbox", ignoreCase = true) -> "New Releases"
                                    else -> "Xbox Wire"
                                }
                            }
                            articles.add(
                                XboxWireArticle(
                                    id = link.ifBlank { cleanTitle.hashCode().toString() },
                                    title = cleanTitle,
                                    category = finalCategory,
                                    publishedDate = pubDate,
                                    summary = cleanSummary,
                                    fullBody = cleanFullBody.ifBlank { cleanSummary },
                                    imageUrl = imageUrl,
                                    articleUrl = link.ifBlank { "https://news.xbox.com/en-us/" },
                                    extractedGameQuery = extractGameSearchKeyword(cleanTitle)
                                )
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return articles
    }
}
