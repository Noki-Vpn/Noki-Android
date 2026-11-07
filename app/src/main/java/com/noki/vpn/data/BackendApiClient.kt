package com.noki.vpn.data

import com.noki.vpn.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal fun defaultBackendControlPlaneClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .writeTimeout(8, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .build()

internal fun backendStreamingClient(controlPlaneClient: OkHttpClient): OkHttpClient =
    controlPlaneClient.newBuilder()
        .callTimeout(30, TimeUnit.MINUTES)
        .build()

class BackendApiClient(
    private val client: OkHttpClient = defaultBackendControlPlaneClient(),
    baseUrl: String = NokiBackendConfig.apiBaseUrl,
    streamingClient: OkHttpClient = backendStreamingClient(client),
    private val challengeSigner: (String) -> String = DeviceIdentity::signChallenge,
) : VpnSessionApi, TemporaryVpnApi, AuthFlowApi, TelegramAuthApi, GoogleAuthApi, TelegramOidcApi,
    AuthRefreshApi, PasswordRecoveryApi,
    AccountApi, AccountSecurityApi, DeviceActionApi,
    BackendBootstrapLoader {
    private val jsonApi = BackendJsonApi(client, baseUrl, streamingClient)
    private val baseUrl = jsonApi.baseUrl
    private val contentClient = BackendContentClient(jsonApi)
    private val authClient = BackendAuthClient(jsonApi)

    suspend fun supportChat(token: String, before: Long? = null): BackendSupportChat =
        jsonApi.get("/app/support/chat" + (before?.let { "?before=$it" } ?: ""), token).toBackendSupportChat()

    suspend fun sendSupportMessage(token: String, content: String, clientMessageId: String): BackendSupportMessage =
        jsonApi.post(
            path = "/app/support/chat/messages",
            payload = JSONObject().put("content", content).put("client_message_id", clientMessageId),
            token = token,
        ).toBackendSupportMessage()

    suspend fun sendSupportAttachments(token: String, content: String, clientMessageId: String, files: List<SupportAttachmentDraft>): BackendSupportMessage =
        contentClient.uploadSupportAttachments(token, content, clientMessageId, files)

    suspend fun downloadSupportAttachment(token: String, attachment: BackendSupportAttachment, destination: File): File =
        contentClient.downloadSupportAttachment(token, attachment, destination)

    internal fun onNetwork(network: android.net.Network): BackendApiClient = BackendApiClient(
        client = client.newBuilder().socketFactory(network.socketFactory)
            .dns { host -> network.getAllByName(host).toList() }.build(),
        baseUrl = baseUrl,
        challengeSigner = challengeSigner,
    )

    suspend fun paymentConfig(): BackendPaymentConfig = withContext(Dispatchers.IO) {
        val response = execute(Request.Builder().url(jsonApi.apiUrl("/payments/config")).get().build())
        val methods = response.getJSONArray("methods")
        BackendPaymentConfig(
            configured = response.getBoolean("configured"),
            checkoutEnabled = response.getBoolean("checkout_enabled"),
            methods = (0 until methods.length()).map { index ->
                val method = methods.getJSONObject(index)
                BackendPaymentMethod(
                    id = if (method.isNull("id")) null else method.getInt("id"),
                    code = method.getString("code"),
                    label = method.getString("label"),
                    enabled = method.optBoolean("enabled", false),
                )
            },
        )
    }

    suspend fun createPayment(
        token: String,
        planCode: String,
        paymentMethod: Int?,
        deviceId: String,
        deviceKey: String,
        promoCode: String? = null,
    ): BackendPayment = postJson(
        path = "/payments/create",
        payload = JSONObject().put("plan_code", planCode).put("payment_method", paymentMethod ?: JSONObject.NULL).put("promo_code", promoCode ?: JSONObject.NULL),
        token = token,
        currentDeviceId = deviceId,
        currentDeviceKey = deviceKey,
    ).toBackendPayment()

    suspend fun promo(token: String, code: String, planCode: String?, redeem: Boolean, deviceId: String, deviceKey: String): BackendPromo {
        val json = postJson(path = if (redeem) "/promocodes/redeem" else "/promocodes/quote",
            payload = JSONObject().put("code", code).put("plan_code", planCode ?: JSONObject.NULL),
            token = token, currentDeviceId = deviceId, currentDeviceKey = deviceKey)
        return BackendPromo(json.getString("kind"), json.getInt("value"), json.optBackendString("code"),
            if (json.isNull("amount_rub")) null else json.getInt("amount_rub"))
    }

    suspend fun payments(token: String, deviceId: String, deviceKey: String): List<BackendPayment> = withContext(Dispatchers.IO) {
        val response = executeArray(
            Request.Builder().url(jsonApi.apiUrl("/payments"))
                .header("Authorization", "Bearer $token")
                .currentDeviceHeaders(deviceId, deviceKey).get().build(),
        )
        (0 until response.length()).map { response.getJSONObject(it).toBackendPayment() }
    }

    override suspend fun register(
        username: String,
        email: String,
        password: String,
        verificationCode: String,
    ) = authClient.register(username, email, password, verificationCode)

    suspend fun sendRegistrationCode(email: String): Int = authClient.sendRegistrationCode(email)

    suspend fun verifyRegistrationCode(email: String, verificationCode: String) =
        authClient.verifyRegistrationCode(email, verificationCode)

    suspend fun checkRegistrationUsernameAvailable(username: String, token: String? = null): Boolean =
        authClient.checkRegistrationUsernameAvailable(username, token)

    override suspend fun sendPasswordRecoveryCode(email: String): Int =
        authClient.sendPasswordRecoveryCode(email)

    override suspend fun verifyPasswordRecoveryCode(email: String, verificationCode: String) =
        authClient.verifyPasswordRecoveryCode(email, verificationCode)

    override suspend fun resetPassword(email: String, verificationCode: String, newPassword: String) =
        authClient.resetPassword(email, verificationCode, newPassword)

    override suspend fun deleteAccount(token: String) {
        jsonApi.execute(
            Request.Builder()
                .url(jsonApi.apiUrl("/app/account"))
                .header("Authorization", "Bearer $token")
                .delete()
                .build(),
        )
    }

    override suspend fun login(email: String, password: String, deviceId: String?): BackendAuthTokens =
        authClient.login(email, password, deviceId)

    override suspend fun telegramLogin(idToken: String, deviceId: String?): BackendAuthTokens =
        authClient.telegramLogin(idToken, deviceId)

    override suspend fun googleLogin(idToken: String, deviceId: String?): BackendAuthTokens =
        authClient.googleLogin(idToken, deviceId)

    override suspend fun startTelegramOidc(codeChallenge: String, clientState: String): String =
        authClient.startTelegramOidc(codeChallenge, clientState)

    override suspend fun startTelegramBrowserOidc(codeChallenge: String, clientState: String): String =
        authClient.startTelegramBrowserOidc(codeChallenge, clientState)

    override suspend fun exchangeTelegramOidcCode(code: String, codeVerifier: String): String =
        authClient.exchangeTelegramOidcCode(code, codeVerifier)

    override suspend fun exchangeTelegramBrowserOidc(state: String, codeVerifier: String): String =
        authClient.exchangeTelegramBrowserOidc(state, codeVerifier)

    override suspend fun refreshAuthToken(
        refreshToken: String,
        deviceId: String?,
        requestId: String?,
    ): BackendAuthTokens = authClient.refreshAuthToken(refreshToken, deviceId, requestId)

    override suspend fun revokeRefreshToken(refreshToken: String) =
        authClient.revokeRefreshToken(refreshToken)

    override suspend fun sendAccountEmailCode(
        token: String,
        email: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
        currentEmail: String?,
        currentVerificationCode: String?,
        purpose: String?,
    ): Int = postJson(
        path = "/app/security/email-code",
        payload = JSONObject().put("email", email)
            .put("purpose", purpose)
            .put("current_email", currentEmail)
            .put("current_verification_code", currentVerificationCode),
        token = token,
        currentDeviceId = currentDeviceId,
        currentDeviceKey = currentDeviceKey,
    ).optInt("cooldown_seconds", 60)

    override suspend fun verifyCurrentAccountEmailCode(
        token: String,
        email: String,
        verificationCode: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ) {
        postJson(
            path = "/app/security/email/verify-current",
            payload = JSONObject().put("email", email).put("verification_code", verificationCode),
            token = token,
            currentDeviceId = currentDeviceId,
            currentDeviceKey = currentDeviceKey,
        )
    }

    override suspend fun changeAccountEmail(
        token: String,
        email: String,
        verificationCode: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
        currentEmail: String?,
        currentVerificationCode: String?,
    ): BackendUser = BackendUserResponseParser.parse(
        postJson(
            path = "/app/security/email",
            payload = JSONObject()
                .put("email", email)
                .put("verification_code", verificationCode)
                .put("current_email", currentEmail)
                .put("current_verification_code", currentVerificationCode),
            token = token,
            currentDeviceId = currentDeviceId,
            currentDeviceKey = currentDeviceKey,
        ),
    )

    override suspend fun changeAccountPassword(
        token: String,
        currentPassword: String?,
        newPassword: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
        verificationCode: String?,
    ): BackendAccountPasswordChange {
        val payload = JSONObject().put("new_password", newPassword)
        currentPassword?.takeIf { it.isNotBlank() }?.let { payload.put("current_password", it) }
        verificationCode?.takeIf { it.isNotBlank() }?.let { payload.put("verification_code", it) }
        val response = postJson(
            path = "/app/security/password",
            payload = payload,
            token = token,
            currentDeviceId = currentDeviceId,
            currentDeviceKey = currentDeviceKey,
        )
        return BackendAccountPasswordChange(
            user = BackendUserResponseParser.parse(response.getJSONObject("user")),
            tokens = response.optJSONObject("tokens")?.toBackendAuthTokens(),
        )
    }

    override suspend fun verifyAccountPasswordCode(
        token: String,
        email: String,
        verificationCode: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ) {
        postJson(
            path = "/app/security/password/verify-code",
            payload = JSONObject().put("email", email).put("verification_code", verificationCode),
            token = token,
            currentDeviceId = currentDeviceId,
            currentDeviceKey = currentDeviceKey,
        )
    }

    override suspend fun changeAccountUsername(
        token: String,
        username: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): BackendUser = BackendUserResponseParser.parse(
        patchJson(
            path = "/app/security/username",
            payload = JSONObject().put("username", username),
            token = token,
            currentDeviceId = currentDeviceId,
            currentDeviceKey = currentDeviceKey,
        ),
    )

    override suspend fun setupAccountCredentials(
        token: String,
        username: String,
        password: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): BackendUser = BackendUserResponseParser.parse(
        postJson(
            path = "/app/security/credentials",
            payload = JSONObject().put("username", username).put("password", password),
            token = token,
            currentDeviceId = currentDeviceId,
            currentDeviceKey = currentDeviceKey,
        ),
    )

    override suspend fun linkTelegramAccount(
        token: String,
        idToken: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): BackendUser = BackendUserResponseParser.parse(
        postJson(
            path = "/app/security/telegram/link",
            payload = JSONObject().put("id_token", idToken),
            token = token,
            currentDeviceId = currentDeviceId,
            currentDeviceKey = currentDeviceKey,
        ),
    )

    override suspend fun unlinkTelegramAccount(
        token: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): BackendUser = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$API_PREFIX/app/security/telegram/link")
            .header("Authorization", "Bearer $token")
            .currentDeviceHeaders(currentDeviceId, currentDeviceKey)
            .delete()
            .build()
        BackendUserResponseParser.parse(execute(request))
    }

    override suspend fun bootstrap(
        token: String,
        deviceId: String?,
        deviceKey: String?,
    ): BootstrapPayload {
        val request = Request.Builder()
            .url("$baseUrl$API_PREFIX/app/bootstrap")
            .header("Authorization", "Bearer $token")
            .currentDeviceHeaders(deviceId, deviceKey)
            .get()
            .build()
        val json = withContext(Dispatchers.IO) { execute(request) }
        return BootstrapPayload(
            user = BackendUserResponseParser.parse(json.getJSONObject("user")),
            subscription = json.optJSONObject("subscription")?.toBackendSubscription()
                ?: BackendSubscription("inactive", null, null, null, null),
            plans = json.optJSONArray("plans").toPlanList(),
            locations = json.optJSONArray("locations").toLocationList(),
            devices = json.getJSONArray("devices").toDeviceList(),
            paymentsReady = json.optBoolean("payments_ready", false),
        )
    }

    override suspend fun vpnAccess(
        token: String,
        deviceId: String?,
        deviceKey: String?,
    ): BackendVpnAccess = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$API_PREFIX/vpn/access")
            .header("Authorization", "Bearer $token")
            .currentDeviceHeaders(deviceId, deviceKey)
            .get()
            .build()
        execute(request).toBackendVpnAccess()
    }

    override suspend fun registerDevice(
        token: String,
        deviceKey: String?,
        deviceId: String?,
        deviceName: String,
        publicKey: String,
        deviceClaims: List<String>,
        platform: String,
    ): BackendDevice {
        val claims = JSONArray().apply {
            deviceClaims.forEach { put(it) }
        }
        val payload = JSONObject()
            .put("device_key", deviceKey)
            .put("device_id", deviceId)
            .put("device_name", deviceName)
            .put("platform", platform)
            .put("public_key", publicKey)
            .put("key_algorithm", "rsa-sha256")
            .put("device_claims", claims)
        return postJson("/devices/register", payload, token).toBackendDevice()
    }

    override suspend fun createDeviceChallenge(
        token: String,
        deviceId: String,
    ): BackendDeviceChallenge {
        val payload = JSONObject()
            .put("device_id", deviceId)
        return postJson("/devices/challenge", payload, token).toBackendDeviceChallenge()
    }

    override suspend fun createVpnSession(
        token: String,
        deviceId: String,
        deviceKey: String?,
        deviceNonce: String,
        deviceSignature: String,
        countryCode: String?,
        locationCode: String?,
        excludeLocationCode: String?,
        profileCode: String,
        nodeId: String?,
        multiHop: MultiHopSettings?,
    ): BackendVpnSession {
        val hops = multiHop?.explicitNodeIds()
        require(hops == null || (countryCode == null && locationCode == null &&
            excludeLocationCode == null && nodeId == null)) { "multihop_direct_selector_invalid" }
        val payload = JSONObject()
            .put("device_key", deviceKey)
            .put("device_id", deviceId)
            .put("device_nonce", deviceNonce)
            .put("device_signature", deviceSignature)
            .put("profile_code", if (hops != null) "auto" else profileCode)
            .put("capabilities", JSONArray(listOf("nokinv_v1", "nokinv_release_v1")))
            .put("country_code", countryCode)
            .put("location_code", locationCode)
            .put("exclude_location_code", excludeLocationCode)
            .put("node_id", nodeId)
        if (hops != null) payload.put("multihop", JSONObject()
            .put("version", 2)
            .put("entry", JSONObject().put("kind", "node").put("node_id", hops.first))
            .put("exit", JSONObject().put("kind", "node").put("node_id", hops.second))
            .put("excluded_pairs", JSONArray()))
        return postJson("/vpn/session", payload, token).toBackendVpnSession()
    }

    override suspend fun serverLocations(token: String, deviceId: String?, deviceKey: String?): List<BackendLocation> =
        withContext(Dispatchers.IO) {
            executeArray(Request.Builder().url(jsonApi.apiUrl("/locations"))
                .header("Authorization", "Bearer $token").currentDeviceHeaders(deviceId, deviceKey)
                .get().build()).toLocationList()
        }

    suspend fun ruRelayCatalog(
        token: String,
        deviceId: String,
        deviceKey: String?,
        exitNodeId: String? = null,
        entryNodeId: String? = null,
        routeVersion: Int = 1,
    ): RuRelayCatalog = withContext(Dispatchers.IO) {
        require(routeVersion == 1) { "ru_route_version_invalid" }
        val exitId = exitNodeId?.let(::canonicalRuUuid)
        val entryId = entryNodeId?.let(::canonicalRuUuid)
        require((entryId == null || entryId != exitId) && (exitId != null || entryId == null)) { "ru_route_identity_invalid" }
        val url = jsonApi.apiUrl("/vpn/ru-relays").toHttpUrl().newBuilder()
            .addQueryParameter("route_version", routeVersion.toString())
            .apply { exitId?.let { addQueryParameter("exit_node_id", it) } }
            .apply { entryId?.let { addQueryParameter("entry_node_id", it) } }
            .addQueryParameter("device_id", deviceId)
            .build()
        val json = execute(Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .currentDeviceHeaders(deviceId, deviceKey)
            .get().build())
        RuRelayCatalogJsonParser.parse(json, exitId, entryId, routeVersion)
    }

    override suspend fun createRuWireGuardSession(
        token: String,
        request: RuWireGuardSessionRequest,
    ): BackendRuWireGuardSession = RuWireGuardSessionJsonParser.parse(
        postJson("/vpn/session", request.toBackendJson(), token),
        RuWireGuardSessionExpectation(
            requestId = request.requestId,
            publicKeyId = request.publicKeyId,
            exitNodeId = request.exitNodeId,
            relaySelection = request.relaySelection,
            entryNodeId = request.entryNodeId,
            routeVersion = request.routeVersion,
        ),
    )

    suspend fun pollRuWireGuardSession(
        token: String,
        deviceId: String,
        deviceKey: String?,
        identity: BackendRuWireGuardIdentity,
    ): BackendRuWireGuardSession = withContext(Dispatchers.IO) {
        val url = jsonApi.apiUrl("/vpn/sessions/${identity.sessionId}").toHttpUrl().newBuilder()
            .addQueryParameter("device_id", deviceId).build()
        val json = execute(Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .currentDeviceHeaders(deviceId, deviceKey)
            .get().build())
        parseExistingRuSession(json, identity)
    }

    suspend fun activateRuWireGuardSession(
        token: String,
        deviceId: String,
        deviceKey: String?,
        identity: BackendRuWireGuardIdentity,
    ): BackendRuWireGuardSession = withContext(Dispatchers.IO) {
        val url = jsonApi.apiUrl("/vpn/sessions/${identity.sessionId}/activate").toHttpUrl().newBuilder()
            .addQueryParameter("device_id", deviceId).build()
        val json = execute(Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .currentDeviceHeaders(deviceId, deviceKey)
            .post("{}".toRequestBody(JSON_MEDIA_TYPE)).build())
        parseExistingRuSession(json, identity)
    }

    suspend fun revokeRuWireGuardSession(
        sessionId: String,
        revokeToken: String,
    ): Unit = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(jsonApi.apiUrl("/vpn/sessions/$sessionId"))
            .header("X-Ru-Revoke-Token", revokeToken)
            .delete().build())
        Unit
    }

    private fun parseExistingRuSession(
        json: JSONObject,
        identity: BackendRuWireGuardIdentity,
    ): BackendRuWireGuardSession = RuWireGuardSessionJsonParser.parse(json, RuWireGuardSessionExpectation(
        requestId = identity.requestId,
        publicKeyId = identity.publicKeyId,
        exitNodeId = identity.exitNodeId,
        relaySelection = identity.relaySelection,
        entryNodeId = identity.entryNodeId,
        routeVersion = identity.routeVersion,
    )).also {
        require(it.identity.hasSameAuthorization(identity)) {
            "ru_wireguard_session_changed"
        }
    }

    suspend fun registerRuWireGuardKey(
        token: String,
        deviceId: String,
        deviceKey: String?,
        deviceNonce: String,
        deviceSignature: String,
        publicKey: String,
    ): BackendRuWireGuardKey {
        java.util.UUID.fromString(deviceId)
        val keyBytes = java.util.Base64.getDecoder().decode(publicKey)
        require(keyBytes.size == 32 && keyBytes.any { it.toInt() != 0 } &&
            java.util.Base64.getEncoder().encodeToString(keyBytes) == publicKey) {
            "ru_wireguard_public_key_invalid"
        }
        val payload = JSONObject()
            .put("device_id", deviceId)
            .put("device_key", deviceKey)
            .put("device_nonce", deviceNonce)
            .put("device_signature", deviceSignature)
            .put("public_key", publicKey)
        val json = postJson("/vpn/wireguard-keys", payload, token, deviceId, deviceKey)
        val id = json.getString("id")
        java.util.UUID.fromString(id)
        require(json.getString("public_key") == publicKey) { "ru_wireguard_public_key_mismatch" }
        return BackendRuWireGuardKey(id, publicKey)
    }

    override suspend fun createTemporaryVpnChallenge(
        publicKey: String,
        deviceKey: String,
        deviceName: String,
        platform: String,
    ): BackendTemporaryVpnChallenge {
        val payload = JSONObject()
            .put("public_key", publicKey)
            .put("device_key", deviceKey)
            .put("device_name", deviceName)
            .put("platform", platform)
        return BackendTemporaryVpnResponseParser.parseChallenge(
            postJson("/auth/temporary-vpn/challenge", payload),
        )
    }

    override suspend fun createTemporaryVpnSession(
        publicKey: String,
        nonce: String,
        signature: String,
        deviceKey: String,
    ): BackendTemporaryVpnSession {
        val payload = JSONObject()
            .put("public_key", publicKey)
            .put("nonce", nonce)
            .put("signature", signature)
            .put("device_key", deviceKey)
        return BackendTemporaryVpnResponseParser.parseSession(
            postJson("/auth/temporary-vpn/session", payload),
        )
    }

    override suspend fun revokeTemporaryVpnSession(
        sessionId: String,
        controlToken: String,
    ): Unit = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("session_id", sessionId)
            .put("control_token", controlToken)
        val request = Request.Builder()
            .url("$baseUrl$API_PREFIX/auth/temporary-vpn/session")
            .delete(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request)
        Unit
    }

    override suspend fun clearOtherDevices(
        token: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): List<BackendDevice> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$API_PREFIX/devices/others")
            .header("Authorization", "Bearer $token")
            .currentDeviceHeaders(currentDeviceId, currentDeviceKey)
            .delete()
            .build()
        executeArray(request).toDeviceList()
    }

    override suspend fun deleteCurrentDevice(
        token: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$API_PREFIX/devices/current")
            .header("Authorization", "Bearer $token")
            .currentDeviceHeaders(currentDeviceId, currentDeviceKey)
            .delete()
            .build()
        execute(request)
        Unit
    }

    override suspend fun deleteDevice(
        token: String,
        deviceId: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): List<BackendDevice> = withContext(Dispatchers.IO) {
        val encodedDeviceId = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("$baseUrl$API_PREFIX/devices/$encodedDeviceId")
            .header("Authorization", "Bearer $token")
            .currentDeviceHeaders(currentDeviceId, currentDeviceKey)
            .delete()
            .build()
        executeArray(request).toDeviceList()
    }

    override suspend fun renameDevice(
        token: String,
        deviceId: String,
        name: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): List<BackendDevice> = withContext(Dispatchers.IO) {
        val encodedDeviceId = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        val payload = JSONObject().put("name", name)
        val request = Request.Builder()
            .url("$baseUrl$API_PREFIX/devices/$encodedDeviceId")
            .header("Authorization", "Bearer $token")
            .currentDeviceHeaders(currentDeviceId, currentDeviceKey)
            .patch(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeArray(request).toDeviceList()
    }

    override suspend fun setDeviceFullAccess(
        token: String,
        deviceId: String,
        fullAccess: Boolean,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): List<BackendDevice> = withContext(Dispatchers.IO) {
        val encodedDeviceId = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        val payload = JSONObject()
            .put("access_role", if (fullAccess) "owner" else "invited")
        val request = Request.Builder()
            .url("$baseUrl$API_PREFIX/devices/$encodedDeviceId/access-role")
            .header("Authorization", "Bearer $token")
            .currentDeviceHeaders(currentDeviceId, currentDeviceKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeArray(request).toDeviceList()
    }

    suspend fun createDeviceInvite(
        token: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): BackendDeviceInvite = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$API_PREFIX/devices/invites")
            .header("Authorization", "Bearer $token")
            .currentDeviceHeaders(currentDeviceId, currentDeviceKey)
            .post(JSONObject().toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request).toBackendDeviceInvite()
    }

    suspend fun listIncyDevices(token: String): List<BackendIncyDevice> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$API_PREFIX/incy/devices")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        executeArray(request).toIncyDeviceList()
    }

    suspend fun createIncyDevice(token: String, name: String): BackendIncyDeviceCreate {
        val response = postJson("/incy/devices", JSONObject().put("name", name), token)
        return BackendIncyDeviceCreate(
            device = response.toBackendIncyDevice(),
            importLink = response.getString("import_link"),
            v2raynSubscriptionUrl = response.optionalString("v2rayn_subscription_url"),
        )
    }

    suspend fun getIncyImportLink(token: String, deviceId: String): IncyConnectionLinks {
        val id = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        return jsonApi.get("/incy/devices/$id/import-link", token).toIncyConnectionLinks()
    }

    suspend fun setDeviceTrafficPool(
        token: String,
        deviceId: String,
        trafficPool: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): List<BackendDevice> = withContext(Dispatchers.IO) {
        val id = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("$baseUrl$API_PREFIX/devices/$id/traffic-pool")
            .header("Authorization", "Bearer $token")
            .currentDeviceHeaders(currentDeviceId, currentDeviceKey)
            .patch(JSONObject().put("traffic_pool", trafficPool).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeArray(request).toDeviceList()
    }

    suspend fun setIncyDeviceTrafficPool(token: String, deviceId: String, trafficPool: String): BackendIncyDevice {
        val id = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        return patchJson(
            path = "/incy/devices/$id/traffic-pool",
            payload = JSONObject().put("traffic_pool", trafficPool),
            token = token,
            currentDeviceId = null,
            currentDeviceKey = null,
        ).toBackendIncyDevice()
    }

    suspend fun renameIncyDevice(token: String, deviceId: String, name: String): BackendIncyDevice {
        val id = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        return patchJson(
            path = "/incy/devices/$id",
            payload = JSONObject().put("name", name),
            token = token,
            currentDeviceId = null,
            currentDeviceKey = null,
        ).toBackendIncyDevice()
    }

    suspend fun reissueIncyDevice(token: String, deviceId: String): IncyConnectionLinks {
        val id = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        val response = postJson("/incy/devices/$id/reissue", JSONObject(), token)
        return response.toIncyConnectionLinks()
    }

    suspend fun deleteIncyDevice(token: String, deviceId: String) = withContext(Dispatchers.IO) {
        val id = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("$baseUrl$API_PREFIX/incy/devices/$id")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        execute(request)
        Unit
    }

    suspend fun acceptDeviceInvite(
        inviteCode: String,
        deviceKey: String?,
        deviceId: String?,
        deviceName: String,
        publicKey: String,
        deviceClaims: List<String>,
        platform: String = "android",
    ): BackendInviteAcceptPayload {
        val claims = JSONArray().apply {
            deviceClaims.forEach { put(it) }
        }
        val payload = JSONObject()
            .put("invite_code", inviteCode)
            .put("device_key", deviceKey)
            .put("device_id", deviceId)
            .put("device_name", deviceName)
            .put("platform", platform)
            .put("public_key", publicKey)
            .put("key_algorithm", "rsa-sha256")
            .put("device_claims", claims)
        val response = postJson("/devices/invites/accept", payload)
        return BackendInviteAcceptPayload(
            tokens = response.toBackendAuthTokens(),
            device = response.getJSONObject("device").toBackendDevice(),
        )
    }

    suspend fun uploadAppLogs(
        token: String,
        deviceId: String?,
        deviceKey: String?,
        deviceName: String,
        logsText: String,
        incident: VpnIncidentReport? = null,
    ) = contentClient.uploadAppLogs(token, deviceId, deviceKey, deviceName, logsText, incident)

    suspend fun uploadEndpointHealthEvents(
        token: String,
        events: List<EndpointHealthEvent>,
    ) = contentClient.uploadEndpointHealthEvents(token, events)

    suspend fun appNotifications(token: String): List<BackendAppNotification> =
        contentClient.appNotifications(token)

    suspend fun registerFcmToken(
        token: String,
        fcmToken: String,
        deviceId: String?,
    ) = contentClient.registerFcmToken(token, fcmToken, deviceId)

    suspend fun androidUpdateAvailable(
        token: String,
        versionCode: Long,
        abis: List<String>,
    ): Boolean = contentClient.androidUpdateAvailable(token, versionCode, abis)

    suspend fun geositeMetadata(): Pair<String, Int> = contentClient.geositeMetadata()

    suspend fun downloadGeosite(expectedSize: Int): ByteArray = contentClient.downloadGeosite(expectedSize)

    suspend fun uploadAvatar(
        token: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): String = contentClient.uploadAvatar(token, fileName, mimeType, bytes)

    suspend fun deleteAvatar(token: String): String? = contentClient.deleteAvatar(token)

    suspend fun androidUpdate(
        token: String,
        versionCode: Long,
        abis: List<String>,
    ): BackendAndroidUpdate = contentClient.androidUpdate(token, versionCode, abis)

    suspend fun downloadAndroidUpdateApk(
        token: String,
        apkUrl: String,
        expectedSha256: String?,
        expectedSizeBytes: Long?,
        destination: File,
    ): Long = contentClient.downloadAndroidUpdateApk(
        token = token,
        apkUrl = apkUrl,
        expectedSha256 = expectedSha256,
        expectedSizeBytes = expectedSizeBytes,
        destination = destination,
    )

    suspend fun downloadProfileAvatar(
        token: String,
        avatarUrl: String,
        destination: File,
    ): File = contentClient.downloadProfileAvatar(token, avatarUrl, destination)

    private suspend fun postJson(
        path: String,
        payload: JSONObject,
        token: String? = null,
        currentDeviceId: String? = null,
        currentDeviceKey: String? = null,
    ): JSONObject = jsonApi.post(path, payload, token, currentDeviceId, currentDeviceKey)

    private suspend fun patchJson(
        path: String,
        payload: JSONObject,
        token: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): JSONObject = jsonApi.patch(path, payload, token, currentDeviceId, currentDeviceKey)

    private suspend fun execute(request: Request): JSONObject = jsonApi.execute(request)

    private suspend fun executeArray(request: Request): JSONArray = jsonApi.executeArray(request)

    companion object {
        private const val API_PREFIX = "/v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private fun JSONObject.optionalString(key: String): String? =
    if (has(key) && !isNull(key)) getString(key).takeIf(String::isNotBlank) else null

private fun JSONObject.toIncyConnectionLinks(): IncyConnectionLinks = IncyConnectionLinks(
    importLink = IncyImportLink.parse(getString("import_link")),
    v2raynSubscriptionUrl = optionalString("v2rayn_subscription_url")?.let(V2raynSubscriptionUrl::parse),
)
