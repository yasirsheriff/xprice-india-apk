package `in`.xprice.tracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class XboxSyncedTitle(
    val titleId: String,
    val name: String,
    /** Microsoft Store product ID (e.g. 9NBLGGH4R315) or "" when the title has none. */
    val productId: String,
    val imageUrl: String,
    val minutesPlayed: Int,
    val currentGamerscore: Int,
    val maxGamerscore: Int,
    val progressPercent: Int,
    val lastPlayed: String
)

data class XboxAuthSyncResult(
    val gamertag: String,
    val xuid: String,
    val gamerscore: Int,
    val avatarUrl: String,
    val titles: List<XboxSyncedTitle>
)

class XboxAuthException(message: String) : Exception(message)

/**
 * Microsoft account -> Xbox Live sign-in using the public Xbox app client.
 *  1. login.live.com OAuth (in a WebView) returns an RPS access token.
 *  2. user.auth.xboxlive.com: RPS ticket -> XBL user token
 *  3. xsts.auth.xboxlive.com: user token -> XSTS token (+ gamertag / xuid)
 *  4. profile, titlehub and userstats APIs with "XBL3.0 x=uhs;xsts".
 */
class XboxLiveAuthManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    companion object {
        const val REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"
        const val MS_OAUTH_AUTHORIZE_URL =
            "https://login.live.com/oauth20_authorize.srf" +
                "?client_id=0000000048093EE3" +
                "&redirect_uri=$REDIRECT_URI" +
                "&response_type=token" +
                "&display=touch" +
                "&scope=service::user.auth.xboxlive.com::MBI_SSL" +
                "&locale=en"

        private val STORE_ID_REGEX = Regex("^[9BC][A-Z0-9]{11}$")

