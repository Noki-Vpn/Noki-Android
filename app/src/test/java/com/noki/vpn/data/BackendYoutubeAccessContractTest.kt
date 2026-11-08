package com.noki.vpn.data

import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal fun youtubeAccessFixture(status: String = "ready", expiresAt: Long = System.currentTimeMillis() + 120_000): JSONObject =
    JSONObject().put("version", 1).put("mode", "wireguard").put("status", status)
        .put("grant_id", "22222222-2222-4222-8222-222222222222").put("generation", 1)
        .put("expires_at", Instant.ofEpochMilli(expiresAt).toString()).put("revoke_token", "fixture-revoke")
        .apply { if (status == "ready") {
            put("public_key_id", "33333333-3333-4333-8333-333333333333")
            put("wireguard", JSONObject().put("endpoint", "203.0.113.5:51820")
                .put("server_public_key", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
                .put("address", "10.241.0.2/32").put("dns", org.json.JSONArray().put("1.1.1.1"))
                .put("mtu", 1280).put("allowed_ips", org.json.JSONArray().put("0.0.0.0/0")))
        } }

class BackendYoutubeAccessContractTest {
    @Test fun wireGuardRegistrationCannotOverwriteAnOutstandingDeviceChallenge() = runBlocking {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var nonce = 0
        var outstanding: String? = null
        val consumed = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val payload = if (request.url.encodedPath.endsWith("/devices/challenge")) {
                outstanding = "nonce-${++nonce}"
                if (nonce == 2) second.complete(Unit)
                JSONObject().put("device_id", "11111111-1111-4111-8111-111111111111").put("nonce", outstanding)
            } else {
                val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                val proof = body.getString("device_nonce")
                check(outstanding == proof)
                outstanding = null
                consumed += proof
                JSONObject().put("id", "33333333-3333-4333-8333-333333333333").put("public_key", body.getString("public_key"))
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example", challengeSigner = { "signed-$it" })
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            DeviceIdentity.withChallengeTransaction {
                val proof = api.createDeviceChallenge("access", "11111111-1111-4111-8111-111111111111")
                held.complete(Unit)
                release.await()
                assertEquals(proof.nonce, outstanding)
                outstanding = null
                consumed += proof.nonce
            }
        }
        held.await()
        val registration = async(start = CoroutineStart.UNDISPATCHED) {
            api.registerRuWireGuardKey("access", "11111111-1111-4111-8111-111111111111", "key",
                java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        }
        try { assertNull(withTimeoutOrNull(100) { second.await() }) } finally { release.complete(Unit) }
        withTimeout(5_000) { first.await(); registration.await() }
        assertEquals(listOf("nonce-1", "nonce-2"), consumed)
    }

    @Test fun readyAcceptsOnlyWireGuardMaterialAndTheRegisteredPublicKeyIdentity() {
        val ready = BackendYoutubeAccessJsonParser.parse(youtubeAccessFixture())
        assertEquals("wireguard", ready.mode)
        assertEquals("33333333-3333-4333-8333-333333333333", ready.publicKeyId)
        assertEquals("203.0.113.5:51820", ready.wireguard!!.endpoint)
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", 2) }, { it.put("mode", "selected_transport") }, { it.put("vpn_secret", "shared") },
            { it.put("nokinv_device_id", "device") }, { it.put("nokinv_credential_generation", 7) },
            { it.put("endpoint", JSONObject()) }, { it.put("public_key_id", "") },
            { it.put("wireguard", JSONObject.NULL) },
            { it.put("expires_at", Instant.EPOCH.toString()) },
        )
        mutations.forEach { mutate -> assertThrows(IllegalArgumentException::class.java) {
            BackendYoutubeAccessJsonParser.parse(youtubeAccessFixture().also(mutate))
        } }
        val wireguardMutations = listOf("endpoint" to "node.example:51820", "endpoint" to "203.0.113.5:0",
            "address" to "10.241.0.2/24", "server_public_key" to "invalid", "mtu" to 1279,
            "dns" to org.json.JSONArray().put("resolver.example"),
            "allowed_ips" to org.json.JSONArray().put("::/0"))
        wireguardMutations.forEach { (key, value) -> assertThrows(IllegalArgumentException::class.java) {
            BackendYoutubeAccessJsonParser.parse(youtubeAccessFixture().apply { getJSONObject("wireguard").put(key, value) })
        } }
    }

    @Test fun pendingAndBlockedCannotCarryRuntimeCredentials() {
        for (status in listOf("pending", "blocked")) {
            assertNull(BackendYoutubeAccessJsonParser.parse(youtubeAccessFixture(status)).wireguard)
            for (field in listOf("wireguard", "public_key_id", "endpoint", "vpn_secret", "nokinv_device_id", "nokinv_credential_generation")) {
                assertThrows(IllegalArgumentException::class.java) {
                    BackendYoutubeAccessJsonParser.parse(youtubeAccessFixture(status).put(field, "unexpected"))
                }
            }
        }
        assertEquals("blocked", BackendYoutubeAccessJsonParser.parse(youtubeAccessFixture("blocked", 0)).status)
    }

    @Test fun everyAdmissionOperationUsesAFreshSerializedProofAndRevokeIsACapability() = runBlocking {
        val requests = mutableListOf<Request>()
        var nonce = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val payload = when {
                request.url.encodedPath == "/v1/devices/challenge" -> JSONObject().put("device_id", "device").put("nonce", "nonce-${++nonce}")
                request.method == "DELETE" -> JSONObject()
                else -> youtubeAccessFixture()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example", challengeSigner = { "signed-$it" })
        val keyId = "33333333-3333-4333-8333-333333333333"
        val grant = api.createYoutubeAccess("access", "22222222-2222-4222-8222-222222222222", "device", "key", keyId)
        api.pollYoutubeAccess("access", "device", "key", grant.grantId)
        api.renewYoutubeAccess("access", "device", "key", grant)
        api.revokeYoutubeAccess(grant.grantId, grant.revokeToken)
        assertEquals(3, nonce)
        val admissions = requests.filter { it.url.encodedPath.contains("youtube-access") && it.method != "DELETE" }
        assertEquals(listOf("POST", "GET", "POST"), admissions.map { it.method })
        admissions.forEachIndexed { index, request ->
            assertNull(request.header("X-Youtube-Transport"))
            assertEquals("Bearer access", request.header("Authorization"))
            assertEquals("device", request.header("X-Device-Id"))
            assertEquals("key", request.header("X-Device-Key"))
            assertEquals("nonce-${index + 1}", request.header("X-Device-Nonce"))
            assertEquals("signed-nonce-${index + 1}", request.header("X-Device-Signature"))
        }
        val create = JSONObject(Buffer().also { admissions.first().body!!.writeTo(it) }.readUtf8())
        assertEquals(setOf("request_id", "device_id", "device_key", "device_nonce", "device_signature", "public_key_id"), create.keys().asSequence().toSet())
        assertEquals(keyId, create.getString("public_key_id"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            api.createYoutubeAccess("access", "22222222-2222-4222-8222-222222222222", "device", "key", "bad-key-id")
        } }
        val renew = JSONObject(Buffer().also { admissions.last().body!!.writeTo(it) }.readUtf8())
        assertEquals(setOf("generation"), renew.keys().asSequence().toSet())
        assertEquals("device", admissions.last().url.queryParameter("device_id"))
        val revoke = requests.last()
        assertEquals("DELETE", revoke.method)
        assertEquals("fixture-revoke", revoke.header("X-Youtube-Revoke-Token"))
        assertNull(revoke.header("X-Youtube-Transport"))
        assertNull(revoke.header("Authorization"))
        assertNull(revoke.header("X-Device-Nonce"))
    }
}
