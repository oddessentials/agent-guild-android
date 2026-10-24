package com.oddessentials.agentguild.data

import androidx.datastore.core.Serializer
import com.oddessentials.agentguild.model.Look
import com.oddessentials.agentguild.model.Looks
import com.oddessentials.agentguild.model.Theme
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The looks in a real DataStore file. Each test writes the file once, so it never replaces one. */
class LookStoreTest {
    /** [LookSerializer], with writes that fail while [failing] is true. */
    private class Failing : Serializer<Map<String, Look>> by LookSerializer {
        @Volatile var failing = false

        override suspend fun writeTo(t: Map<String, Look>, output: OutputStream) {
            if (failing) throw IOException("disk full")
            LookSerializer.writeTo(t, output)
        }
    }

    private val folder = Files.createTempDirectory("looks").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
        folder.deleteRecursively()
    }

    @Test
    fun aFailedWriteKeepsTheSavedLook() = runBlocking {
        val serializer = Failing()
        val store = LookStore.create(File(folder, "looks.json"), scope, serializer)
        store.set("id1", Look("orbital", Theme.Dark))

        serializer.failing = true
        assertThrows(IOException::class.java) { runBlocking { store.set("id1", Look("grove", Theme.System)) } }
        assertThrows(IOException::class.java) { runBlocking { store.set(Looks.LOOK_DEMO, Look("guild")) } }

        assertEquals(mapOf("id1" to Look("orbital", Theme.Dark)), store.current())
        assertEquals(mapOf("id1" to Look("orbital", Theme.Dark)), Looks.decode(File(folder, "looks.json").readText()))
    }

    @Test
    fun anUnreadableFileReadsAsNoLooks() = runBlocking {
        val file = File(folder, "looks.json").apply { writeText("not json") }
        val store = LookStore.create(file, scope)
        assertEquals(emptyMap<String, Look>(), store.current())
    }

    @Test
    fun theFileHoldsTheLooksAsJson() = runBlocking {
        val looks = mapOf("id1" to Look("orbital", Theme.Dark), Looks.LOOK_DEMO to Look(theme = Theme.Light))
        val output = ByteArrayOutputStream()
        LookSerializer.writeTo(looks, output)
        assertEquals("""{"looks":{"id1":{"skin":"orbital","theme":"dark"},"demo":{"theme":"light"}}}""", output.toString(Charsets.UTF_8))
        assertEquals(looks, LookSerializer.readFrom(ByteArrayInputStream(output.toByteArray())))
        assertEquals(emptyMap<String, Look>(), LookSerializer.readFrom(InputStream.nullInputStream()))
    }
}
