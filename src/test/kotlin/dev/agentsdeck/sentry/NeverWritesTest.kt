package dev.agentsdeck.sentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * **This extension only ever reads.** Tessl's counterpart states that as a rule about the
 * user's project because that is where its risk is; here the risk is the other way round — the
 * only thing this extension touches is someone's *crash reporter*, and a mutating request
 * would resolve, ignore or delete an issue on their behalf.
 *
 * Two halves, because either alone is the weaker claim that passes while the other is broken:
 * the transport cannot express a write, and no source in this extension builds one.
 */
class NeverWritesTest {

    /**
     * The seam itself. A transport with one `get` is a shape a mutating call cannot be written
     * against without changing this interface — which is the point, and what this pins.
     */
    @Test
    fun `the transport can only express a read`() {
        val methods = SentryTransport::class.java.declaredMethods.filterNot { it.isSynthetic }
        assertEquals("a second method here is a second thing this extension can do", 1, methods.size)
        assertEquals("get", methods.single().name)
    }

    /**
     * `HttpRequest.Builder` has exactly four ways to leave GET, and this asserts none of them
     * appears — the invariant, not one spelling of it. A behaviour-preserving rename cannot
     * break this; adding a write is what breaks it.
     */
    @Test
    fun `no source in this extension builds a mutating request`() {
        val sources = Files.walk(mainSources()).use { stream ->
            stream.filter { it.name.endsWith(".kt") }.toList()
        }
        assertTrue("the scan found no sources at all, so it is asserting nothing", sources.size >= 5)
        for (source in sources) {
            val text = Files.readString(source)
            for (verb in listOf(".POST(", ".PUT(", ".DELETE(", ".method(")) {
                assertTrue("${source.name} builds a $verb request", !text.contains(verb))
            }
        }
    }

    /**
     * Nothing is persisted either. The credential is resolved per use through the host and
     * held only as a digest; a `PropertiesComponent`, a `@State` or a file write would each be
     * a place the user's token could come to rest inside this plugin.
     */
    @Test
    fun `no source in this extension persists anything`() {
        val forbidden = listOf(
            "PropertiesComponent",
            "PersistentStateComponent",
            "Files.write",
            "Files.newBufferedWriter",
            "writeText",
        )
        Files.walk(mainSources()).use { stream ->
            stream.filter { it.name.endsWith(".kt") }.forEach { source ->
                val text = Files.readString(source)
                forbidden.forEach { api ->
                    assertTrue("${source.name} reaches for $api", !text.contains(api))
                }
            }
        }
    }

    /** Gradle runs a test with the module directory as its working directory. */
    private fun mainSources(): Path =
        Path.of("src/main/kotlin/dev/agentsdeck/sentry").toAbsolutePath().also {
            assertTrue("sources are not where this test looks: $it", Files.isDirectory(it))
        }
}
