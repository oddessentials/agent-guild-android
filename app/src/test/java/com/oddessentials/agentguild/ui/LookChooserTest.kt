package com.oddessentials.agentguild.ui

import com.oddessentials.agentguild.model.Look
import com.oddessentials.agentguild.model.PageLook
import com.oddessentials.agentguild.model.Skin
import com.oddessentials.agentguild.model.Theme
import com.oddessentials.agentguild.web.LookPage
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The panel's rules over a page whose answers the test delivers, late or never. */
@OptIn(ExperimentalCoroutinesApi::class)
class LookChooserTest {
    private class FakePage : LookPage {
        override var loads = 1
        override var loaded = true
        val reads = mutableListOf<Pair<Int, (PageLook?) -> Unit>>()
        val previews = mutableListOf<Triple<Int, String?, Theme>>()
        val restores = mutableListOf<Triple<Int, String?, Theme?>>()

        override fun readLook(load: Int, result: (PageLook?) -> Unit) {
            reads += load to result
        }

        override fun preview(load: Int, skin: String?, theme: Theme) {
            previews += Triple(load, skin, theme)
        }

        override fun restore(load: Int, skin: String?, theme: Theme?) {
            restores += Triple(load, skin, theme)
        }

        /** The page starts a new document, as a reload or a new renderer does. */
        fun newDocument() {
            loads++
        }
    }

    private val skins = listOf(Skin("professional", "Professional"), Skin("orbital", "Orbital"), Skin("grove", "Grove"))
    private val shown = PageLook(skins, "grove", Theme.Light)
    private val page = FakePage()
    private val scope = TestScope()
    private val saves = mutableListOf<Look>()
    private var write: CompletableDeferred<Unit>? = null
    private var closings = 0
    private val chooser = LookChooser(page, scope, closed = { closings++ }) { look ->
        write?.await()
        saves += look
    }

    private fun answer(index: Int, look: PageLook? = shown) = page.reads[index].second(look)

    private fun opened(saved: Look = Look("grove", Theme.Light)) {
        chooser.open(saved)
        answer(page.reads.lastIndex)
        assertTrue(chooser.isOpen)
    }

    @Test
    fun opensWithWhatThePageShowsAndTheSavedTheme() {
        chooser.open(Look("grove", Theme.System))
        assertFalse(chooser.isOpen)
        answer(0)
        assertTrue(chooser.isOpen)
        assertEquals("grove", chooser.skin)
        assertEquals(Theme.System, chooser.theme)
        assertEquals(skins, chooser.page!!.skins)
    }

    @Test
    fun anAnswerFromAnEndedDocumentIsDropped() {
        chooser.open(Look.Default)
        page.newDocument()
        chooser.loadChanged(page.loads)
        answer(0)
        assertFalse(chooser.isOpen)
        assertTrue(page.previews.isEmpty())
    }

    @Test
    fun anAnswerFromAnEndedDocumentIsDroppedEvenWithoutTheChangeReported() {
        chooser.open(Look.Default)
        page.newDocument()
        answer(0)
        assertFalse(chooser.isOpen)
    }

    @Test
    fun onlyTheNewDocumentsAnswerOpensThePanel() {
        chooser.open(Look.Default)
        page.newDocument()
        chooser.loadChanged(page.loads)
        chooser.open(Look.Default)
        assertEquals(listOf(1, 2), page.reads.map { it.first })

        answer(0, PageLook(skins, "orbital", Theme.Dark))
        assertFalse(chooser.isOpen)
        answer(1)
        assertTrue(chooser.isOpen)
        assertEquals("grove", chooser.skin)
    }

    @Test
    fun noAnswerOrANotLoadedPageOpensNothing() {
        chooser.open(Look.Default)
        answer(0, null)
        assertFalse(chooser.isOpen)
        page.loaded = false
        chooser.open(Look.Default)
        assertEquals(1, page.reads.size)
    }

    @Test
    fun previewsGoToTheDocumentThePanelOpenedOn() {
        opened()
        chooser.choose("orbital", Theme.Dark)
        assertEquals(Triple(1, "orbital", Theme.Dark), page.previews.single())
        assertEquals("orbital", chooser.skin)
        assertEquals(Theme.Dark, chooser.theme)
    }

