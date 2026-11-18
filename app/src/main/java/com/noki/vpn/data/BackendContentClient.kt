package com.noki.vpn.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.CoroutineContext

internal data class GeodataMetadata(val version: String, val size: Int)

internal class BackendContentClient(
    private val jsonApi: BackendJsonApi,
) {
    private val baseUrl = jsonApi.baseUrl
    private val baseHttpUrl = baseUrl.toHttpUrl()

    suspend fun uploadAppLogs(
        token: String,
        deviceId: String?,
        deviceKey: String?,
        deviceName: String,
        logsText: String,
        incident: VpnIncidentReport? = null,
    ) {
        val payload = JSONObject()
            .put("device_id", deviceId)
            .put("device_key", deviceKey)
            .put("device_name", deviceName)
            .put("platform", "android")
            .put("content", logsText)
            .apply {
                incident?.let {
                    put("incident_id", it.id)
                    put("incident_reason", it.reason)
                    put("country_code", it.countryCode)
                    put("location_code", it.locationCode)
                    put("recovery_attempts", it.recoveryAttempts)
                    put("incident_outcome", it.outcome)
                    put("incident_occurred_at", it.occurredAt)
                }
            }
        postJson("/app/logs", payload, token)
    }

    suspend fun uploadEndpointHealthEvents(
        token: String,
        events: List<EndpointHealthEvent>,
    ) {
        if (events.isEmpty()) return
        postJson(
            "/app/endpoint-health-events",
            EndpointHealthEvents.toRequestJson(events),
            token,
        )
    }

    suspend fun appNotifications(token: String): List<BackendAppNotification> {
        val response = getJson("/app/notifications?limit=20", token)
        return response.optJSONArray("notifications").toAppNotificationList()
    }

    suspend fun registerFcmToken(
        token: String,
        fcmToken: String,
        deviceId: String?,
    ) {
        val payload = JSONObject()
            .put("platform", "android")
            .put("fcm_token", fcmToken)
        deviceId?.takeIf { it.isNotBlank() }?.let { payload.put("device_id", it) }
        postJson("/app/devices/fcm-token", payload, token)
    }

    suspend fun androidUpdateAvailable(
        token: String,
        versionCode: Long,
        abis: List<String>,
    ): Boolean = androidUpdate(token, versionCode, abis).updateAvailable

    suspend fun geodataMetadata(): GeodataMetadata? = withContext(Dispatchers.IO) {
        val response = execute(Request.Builder().url(jsonApi.apiUrl("/app/geosite")).get().build())
        if (!response.has("version") && !response.has("release_size_bytes")) return@withContext null
        val version = response.getString("version")
        val size = response.getInt("release_size_bytes")
        require(version.matches(GEODATA_VERSION) && size in 1..GEODATA_MAX_BYTES) {
            "geodata_metadata_invalid"
        }
        GeodataMetadata(version, size)
    }

    suspend fun downloadGeodata(metadata: GeodataMetadata): ByteArray = withContext(Dispatchers.IO) {
        val (version, expectedSize) = metadata
        require(version.matches(GEODATA_VERSION) && expectedSize in 1..GEODATA_MAX_BYTES)
        val request = Request.Builder().url(jsonApi.apiUrl("/app/geosite/releases/$version")).get().build()
        val downloaded = ByteArrayOutputStream(expectedSize)
        val coroutine = currentCoroutineContext()
        jsonApi.stream(request) { body ->
            require(body.contentLength() < 0 || body.contentLength() == expectedSize.toLong()) {
                "geodata_size_mismatch"
            }
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer, 0, minOf(buffer.size, expectedSize - downloaded.size() + 1))
                    coroutine.ensureActive()
                    if (read < 0) break
                    require(downloaded.size() + read <= expectedSize) { "geodata_size_mismatch" }
                    downloaded.write(buffer, 0, read)
                }
            }
        }
        require(downloaded.size() == expectedSize) { "geodata_size_mismatch" }
        downloaded.toByteArray()
    }

    suspend fun uploadAvatar(
        token: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        if (bytes.size > AVATAR_UPLOAD_MAX_BYTES) {
            throw IllegalArgumentException("avatar_file_too_large")
        }
        val mediaType = runCatching {
            mimeType.takeIf { it.isNotBlank() }?.toMediaType()
        }.getOrNull() ?: "application/octet-stream".toMediaType()
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                sanitizeAvatarFileName(fileName),
                bytes.toRequestBody(mediaType),
            )
            .build()
        val request = Request.Builder()
            .url("$baseUrl$API_PREFIX/app/profile/avatar")
            .header("Authorization", "Bearer $token")
            .post(multipart)
            .build()
        execute(request).optBackendString("avatar_url")
            ?: throw BackendException("avatar_url missing", 502)
    }

    suspend fun uploadSupportAttachments(token: String, content: String, requestId: String, files: List<SupportAttachmentDraft>): BackendSupportMessage = withContext(Dispatchers.IO) {
        require(files.size in 1..SUPPORT_ATTACHMENT_MAX_COUNT) { "support_attachment_count" }
        require(files.all { File(it.path).isFile && File(it.path).length() in 0..SUPPORT_ATTACHMENT_MAX_BYTES } &&
            files.sumOf { File(it.path).length() } <= SUPPORT_ATTACHMENT_MAX_BYTES) { "support_attachment_too_large" }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("content", content)
            .addFormDataPart("client_message_id", requestId)
            .apply { files.forEach { file -> addFormDataPart("attachment", file.name,
                File(file.path).asRequestBody(file.mime.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType())) } }
            .build()
        jsonApi.executeStreaming(Request.Builder().url(jsonApi.apiUrl("/app/support/chat/attachments"))
            .header("Authorization", "Bearer $token").post(multipart).build()).toBackendSupportMessage()
    }

    suspend fun downloadSupportAttachment(token: String, attachment: BackendSupportAttachment, destination: File): File =
        withContext(Dispatchers.IO) {
            require(attachment.id > 0 && attachment.messageId > 0)
            val coroutine = currentCoroutineContext()
            val temporary = File(destination.parentFile, "${destination.name}.part")
            try {
                val request = Request.Builder().url(authenticatedDownloadUrl(
                    "$API_PREFIX${attachment.contentPath}", "$API_PREFIX/app/support/chat/messages/"))
                    .header("Authorization", "Bearer $token").get().build()
                destination.parentFile?.mkdirs()
                jsonApi.stream(request) { body ->
                    writePrivateDownload(body, temporary, SUPPORT_ATTACHMENT_MAX_BYTES, coroutine, "support_attachment_too_large")
                }
                coroutine.ensureActive()
                Files.move(temporary.toPath(), destination.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
                destination
            } finally { temporary.delete() }
        }

    suspend fun deleteAvatar(token: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$API_PREFIX/app/profile/avatar")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        execute(request).optBackendString("avatar_url")
    }

    suspend fun androidUpdate(
        token: String,
        versionCode: Long,
        abis: List<String>,
    ): BackendAndroidUpdate = withContext(Dispatchers.IO) {
        val url = "$baseUrl$API_PREFIX/app/android-update"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("version_code", versionCode.toString())
            .addQueryParameter("abis", abis.joinToString(","))
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        execute(request).toBackendAndroidUpdate()
    }

    suspend fun downloadAndroidUpdateApk(
        token: String,
        apkUrl: String,
        expectedSha256: String?,
        expectedSizeBytes: Long?,
        destination: File,
    ): Long = withContext(Dispatchers.IO) {
        val downloadContext = currentCoroutineContext()
        val expectedSize = expectedSizeBytes?.also { size ->
            if (size <= 0L) throw IllegalArgumentException("apk_size_invalid")
            if (size > APK_DOWNLOAD_MAX_BYTES) throw IllegalArgumentException("apk_file_too_large")
        }
        val temporary = File(destination.parentFile, "${destination.name}.part")
        val resolvedUrl = authenticatedDownloadUrl(
            rawUrl = apkUrl,
            expectedPathPrefix = "$API_PREFIX/app/android-releases/",
        )
        val request = Request.Builder()
            .url(resolvedUrl)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        destination.parentFile?.mkdirs()
        try {
            var downloadedBytes = 0L
            jsonApi.stream(request) { body ->
                val contentLength = body.contentLength()
                if (contentLength > APK_DOWNLOAD_MAX_BYTES) {
                    throw IllegalArgumentException("apk_file_too_large")
                }
                if (contentLength >= 0L && expectedSize != null && contentLength != expectedSize) {
                    throw IllegalArgumentException("apk_size_mismatch")
                }
                val byteLimit = expectedSize ?: APK_DOWNLOAD_MAX_BYTES
                temporary.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val readLimit = minOf(
                                buffer.size.toLong(),
                                byteLimit - downloadedBytes + 1L,
                            ).toInt()
                            val read = input.read(buffer, 0, readLimit)
                            downloadContext.ensureActive()
                            if (read < 0) break
                            if (read == 0) continue
                            downloadedBytes += read
                            if (downloadedBytes > byteLimit) {
                                val reason = if (expectedSize != null) "apk_size_mismatch" else "apk_file_too_large"
                                throw IllegalArgumentException(reason)
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (downloadedBytes == 0L) {
                throw IllegalArgumentException("apk_file_empty")
            }
            if (expectedSize != null && downloadedBytes != expectedSize) {
                throw IllegalArgumentException("apk_size_mismatch")
            }
            downloadContext.ensureActive()
            expectedSha256?.trim()?.takeIf { it.isNotBlank() }?.let { expected ->
                val actual = sha256Hex(temporary)
                if (!actual.equals(expected.lowercase(Locale.ROOT), ignoreCase = true)) {
                    throw BackendException("APK checksum mismatch", 0)
                }
            }
            downloadContext.ensureActive()
            Files.move(temporary.toPath(), destination.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            downloadedBytes
        } finally {
            temporary.delete()
        }
    }

    suspend fun downloadProfileAvatar(
        token: String,
        avatarUrl: String,
        destination: File,
    ): File = withContext(Dispatchers.IO) {
        val downloadContext = currentCoroutineContext()
        val temporary = File(destination.parentFile, "${destination.name}.part")
        try {
            val resolvedUrl = authenticatedDownloadUrl(
                rawUrl = avatarUrl,
                expectedPathPrefix = "$API_PREFIX/app/profile/avatar/",
            )
            val request = Request.Builder()
                .url(resolvedUrl)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            destination.parentFile?.mkdirs()
            var downloadedBytes = 0L
            jsonApi.stream(request) { body ->
                downloadedBytes = writePrivateDownload(body, temporary, AVATAR_DOWNLOAD_MAX_BYTES, downloadContext, "avatar_file_too_large")
            }
            if (downloadedBytes == 0L) {
                throw IllegalArgumentException("avatar_open_failed")
            }
            downloadContext.ensureActive()
            Files.move(temporary.toPath(), destination.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            destination
        } finally {
            temporary.delete()
        }
    }


    private suspend fun getJson(path: String, token: String): JSONObject = jsonApi.get(path, token)

    private suspend fun postJson(path: String, payload: JSONObject, token: String): JSONObject =
        jsonApi.post(path, payload, token)

    private suspend fun execute(request: Request): JSONObject = jsonApi.execute(request)

    private fun sanitizeAvatarFileName(fileName: String): String {
        val cleaned = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.', '-')
        return cleaned.ifBlank { "avatar.jpg" }.take(96)
    }

    private fun writePrivateDownload(body: ResponseBody, destination: File, limit: Long, coroutine: CoroutineContext, tooLarge: String): Long {
        require(body.contentLength() <= limit) { tooLarge }
        var size = 0L
        destination.outputStream().use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - size + 1).toInt())
                    coroutine.ensureActive()
                    if (read < 0) break
                    if (read == 0) continue
                    size += read
                    require(size <= limit) { tooLarge }
                    output.write(buffer, 0, read)
                }
            }
        }
        return size
    }

    private fun authenticatedDownloadUrl(rawUrl: String, expectedPathPrefix: String): okhttp3.HttpUrl {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotBlank()) { "download_url_missing" }
        val resolved = if (trimmed.startsWith("/")) "$baseUrl$trimmed".toHttpUrl() else trimmed.toHttpUrl()
        require(resolved.scheme == "https") { "download_url_must_use_https" }
        require(resolved.host == baseHttpUrl.host && resolved.port == baseHttpUrl.port) {
            "download_url_origin_not_allowed"
        }
        require(resolved.username.isEmpty() && resolved.password.isEmpty()) {
            "download_url_userinfo_not_allowed"
        }
        require(resolved.encodedPath.startsWith(expectedPathPrefix)) { "download_url_path_not_allowed" }
        return resolved
    }

    companion object {
        private const val API_PREFIX = "/v1"
        private const val AVATAR_UPLOAD_MAX_BYTES = 1L * 1024L * 1024L
        private const val AVATAR_DOWNLOAD_MAX_BYTES = 5L * 1024L * 1024L
        private const val APK_DOWNLOAD_MAX_BYTES = 256L * 1024L * 1024L
        private const val GEODATA_MAX_BYTES = 64 * 1024 * 1024 + 64 * 1024
        private val GEODATA_VERSION = Regex("[0-9a-f]{64}")
    }
}

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    file.inputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
