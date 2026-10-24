package com.oddessentials.agentguild

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oddessentials.agentguild.model.HealthProblem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
