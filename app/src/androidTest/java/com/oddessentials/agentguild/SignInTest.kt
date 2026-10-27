package com.oddessentials.agentguild

import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oddessentials.agentguild.model.HealthProblem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Adding a computer from its sign-in link, against the scratch manager. */
@RunWith(AndroidJUnit4::class)
class SignInTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val harness = Harness(compose)

    @After
    fun tearDown() = harness.close()

    private fun startAdding() {
        harness.launch()
        compose.onNodeWithText("Add Computer").click()
    }

    /** Whether [saved], written out as Android writes it, holds [text]. */
    private fun holds(saved: Bundle, text: String): Boolean {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved)
            val bytes = parcel.marshall()
            return listOf(Charsets.UTF_8, Charsets.UTF_16LE).any { charset ->
                val part = text.toByteArray(charset)
                (0..bytes.size - part.size).any { i -> part.indices.all { bytes[i + it] == part[it] } }
            }
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun typedTokensAreNeverSaved() {
        val token = "0123456789abcdef".repeat(3)
        val link = "https://mini.tail1234.ts.net/#token=$token"
        val scenario = harness.launch()
        compose.onNodeWithText("Add Computer").click()
        compose.onNodeWithTag("address").performTextInput(link)
        compose.onNodeWithTag("link").performTextInput(link)
        compose.waitForIdle()

        val saved = Bundle()
        scenario.onActivity { InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(it, saved) }
        assertFalse(holds(saved, token))
    }

    @Test
    fun signingInAgainByCodeRefusesAnotherComputersAddressWithoutAskingIt() {
        FakeServer(0).use { other ->
            harness.add("https://mini.tail1234.ts.net/#token=${"0".repeat(48)}", "Studio")
            harness.launch()
            compose.waitUntil(10_000) { compose.onAllNodesWithTagCount("computer:Studio") == 1 }
            compose.onNodeWithContentDescription("More for Studio").click()
            compose.onNodeWithText("Sign In Again").click()
            compose.onNodeWithTag("address").performTextReplacement(other.origin)
            compose.onNodeWithTag("getCode").click()

            compose.waitUntil(10_000) { compose.onAllNodesWithTextCount("This address belongs to a different computer.") == 1 }
            assertEquals(emptyList<FakeServer.Request>(), other.requests.toList())
        }
    }

    @Test
    fun aPastedLinkAddsTheComputer() {
        startAdding()
        compose.onNodeWithTag("link").performTextInput(Harness.managerLink)
        compose.onNodeWithTag("name").performTextInput("Scratch")
        compose.onNodeWithTag("submit").click()

        compose.waitUntil(20_000) { compose.onAllNodesWithTagCount("computer:Scratch") == 1 }
        val saved = runBlocking { harness.app.store.current() }.single()
        assertEquals("Scratch", saved.name)
        assertEquals(Harness.managerAddress, saved.address)
        assertEquals(Harness.managerToken, saved.token)
    }

    @Test
    fun aRejectedTokenIsExplained() {
        startAdding()
        compose.onNodeWithTag("link").performTextInput("${Harness.managerAddress}/#token=${"0".repeat(48)}")
        compose.onNodeWithTag("submit").click()

        compose.waitUntil(20_000) { compose.onAllNodesWithTextCount(HealthProblem.TokenRejected.message) == 1 }
        assertEquals(emptyList<Any>(), runBlocking { harness.app.store.current() })
    }

    @Test
    fun textThatIsntASignInLinkCantBeAdded() {
        startAdding()
        compose.onNodeWithTag("link").performTextInput("${Harness.managerAddress}/?token=${Harness.managerToken}")
        compose.onNodeWithTag("submit").assertIsNotEnabled()
    }
}