        /**
         * Extracts the access token (or an error) from the OAuth redirect URL.
         * The token comes back in the URL fragment: ...oauth20_desktop.srf#access_token=...&token_type=bearer...
         */
        fun parseRedirect(url: String): Pair<String?, String?> {
            if (!url.startsWith(REDIRECT_URI)) return null to null
            val params = (url.substringAfter('#', "") + "&" + url.substringAfter('?', "").substringBefore('#'))
                .split('&')
                .mapNotNull { kv ->
                    val idx = kv.indexOf('=')
                    if (idx <= 0) null else kv.substring(0, idx) to java.net.URLDecoder.decode(kv.substring(idx + 1), "UTF-8")
                }
                .toMap()
            val token = params["access_token"]?.takeIf { it.isNotBlank() }
            val error = params["error_description"] ?: params["error"]
            return token to error
        }
    }

    suspend fun authenticateAndFetchLibrary(msAccessToken: String): Result<XboxAuthSyncResult> = withContext(Dispatchers.IO) {
        try {
            val (userToken, _) = requestUserToken(msAccessToken)
            val xsts = requestXsts(userToken)
            val xui = xsts.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0)
            val uhs = xui.getString("uhs")
            val xuid = xui.optString("xid", "")
            if (xuid.isBlank()) throw XboxAuthException("Xbox Live didn't return a player ID. Try signing in again.")
            val auth = "XBL3.0 x=$uhs;${xsts.getString("Token")}"

            val profile = fetchProfile(auth, xuid)
            val gamertag = profile["Gamertag"] ?: xui.optString("gtg", "Xbox player")
            val gamerscore = profile["Gamerscore"]?.toIntOrNull() ?: 0
            val avatar = profile["GameDisplayPicRaw"].orEmpty()

            val titles = fetchTitleHistory(auth, xuid)
            val minutes = fetchMinutesPlayed(auth, xuid, titles.map { it.titleId })
            val withMinutes = titles.map { it.copy(minutesPlayed = minutes[it.titleId] ?: 0) }

            Result.success(XboxAuthSyncResult(gamertag, xuid, gamerscore, avatar, withMinutes))
        } catch (e: XboxAuthException) {
            Result.failure(e)
        } catch (e: java.net.UnknownHostException) {
            Result.failure(XboxAuthException("No internet connection. Connect and try again."))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(XboxAuthException("Xbox Live took too long to respond. Try again in a moment."))
        } catch (e: Exception) {
            Result.failure(XboxAuthException("Xbox sign-in failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun postJson(url: String, body: JSONObject, contract: String, auth: String? = null): Response {
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Accept", "application/json")
            .header("x-xbl-contract-version", contract)
            .apply { if (auth != null) header("Authorization", auth) }
            .build()
        return client.newCall(req).execute()
    }

    private fun getJson(url: String, contract: String, auth: String): Response {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Accept-Language", "en-IN")
            .header("x-xbl-contract-version", contract)
            .header("Authorization", auth)
            .build()
        return client.newCall(req).execute()
    }

    /**
     * The RPS ticket prefix depends on how the token was issued ("t=" for MBI_SSL live tokens,
     * "d=" for MSAL tokens, none for compact tickets). Try each so one bad guess isn't fatal.
     */
    private fun requestUserToken(accessToken: String): Pair<String, String> {
        val candidates = if (accessToken.startsWith("t=") || accessToken.startsWith("d=")) {
            listOf(accessToken)
        } else {
            listOf("t=$accessToken", accessToken, "d=$accessToken")
        }
        var lastCode = 0
        for (ticket in candidates) {
            val payload = JSONObject()
                .put("Properties", JSONObject()
                    .put("AuthMethod", "RPS")
                    .put("SiteName", "user.auth.xboxlive.com")
                    .put("RpsTicket", ticket))
                .put("RelyingParty", "http://auth.xboxlive.com")
                .put("TokenType", "JWT")
            postJson("https://user.auth.xboxlive.com/user/authenticate", payload, "1").use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val json = JSONObject(body)
                    val uhs = json.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs")
                    return json.getString("Token") to uhs
                }
                lastCode = resp.code
            }
        }
        throw XboxAuthException("Microsoft rejected the sign-in (HTTP $lastCode). Close this and sign in again.")
    }

    private fun requestXsts(userToken: String): JSONObject {
        val payload = JSONObject()
            .put("Properties", JSONObject()
                .put("SandboxId", "RETAIL")
                .put("UserTokens", JSONArray().put(userToken)))
            .put("RelyingParty", "http://xboxlive.com")
            .put("TokenType", "JWT")
        postJson("https://xsts.auth.xboxlive.com/xsts/authorize", payload, "1").use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) return JSONObject(body)
            val xerr = try { JSONObject(body).optLong("XErr", 0L) } catch (_: Exception) { 0L }
            throw XboxAuthException(xstsErrorMessage(xerr, resp.code))
        }
    }

    private fun xstsErrorMessage(xerr: Long, httpCode: Int): String = when (xerr) {
        2148916233L -> "This Microsoft account has no Xbox profile yet. Sign in once at xbox.com to create one, then try again."
        2148916235L -> "Xbox Live isn't available in this account's country/region."
        2148916236L, 2148916237L -> "This account needs adult verification on xbox.com before it can sign in."
        2148916238L -> "This is a child account. An adult must add it to a Microsoft family group first."
        2148916227L -> "This account is banned from Xbox Live."
        else -> "Xbox Live authorization failed (HTTP $httpCode${if (xerr != 0L) ", XErr $xerr" else ""})."
    }

    /** Returns settings like Gamertag, Gamerscore, GameDisplayPicRaw. Failure is non-fatal. */
    private fun fetchProfile(auth: String, xuid: String): Map<String, String> = try {
        getJson(
            "https://profile.xboxlive.com/users/xuid($xuid)/profile/settings?settings=Gamertag,Gamerscore,GameDisplayPicRaw",
            "3", auth
        ).use { resp ->
            if (!resp.isSuccessful) return emptyMap()
            val settings = JSONObject(resp.body?.string().orEmpty())
                .getJSONArray("profileUsers").getJSONObject(0).getJSONArray("settings")
            (0 until settings.length()).associate { i ->
                val s = settings.getJSONObject(i)
                s.getString("id") to s.optString("value")
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun fetchTitleHistory(auth: String, xuid: String): List<XboxSyncedTitle> {
        val url = "https://titlehub.xboxlive.com/users/xuid($xuid)/titles/titlehistory/decoration/achievement,detail,image"
        getJson(url, "2", auth).use { resp ->
            if (!resp.isSuccessful) {
                throw XboxAuthException("Signed in, but couldn't load your game history (HTTP ${resp.code}). Check your Xbox privacy settings allow game history.")
            }
            val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("titles") ?: return emptyList()
            val out = mutableListOf<XboxSyncedTitle>()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                if (!t.optString("type", "Game").equals("Game", true)) continue
                val name = t.optString("name").takeIf { it.isNotBlank() } ?: continue

                val ach = t.optJSONObject("achievement")
                out.add(
                    XboxSyncedTitle(
                        titleId = t.optString("titleId"),
                        name = name,
                        productId = extractProductId(t),
                        imageUrl = t.optString("displayImage").replaceFirst("http://", "https://"),
                        minutesPlayed = 0,
                        currentGamerscore = ach?.optInt("currentGamerscore", 0) ?: 0,
                        maxGamerscore = ach?.optInt("totalGamerscore", 0) ?: 0,
                        progressPercent = ach?.optDouble("progressPercentage", 0.0)?.toInt() ?: 0,
                        lastPlayed = t.optJSONObject("titleHistory")?.optString("lastTimePlayed").orEmpty()
                    )
                )
            }
            return out
        }
    }

    /** Store product IDs live in detail.availabilities[].ProductId (not modernTitleId, which is a hex title ID). */
    private fun extractProductId(title: JSONObject): String {
        title.optString("productId").takeIf { STORE_ID_REGEX.matches(it) }?.let { return it }
        val avails = title.optJSONObject("detail")?.optJSONArray("availabilities") ?: return ""
        for (i in 0 until avails.length()) {
            val pid = avails.optJSONObject(i)?.optString("ProductId").orEmpty()
            if (STORE_ID_REGEX.matches(pid)) return pid
        }
        return ""
    }

    /** MinutesPlayed per titleId via the userstats batch API. Failure is non-fatal. */
    private fun fetchMinutesPlayed(auth: String, xuid: String, titleIds: List<String>): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        titleIds.filter { it.isNotBlank() }.distinct().chunked(50).forEach { chunk ->
            try {
                val stats = JSONArray()
                chunk.forEach { stats.put(JSONObject().put("name", "MinutesPlayed").put("titleid", it)) }
                val body = JSONObject()
                    .put("arrangebyfield", "xuid")
                    .put("xuids", JSONArray().put(xuid))
                    .put("groups", JSONArray())
                    .put("stats", stats)
                postJson("https://userstats.xboxlive.com/batch", body, "2", auth).use { resp ->
                    if (!resp.isSuccessful) return@use
                    val lists = JSONObject(resp.body?.string().orEmpty()).optJSONArray("statlistscollection") ?: return@use
                    for (l in 0 until lists.length()) {
                        val items = lists.getJSONObject(l).optJSONArray("stats") ?: continue
                        for (s in 0 until items.length()) {
                            val st = items.getJSONObject(s)
                            val v = st.optString("value").toIntOrNull() ?: continue
                            result[st.optString("titleid")] = v
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return result
    }
}