    @Test
    fun aNewDocumentClosesThePanelAndLaterChoicesDoNothing() {
        opened()
        page.newDocument()
        chooser.loadChanged(page.loads)
        assertFalse(chooser.isOpen)
        chooser.choose("orbital", Theme.Dark)
        chooser.cancel()
        chooser.save()
        scope.advanceUntilIdle()
        assertTrue(page.previews.isEmpty())
        assertTrue(page.restores.isEmpty())
        assertTrue(saves.isEmpty())
    }

    @Test
    fun cancelRestoresWhatThePageShowedAndPreviewsNothing() {
        opened()
        chooser.choose("orbital", Theme.Dark)
        chooser.cancel()
        assertFalse(chooser.isOpen)
        assertEquals(listOf(Triple(1, "grove", Theme.Light)), page.restores)
        assertEquals(listOf(Triple(1, "orbital", Theme.Dark)), page.previews)
    }

    @Test
    fun cancelRestoresAShownSkinTheListLeftOut() {
        chooser.open(Look.Default)
        answer(0, PageLook(skins, null, Theme.Dark, "vapor"))
        chooser.choose("orbital", Theme.Light)
        chooser.cancel()
        assertEquals(listOf(Triple(1, "vapor", Theme.Dark)), page.restores)
    }

    @Test
    fun cancelLeavesMissingAttributesMissing() {
        chooser.open(Look.Default)
        answer(0, PageLook(skins, null, null, null))
        chooser.choose("orbital", Theme.Light)
        chooser.cancel()
        assertEquals(listOf(Triple(1, null, null)), page.restores)
    }

    @Test
    fun saveClosesOnlyOnceTheWriteSucceeded() {
        opened()
        chooser.choose("orbital", Theme.System)
        write = CompletableDeferred()
        chooser.save()
        scope.advanceUntilIdle()
        assertTrue(chooser.isOpen)
        assertTrue(chooser.saving)

        // While saving, Cancel and further choices wait.
        chooser.cancel()
        chooser.choose("grove", Theme.Dark)
        assertTrue(chooser.isOpen)
        assertEquals(1, page.previews.size)

        write!!.complete(Unit)
        scope.advanceUntilIdle()
        assertFalse(chooser.isOpen)
        assertFalse(chooser.saving)
        assertEquals(listOf(Look("orbital", Theme.System)), saves)
    }

    @Test
    fun aSecondAnswerForTheSameDocumentChangesNothing() {
        chooser.open(Look.Default)
        chooser.open(Look.Default)
        answer(0)
        chooser.choose("orbital", Theme.Dark)
        answer(1, PageLook(skins, "professional", Theme.Light))
        assertTrue(chooser.isOpen)
        assertEquals("orbital", chooser.skin)
        assertEquals(shown, chooser.page)
    }

    @Test
    fun aFailedSaveKeepsThePanelOpenAndSaysSo() {
        opened()
        chooser.choose("orbital", Theme.Dark)
        write = CompletableDeferred<Unit>().apply { completeExceptionally(IOException("disk full")) }
        chooser.save()
        scope.advanceUntilIdle()
        assertTrue(chooser.isOpen)
        assertTrue(chooser.failed)
        assertFalse(chooser.saving)
        assertTrue(saves.isEmpty())

        // A new choice clears the message, and trying again can succeed.
        chooser.choose("orbital", Theme.Light)
        assertFalse(chooser.failed)
        write = null
        chooser.save()
        scope.advanceUntilIdle()
        assertFalse(chooser.isOpen)
        assertEquals(listOf(Look("orbital", Theme.Light)), saves)
    }

    @Test
    fun aFailedSaveForAnEndedDocumentSaysNothing() {
        opened()
        write = CompletableDeferred()
        chooser.save()
        page.newDocument()
        chooser.loadChanged(page.loads)
        write!!.completeExceptionally(IOException("disk full"))
        scope.advanceUntilIdle()
        assertFalse(chooser.isOpen)
        assertFalse(chooser.failed)
        assertNull(chooser.page)
    }

    @Test
    fun cancelAndASuccessfulSaveReportTheClosing() {
        opened()
        chooser.cancel()
        assertEquals(1, closings)
        opened()
        chooser.save()
        scope.advanceUntilIdle()
        assertEquals(2, closings)
    }

    @Test
    fun aFailedSaveOrANewDocumentReportsNoClosing() {
        opened()
        write = CompletableDeferred<Unit>().apply { completeExceptionally(IOException("disk full")) }
        chooser.save()
        scope.advanceUntilIdle()
        assertTrue(chooser.failed)
        page.newDocument()
        chooser.loadChanged(page.loads)
        assertFalse(chooser.isOpen)
        assertEquals(0, closings)
    }
}
