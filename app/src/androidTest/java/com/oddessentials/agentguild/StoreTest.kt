package com.oddessentials.agentguild

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oddessentials.agentguild.data.AesGcmSealer
import com.oddessentials.agentguild.data.ComputerStore
import com.oddessentials.agentguild.data.LookStore
import com.oddessentials.agentguild.model.Look
import com.oddessentials.agentguild.model.Looks
import com.oddessentials.agentguild.model.SignInLink
import com.oddessentials.agentguild.model.Theme
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The saved computers in DataStore, sealed with a key from the Android Keystore, and the TV's looks beside them. */
@RunWith(AndroidJUnit4::class)
class StoreTest {
    private val app: AgentGuildApp = ApplicationProvider.getApplicationContext()
    private val folder = File(app.cacheDir, "store-test-${UUID.randomUUID()}")
    private val file = File(folder, "computers.sealed")
    private val alias = "store-test"
    private val token = "0123456789abcdef".repeat(3)

    @After
    fun tearDown() {
        folder.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    private suspend fun <T> withStore(block: suspend (ComputerStore) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            return block(ComputerStore.create(file, AesGcmSealer.keystore(alias), scope))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun theComputersSurviveSealed() = runBlocking {
        val link = SignInLink.parse("https://mini.tail1234.ts.net/#token=$token", allowLoopbackHttp = false)!!
        val saved = withStore { store ->
            val studio = store.add(link, "Studio Mac")
            assertTrue(store.rename(studio.id, "Mini"))
            store.current()
        }

        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(text.contains(token))
        assertFalse(text.contains("mini.tail1234.ts.net"))
        assertFalse(text.contains("Mini"))

        assertEquals(saved, withStore { it.current() })
        assertEquals("Mini", saved.single().name)
    }

    @Test
    fun theAppIsKeptOutOfBackups() {
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun theLooksSurviveAndAreReplacedAndRemoved() = runBlocking {
        val looksFile = File(folder, "looks.json")
        suspend fun <T> withLooks(block: suspend (LookStore) -> T): T {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                return block(LookStore.create(looksFile, scope))
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

        withLooks { looks ->
            looks.set("id1", Look("orbital", Theme.Dark))
            looks.set(Looks.LOOK_DEMO, Look(theme = Theme.Light))
            looks.set("id1", Look("grove"))
        }
        assertEquals(mapOf("id1" to Look("grove"), Looks.LOOK_DEMO to Look(theme = Theme.Light)), withLooks { it.current() })
        withLooks { it.remove("id1") }
        assertEquals(mapOf(Looks.LOOK_DEMO to Look(theme = Theme.Light)), withLooks { it.current() })
        assertEquals("""{"looks":{"demo":{"theme":"light"}}}""", looksFile.readText())
    }
}
