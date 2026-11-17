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

/** One sign-in attempt: client ID + PKCE verifier + anti-forgery state. */
class OAuthSession internal constructor(val clientId: String, val verifier: String, val challenge: String, val state: String)

/**
 * Microsoft account -> Xbox Live sign-in, using this app's own Microsoft app registration
 * (the supported route; reusing the official Xbox app's client ID gets HTTP 403).
 *  1. login.microsoftonline.com (in a WebView) -> authorization code (PKCE)
 *  2. code -> Microsoft access token
 *  3. user.auth.xboxlive.com: "d=<token>" -> XBL user token
 *  4. xsts.auth.xboxlive.com: user token -> XSTS token (+ gamertag / xuid)
 *  5. profile, titlehub and userstats APIs with "XBL3.0 x=uhs;xsts".
 */
class XboxLiveAuthManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    companion object {
        /** Built-in default from gradle.properties (XBOX_CLIENT_ID). Can be overridden in the app. */
        val BUILT_IN_CLIENT_ID: String = `in`.xprice.tracker.BuildConfig.XBOX_CLIENT_ID.trim()
        private val GUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun isValidClientId(id: String): Boolean = GUID_REGEX.matches(id.trim())

        const val REDIRECT_URI = "https://login.microsoftonline.com/common/oauth2/nativeclient"
        private const val AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
        private const val SCOPES = "XboxLive.signin offline_access"

        private val STORE_ID_REGEX = Regex("^[9BC][A-Z0-9]{11}$")

        fun newSession(clientId: String): OAuthSession {
            val rnd = java.security.SecureRandom()
            fun b64(bytes: ByteArray) = android.util.Base64.encodeToString(
                bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            val verifier = b64(ByteArray(48).also { rnd.nextBytes(it) })
            val challenge = b64(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            val state = b64(ByteArray(16).also { rnd.nextBytes(it) })
            return OAuthSession(clientId.trim(), verifier, challenge, state)
        }

        fun authorizeUrl(session: OAuthSession): String {
            fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
            return "$AUTHORITY/authorize?client_id=${enc(session.clientId)}" +
                "&response_type=code" +
                "&redirect_uri=${enc(REDIRECT_URI)}" +
                "&response_mode=query" +
                "&scope=${enc(SCOPES)}" +
                "&code_challenge=${session.challenge}&code_challenge_method=S256" +
                "&state=${enc(session.state)}" +
                "&prompt=select_account"
        }

        /** Returns (authorization code, error message) when [url] is our redirect; (null, null) otherwise. */
        fun parseRedirect(url: String, session: OAuthSession): Pair<String?, String?> {
            if (!url.startsWith(REDIRECT_URI)) return null to null
            val params = (url.substringAfter('?', "").substringBefore('#') + "&" + url.substringAfter('#', ""))
                .split('&')
                .mapNotNull { kv ->
                    val idx = kv.indexOf('=')
                    if (idx <= 0) null else kv.substring(0, idx) to java.net.URLDecoder.decode(kv.substring(idx + 1), "UTF-8")
                }
                .toMap()
            val error = params["error"]
            if (error != null) {
                return null to when (error) {
                    "access_denied" -> "Sign-in was cancelled."
                    else -> "Microsoft sign-in error: ${params["error_description"]?.lineSequence()?.firstOrNull() ?: error}"
                }
            }
            if (params["state"] != session.state) return null to "Sign-in response didn't match. Please try again."
            val code = params["code"]?.takeIf { it.isNotBlank() } ?: return null to "Microsoft didn't return a sign-in code."
            return code to null
        }
    }

    suspend fun authenticateWithCode(code: String, session: OAuthSession): Result<XboxAuthSyncResult> = withContext(Dispatchers.IO) {
        try {
            val msToken = exchangeCode(code, session)
            fetchLibrary(msToken)
        } catch (e: XboxAuthException) {
            Result.failure(e)
        } catch (e: java.net.UnknownHostException) {
            Result.failure(XboxAuthException("No internet connection. Connect and try again."))
        } catch (e: Exception) {
            Result.failure(XboxAuthException("Microsoft sign-in failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun exchangeCode(code: String, session: OAuthSession): String {
        val form = okhttp3.FormBody.Builder()
            .add("client_id", session.clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", session.verifier)
            .add("scope", SCOPES)
            .build()
        val req = Request.Builder().url("$AUTHORITY/token").post(form).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            if (resp.isSuccessful && json.has("access_token")) return json.getString("access_token")
            val desc = json.optString("error_description").lineSequence().firstOrNull().orEmpty()
            throw XboxAuthException(
                when {
                    desc.contains("AADSTS700016") -> "The Xbox client ID isn't valid or isn't enabled for personal Microsoft accounts. Check the Azure app registration."
                    desc.contains("AADSTS50011") -> "Redirect URI mismatch. Add $REDIRECT_URI as a Mobile/desktop redirect URI in the Azure app registration."
                    desc.contains("AADSTS7000218") -> "Enable \"Allow public client flows\" in the Azure app registration (Authentication tab)."
                    else -> "Microsoft token exchange failed (HTTP ${resp.code}${if (desc.isNotBlank()) ": $desc" else ""})."
                }
            )
        }
    }

    private suspend fun fetchLibrary(msAccessToken: String): Result<XboxAuthSyncResult> = withContext(Dispatchers.IO) {
        try {
            val userToken = requestUserToken(msAccessToken)
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

    /** Microsoft identity platform tokens use the "d=" RPS ticket prefix. */
    private fun requestUserToken(accessToken: String): String {
        val payload = JSONObject()
            .put("Properties", JSONObject()
                .put("AuthMethod", "RPS")
                .put("SiteName", "user.auth.xboxlive.com")
                .put("RpsTicket", "d=$accessToken"))
            .put("RelyingParty", "http://auth.xboxlive.com")
            .put("TokenType", "JWT")
        postJson("https://user.auth.xboxlive.com/user/authenticate", payload, "1").use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful && body.isNotBlank()) return JSONObject(body).getString("Token")
            val detail = listOfNotNull(
                resp.header("WWW-Authenticate")?.take(120),
                body.take(160).takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            throw XboxAuthException(
                "Xbox Live rejected the Microsoft token (HTTP ${resp.code})." +
                    (if (detail.isNotBlank()) " Details: $detail" else "") +
                    " Make sure the Azure app uses \"Personal Microsoft accounts\" and the XboxLive.signin scope."
            )
        }
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
