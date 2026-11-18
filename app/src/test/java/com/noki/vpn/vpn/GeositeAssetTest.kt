package com.noki.vpn.vpn

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeositeAssetTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun unsupportedDomainTypeCannotReplaceTheVerifiedAsset() {
        assertFalse(GeositeAsset.isValid(asset("CATEGORY-RU" to listOf(domain(4, "ru.example")))))
    }

    @Test
    fun verifiedDigestDoesNotTrustChangedBytesOrShareMutableRules() {
        val bytes = asset("CATEGORY-RU" to listOf(domain(2, "original.example")))
        assertTrue(GeositeAsset.isValid(bytes))
        val selectors = setOf("geosite:category-ru")
        GeositeAsset.resolveDomains(bytes, selectors).getValue("geosite:category-ru")
            .getJSONArray("domain_suffix").put("injected.example")
        val again = GeositeAsset.resolveDomains(bytes, selectors).getValue("geosite:category-ru")
        assertEquals(1, again.getJSONArray("domain_suffix").length())
        assertFalse(GeositeAsset.isValid(asset("CATEGORY-RU" to listOf(domain(4, "bad.example")))))
        assertThrows(IllegalArgumentException::class.java) {
            GeositeAsset.resolveDomains(bytes, setOf("geosite:missing"))
        }
        val updated = asset("CATEGORY-RU" to listOf(domain(2, "updated.example")))
        assertEquals("updated.example", GeositeAsset.resolveDomains(updated, selectors)
            .getValue("geosite:category-ru").getJSONArray("domain_suffix").getString(0))
    }

    @Test
    fun everyProtobufDomainTypeResolvesToItsNativeMeaning() {
        val bytes = asset("CATEGORY-RU" to listOf(
            domain(0, "keyword"), domain(1, "^Case[.]"), domain(2, "suffix.example"), domain(3, "exact.example"),
        ))
        val fields = GeositeAsset.resolveDomains(bytes, setOf("geosite:category-ru")).getValue("geosite:category-ru")
        assertEquals("[\"keyword\"]", fields.getJSONArray("domain_keyword").toString())
        assertEquals("[\"^Case[.]\"]", fields.getJSONArray("domain_regex").toString())
        assertEquals("[\"suffix.example\"]", fields.getJSONArray("domain_suffix").toString())
        assertEquals("[\"exact.example\"]", fields.getJSONArray("domain").toString())
    }

    @Test
    fun attributeConjunctionUsesKeyPresenceIncludingFalseAndZeroValues() {
        val both = listOf(attribute("cn", 2, 0), attribute("ads", 3, 0))
        val bytes = asset("CATEGORY-RU" to listOf(domain(2, "ru.example")), "SAMPLE" to listOf(
            domain(3, "both.example", both), domain(3, "one.example", listOf(attribute("cn", 2, 1))),
            domain(3, "neither.example"),
        ))
        val resolved = GeositeAsset.resolveDomains(bytes, setOf("geosite:sample@cn@ads", "geosite:sample@missing"))
        assertEquals("[\"both.example\"]", resolved.getValue("geosite:sample@cn@ads").getJSONArray("domain").toString())
        assertEquals(0, resolved.getValue("geosite:sample@missing").length())
    }

    @Test
    fun unknownTagsMalformedUtf8AndTruncatedLengthsFailClosed() {
        val bytes = asset("CATEGORY-RU" to listOf(domain(2, "ru.example")))
        assertThrows(IllegalArgumentException::class.java) { GeositeAsset.resolveDomains(bytes, setOf("geosite:unknown")) }
        assertThrows(IllegalArgumentException::class.java) { GeositeAsset.resolveDomains(bytes, setOf("geosite:category-ru@")) }
        val malformed = field(1, 2) + field(2, byteArrayOf(0xc3.toByte(), 0x28))
        assertFalse(GeositeAsset.isValid(asset("CATEGORY-RU" to listOf(malformed))))
        assertFalse(GeositeAsset.isValid(bytes.copyOf(bytes.size - 1)))
        assertFalse(GeositeAsset.isValid(bytes + byteArrayOf(0x0a, 0xff.toByte(), 0xff.toByte())))
    }

    private fun context(): Context {
        val cache = temporaryFolder.newFolder("core")
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File = cache
        }
    }

    @Test
    fun damagedUpdateKeepsTheLastValidBundledFile() {
        val context = context()
        val bundled = context.assets.open("geodata.zip").use { it.readBytes() }
        GeositeAsset.ensureBundledFallback(context)
        val version = GeositeAsset.currentVersion(context)
        val damaged = bundled.copyOfRange(0, bundled.size / 2)
        assertTrue(runCatching { GeositeAsset.install(context, damaged) }.isFailure)
        assertEquals(version, GeositeAsset.currentVersion(context))
        assertArrayEquals(bundled, GeositeAsset.file(context).readBytes())
    }

    @Test
    fun bundledSnapshotResolvesRussianResourcesWithoutTheOldRuntime() {
        val context = context()
        GeositeAsset.ensureBundledFallback(context)
        val release = GeositeAsset.readSnapshot(context)
        val result = GeositeAsset.resolveDomains(release.geosite, setOf("geosite:category-ru", "geosite:youtube@cn"))
        val russianSuffixes = result.getValue("geosite:category-ru").getJSONArray("domain_suffix")
            .let { values -> (0 until values.length()).map(values::getString).toSet() }
        assertTrue(russianSuffixes.containsAll(listOf("ru", "su", "xn--p1ai")))
        assertTrue(release.russianCidrs.isNotEmpty())
        assertEquals(release.version, GeositeAsset.currentVersion(context))
    }

    @Test
    fun validCachedFileSurvivesBootstrapAndConcurrentReadWrite() {
        val context = context()
        val bundled = context.assets.open("geodata.zip").use { it.readBytes() }
        val updated = release("added.example")
        GeositeAsset.install(context, bundled)
        val oldDigest = GeositeAsset.decodeRelease(bundled.inputStream()).version
        val updatedDigest = GeositeAsset.decodeRelease(updated.inputStream()).version
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val reader = executor.submit<String> {
                start.await()
                GeositeAsset.readSnapshot(context).version
            }
            val writer = executor.submit {
                start.await()
                GeositeAsset.install(context, updated)
            }
            start.countDown()
            val observed = reader.get(30, TimeUnit.SECONDS)
            writer.get(30, TimeUnit.SECONDS)
            assertTrue(observed == oldDigest || observed == updatedDigest)
        } finally {
            executor.shutdownNow()
        }
        GeositeAsset.ensureBundledFallback(context)
        assertEquals(updatedDigest, GeositeAsset.currentVersion(context))
        assertArrayEquals(updated, GeositeAsset.file(context).readBytes())
    }

    @Test
    fun coldBootstrapCannotOverwriteAConcurrentVerifiedUpdate() {
        val context = context()
        val updated = release("updated.example")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val bootstrap = executor.submit {
                start.await()
                GeositeAsset.ensureBundledFallback(context)
            }
            val update = executor.submit {
                start.await()
                GeositeAsset.install(context, updated)
            }
            start.countDown()
            bootstrap.get(30, TimeUnit.SECONDS)
            update.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        assertArrayEquals(updated, GeositeAsset.file(context).readBytes())
    }

    companion object {
        internal fun snapshot(vararg sites: Pair<String, List<ByteArray>>): GeositeAsset.Snapshot =
            GeositeAsset.Snapshot("test", asset(*sites), listOf("5.8.0.0/16"))

        internal fun release(extraDomain: String): ByteArray {
            val geosite = asset("CATEGORY-RU" to listOf("ru", "su", "xn--p1ai", extraDomain).map { domain(2, it) })
            val geoip = field(1, field(1, "RU".toByteArray()) +
                field(2, field(1, byteArrayOf(5, 8, 0, 0)) + field(2, 16L)))
            val geositeHash = GeositeAsset.sha256(geosite)
            val geoipHash = GeositeAsset.sha256(geoip)
            val manifest = org.json.JSONObject()
                .put("version", GeositeAsset.sha256("$geositeHash:$geoipHash".toByteArray()))
                .put("geosite", org.json.JSONObject().put("sha256", geositeHash).put("size_bytes", geosite.size))
                .put("geoip", org.json.JSONObject().put("sha256", geoipHash).put("size_bytes", geoip.size))
            val output = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(output).use { zip ->
                mapOf("manifest.json" to manifest.toString().toByteArray(), "geosite.dat" to geosite, "geoip.dat" to geoip)
                    .forEach { (name, bytes) ->
                        zip.putNextEntry(java.util.zip.ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
            }
            return output.toByteArray()
        }

        internal fun asset(vararg sites: Pair<String, List<ByteArray>>): ByteArray {
            val padding = "PADDING" to List(80) { domain(2, "padding.example") }
            return (sites.toList() + padding).fold(byteArrayOf()) { result, (code, domains) ->
                result + field(1, field(1, code.toByteArray()) + domains.fold(byteArrayOf()) { nested, value -> nested + field(2, value) })
            }
        }

        internal fun domain(type: Int, value: String, attributes: List<ByteArray> = emptyList()): ByteArray =
            field(1, type.toLong()) + field(2, value.toByteArray()) +
                attributes.fold(byteArrayOf()) { result, attribute -> result + field(3, attribute) }

        private fun attribute(key: String, valueField: Int, value: Long): ByteArray =
            field(1, key.toByteArray()) + field(valueField, value)

        private fun field(number: Int, value: ByteArray): ByteArray = varint((number.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
        private fun field(number: Int, value: Long): ByteArray = varint(number.toLong() shl 3) + varint(value)
        private fun varint(value: Long): ByteArray {
            var remaining = value
            val bytes = ArrayList<Byte>()
            do {
                val next = remaining and 0x7f
                remaining = remaining ushr 7
                bytes += (next or if (remaining != 0L) 0x80 else 0).toByte()
            } while (remaining != 0L)
            return bytes.toByteArray()
        }
    }
}
