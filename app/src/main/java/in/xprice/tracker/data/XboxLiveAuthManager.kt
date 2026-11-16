package `in`.xprice.tracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class XboxSyncedTitle(
    val titleId: String,
    val name: String,
    val productId: String,
    val minutesPlayed: Int,
    val currentGamerscore: Int,
    val maxGamerscore: Int,
    val achievementsUnlocked: Int
)

data class XboxAuthSyncResult(
    val gamertag: String,
    val xuid: String,
    val gamerscore: Int,
    val titles: List<XboxSyncedTitle>
)

class XboxLiveAuthManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    companion object {
        // Official Xbox Live OAuth 2.0 Implicit Token URL (Used by Xbox App / OpenXBL clients)
        const val MS_OAUTH_AUTHORIZE_URL =
            "https://login.live.com/oauth20_authorize.srf" +
            "?client_id=0000000048093EE3" +
            "&redirect_uri=https://login.live.com/oauth20_desktop.srf" +
            "&response_type=token" +
            "&display=touch" +
            "&scope=service::user.auth.xboxlive.com::MBI_SSL" +
            "&locale=en"
    }

    // Exchanges Microsoft OAuth access_token -> XBL User Token -> XSTS Token -> TitleHub Owned Games & Playtime
    suspend fun authenticateAndFetchLibrary(msAccessToken: String): Result<XboxAuthSyncResult> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Exchange MS Access Token for XBL User Token
            val xblPayload = JSONObject().apply {
                put("Properties", JSONObject().apply {
                    put("AuthMethod", "RPS")
                    put("SiteName", "user.auth.xboxlive.com")
                    put("RpsTicket", if (msAccessToken.startsWith("t=") || msAccessToken.startsWith("d=")) msAccessToken else "t=$msAccessToken")
                })
                put("RelyingParty", "http://auth.xboxlive.com")
                put("TokenType", "JWT")
            }

            val xblReq = Request.Builder()
                .url("https://user.auth.xboxlive.com/user/authenticate")
                .post(xblPayload.toString().toRequestBody(jsonMedia))
                .header("Accept", "application/json")
                .header("x-xbl-contract-version", "1")
                .build()

            val xblResp = client.newCall(xblReq).execute()
            val xblBody = xblResp.body?.string() ?: return@withContext Result.failure(Exception("Empty XBL response"))
            if (!xblResp.isSuccessful) {
                return@withContext Result.failure(Exception("XBL Auth HTTP ${xblResp.code}"))
            }

            val xblJson = JSONObject(xblBody)
            val userToken = xblJson.getString("Token")
            val uhs = xblJson.getJSONObject("DisplayClaims")
                .getJSONArray("xui")
                .getJSONObject(0)
                .getString("uhs")

            // Step 2: Exchange XBL User Token for XSTS Token
            val xstsPayload = JSONObject().apply {
                put("Properties", JSONObject().apply {
                    put("SandboxId", "RETAIL")
                    put("UserTokens", JSONArray().put(userToken))
                })
                put("RelyingParty", "http://xboxlive.com")
                put("TokenType", "JWT")
            }

            val xstsReq = Request.Builder()
                .url("https://xsts.auth.xboxlive.com/xsts/authorize")
                .post(xstsPayload.toString().toRequestBody(jsonMedia))
                .header("Accept", "application/json")
                .header("x-xbl-contract-version", "1")
                .build()

            val xstsResp = client.newCall(xstsReq).execute()
            val xstsBody = xstsResp.body?.string() ?: return@withContext Result.failure(Exception("Empty XSTS response"))
            if (!xstsResp.isSuccessful) {
                return@withContext Result.failure(Exception("XSTS Auth HTTP ${xstsResp.code}"))
            }

            val xstsJson = JSONObject(xstsBody)
            val xstsToken = xstsJson.getString("Token")
            val xuiObj = xstsJson.getJSONObject("DisplayClaims")
                .getJSONArray("xui")
                .getJSONObject(0)
            val xuid = xuiObj.optString("xid", "")
            val gamertag = xuiObj.optString("gtg", "XboxPlayerIN")
            val authHeader = "XBL3.0 x=$uhs;$xstsToken"

            // Step 3: Fetch User's Real Title History, ProductIds, Achievements & Playtime from TitleHub
            val titleHubUrl = "https://titlehub.xboxlive.com/users/xuid($xuid)/titles/titlehistory/decoration/detail,achievement,stats"
            val titleReq = Request.Builder()
                .url(titleHubUrl)
                .get()
                .header("Authorization", authHeader)
                .header("x-xbl-contract-version", "2")
                .header("Accept-Language", "en-IN")
                .build()

            val titleResp = client.newCall(titleReq).execute()
            val titleBody = titleResp.body?.string() ?: "{}"
            val parsedTitles = mutableListOf<XboxSyncedTitle>()
            var totalGamerscore = 0

            if (titleResp.isSuccessful) {
                val root = JSONObject(titleBody)
                val titlesArr = root.optJSONArray("titles")
                if (titlesArr != null) {
                    for (i in 0 until titlesArr.length()) {
                        val tObj = titlesArr.getJSONObject(i)
                        val type = tObj.optString("type", "Game")
                        if (!type.equals("Game", ignoreCase = true)) continue

                        val name = tObj.optString("name", "").takeIf { it.isNotBlank() } ?: continue
                        val titleId = tObj.optString("titleId", "")
                        val pfn = tObj.optString("pfn", "")
                        val productId = tObj.optString("modernTitleId", "")
                            .ifBlank { tObj.optString("productId", "") }
                            .ifBlank { pfn }

                        val achObj = tObj.optJSONObject("achievement")
                        val currGs = achObj?.optInt("currentGamerscore", 0) ?: 0
                        val maxGs = achObj?.optInt("totalGamerscore", 1000) ?: 1000
                        val currAch = achObj?.optInt("currentAchievements", 0) ?: 0
                        totalGamerscore += currGs

                        // Parse MinutesPlayed if returned in stats decoration
                        var minutes = 0
                        val statsArr = tObj.optJSONArray("stats")
                        if (statsArr != null) {
                            for (s in 0 until statsArr.length()) {
                                val st = statsArr.getJSONObject(s)
                                if (st.optString("name").equals("MinutesPlayed", ignoreCase = true)) {
                                    minutes = st.optInt("value", 0)
                                }
                            }
                        }

                        parsedTitles.add(
                            XboxSyncedTitle(
                                titleId = titleId,
                                name = name,
                                productId = productId,
                                minutesPlayed = minutes,
                                currentGamerscore = currGs,
                                maxGamerscore = maxGs.coerceAtLeast(100),
                                achievementsUnlocked = currAch
                            )
                        )
                    }
                }
            }

            Result.success(
                XboxAuthSyncResult(
                    gamertag = gamertag,
                    xuid = xuid,
                    gamerscore = totalGamerscore,
                    titles = parsedTitles
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
