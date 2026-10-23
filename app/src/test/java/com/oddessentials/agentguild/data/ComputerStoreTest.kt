package com.oddessentials.agentguild.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import com.oddessentials.agentguild.model.Computer
import com.oddessentials.agentguild.model.SignInLink
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store's changes over an in-memory DataStore, and the sealed file format. The file-backed
 * DataStore is tested on the emulator: on a Windows JVM it can't replace an existing file.
 */
class ComputerStoreTest {
    private class MemoryDataStore : DataStore<List<Computer>> {
        private val state = MutableStateFlow<List<Computer>>(emptyList())
        private val lock = Mutex()
        override val data: StateFlow<List<Computer>> = state

        override suspend fun updateData(transform: suspend (t: List<Computer>) -> List<Computer>) =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    private var ids = 0
    private val store = ComputerStore(MemoryDataStore()) { "id${++ids}" }
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun link(host: String, token: String) = SignInLink.parse("https://$host/#token=$token", allowLoopbackHttp = false)!!
    private val old = "a".repeat(32)
    private val new = "b".repeat(32)
    private val other = "c".repeat(32)

    @Test
    fun addsAComputerWithItsNameOrHostName() = runBlocking {
        val studio = store.add(link("mini.tail1234.ts.net:8443", old), "  Studio Mac ")
        val unnamed = store.add(link("workbench.tail1234.ts.net", other), "")
        assertEquals(Computer("id1", "Studio Mac", "https://mini.tail1234.ts.net:8443", old), studio)
        assertEquals("workbench", unnamed.name)
        assertEquals(listOf(studio, unnamed), store.current())
    }

    @Test
    fun addingAListedAddressReplacesItsToken() = runBlocking {
        val studio = store.add(link("mini.tail1234.ts.net", old), "Studio Mac")
        assertEquals(studio.copy(token = new), store.add(link("mini.tail1234.ts.net", new), ""))
        assertEquals(studio.copy(token = other, name = "Mini"), store.add(link("mini.tail1234.ts.net", other), "Mini"))
        assertEquals(1, store.current().size)
    }

    @Test
    fun signingInAgainKeepsTheEntryAndReplacesItsAddressAndToken() = runBlocking {
        val studio = store.add(link("mini.tail1234.ts.net:8443", old), "Studio Mac")
        val workbench = store.add(link("workbench.tail1234.ts.net", other), "Workbench")

        assertTrue(store.update(studio.id, link("mini.tail1234.ts.net", new)))

        assertEquals(listOf(Computer(studio.id, "Studio Mac", "https://mini.tail1234.ts.net", new), workbench), store.current())
    }

    @Test
    fun signingInToARemovedEntryChangesNothing() = runBlocking {
        val studio = store.add(link("mini.tail1234.ts.net", old), "Studio Mac")
        store.remove(studio.id)
        assertFalse(store.update(studio.id, link("mini.tail1234.ts.net", new)))
        assertEquals(emptyList<Computer>(), store.current())
    }

    @Test
    fun renamesAndRemoves() = runBlocking {
        val studio = store.add(link("mini.tail1234.ts.net", old), "Studio Mac")
        val workbench = store.add(link("workbench.tail1234.ts.net", other), "Workbench")
        assertTrue(store.rename(studio.id, " Mini "))
        assertFalse(store.rename(studio.id, "   "))
        assertFalse(store.rename("gone", "Mini"))
        assertEquals("Mini", store.current().first().name)
        store.remove(studio.id)
        assertEquals(listOf(workbench), store.current())
    }

    @Test
    fun theFileIsSealed() = runBlocking {
        val serializer = SealedSerializer(AesGcmSealer { key })
        val list = listOf(Computer("id1", "Studio Mac", "https://mini.tail1234.ts.net", old))
        val bytes = ByteArrayOutputStream().also { serializer.writeTo(list, it) }.toByteArray()

        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains(old))
        assertFalse(text.contains("mini.tail1234.ts.net"))
        assertFalse(text.contains("Studio Mac"))
        assertEquals(list, serializer.readFrom(ByteArrayInputStream(bytes)))
        assertEquals(emptyList<Computer>(), serializer.readFrom(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun aFileSealedWithAnotherKeyIsCorrupt() = runBlocking {
        val bytes = ByteArrayOutputStream().also { SealedSerializer(AesGcmSealer { key }).writeTo(emptyList(), it) }.toByteArray()
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val failure = runCatching { SealedSerializer(AesGcmSealer { otherKey }).readFrom(ByteArrayInputStream(bytes)) }.exceptionOrNull()
        assertTrue(failure is CorruptionException)
    }

    @Test
    fun theSealerRefusesChangedData() {
        val sealer = AesGcmSealer { key }
        val sealed = sealer.seal("secret".encodeToByteArray())
        assertEquals("secret", sealer.open(sealed).decodeToString())
        assertFalse(sealed.contentEquals(sealer.seal("secret".encodeToByteArray())))
        val changed = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertTrue(runCatching { sealer.open(changed) }.isFailure)
        assertTrue(runCatching { sealer.open(byteArrayOf(2) + sealed.copyOfRange(1, sealed.size)) }.isFailure)
    }

    @Test
    fun decodingSkipsIncompleteEntries() {
        val text = """{"computers":[{"id":"1","name":"A","address":"https://a.example","token":"t"},{"id":"2","name":"B","address":"file:///x","token":"t"},{"id":"3","name":"C"}]}"""
        assertEquals(listOf(Computer("1", "A", "https://a.example", "t")), Computers.decode(text))
        assertNull(Computers.decode("not json"))
        val list = listOf(Computer("1", "Studio \"Mac\"", "https://a.example", "t"))
        assertEquals(list, Computers.decode(Computers.encode(list)))
    }
}
