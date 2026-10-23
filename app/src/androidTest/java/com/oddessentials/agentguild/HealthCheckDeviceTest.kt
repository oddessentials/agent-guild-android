package com.oddessentials.agentguild

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oddessentials.agentguild.model.Health
import com.oddessentials.agentguild.model.HealthProblem
import com.oddessentials.agentguild.model.HealthResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [Health.check] on the device itself, without the WebView, so it also runs on an API 29 image whose
 * WebView the app doesn't support.
 */
@RunWith(AndroidJUnit4::class)
class HealthCheckDeviceTest {
    private val server = FakeServer(47943)
    private val token = "0123456789abcdef0123456789abcdef"

    @After
    fun tearDown() = server.close()

    @Test
    fun readsTheAnswerOfASupportedManager() = runBlocking {
        server.actAsManager()
        assertEquals(HealthResult.Supported(Health.MINIMUM_VERSION), Health.check(server.origin, token) { true })
    }

    @Test
    fun readsTheAnswerToARejectedToken() = runBlocking {
        server.actAsManager { 401 }
        assertEquals(HealthResult.Failed(HealthProblem.TokenRejected), Health.check(server.origin, token) { true })
    }
}
