package com.oddessentials.agentguild.model

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {
    private fun supported(version: String) = HealthResult.Supported(version)
    private fun failed(problem: HealthProblem) = HealthResult.Failed(problem)

    @Test
    fun acceptsTheAndroidHostReleaseAndLater() {
        assertEquals(supported("1.9.0"), Health.evaluate(200, """{"ok":true,"name":"agent-guild","version":"1.9.0","pid":1}"""))
        assertEquals(supported("1.10.2"), Health.evaluate(200, """{"name":"agent-guild","version":"1.10.2"}"""))
        assertEquals(supported("2.0.0-beta.1"), Health.evaluate(200, """{"name":"agent-guild","version":"2.0.0-beta.1"}"""))
        assertEquals(supported("1.9.0-rc.1"), Health.evaluate(200, """{"name":"agent-guild","version":"1.9.0-rc.1"}"""))
    }

    @Test
    fun acceptsADevelopmentBuild() {
        assertEquals(supported("0.0.0-development"), Health.evaluate(200, """{"name":"agent-guild","version":"0.0.0-development"}"""))
    }

    @Test
    fun refusesAReleaseWithoutTheAndroidHost() {
        assertEquals(failed(HealthProblem.TooOld("1.8.0")), Health.evaluate(200, """{"name":"agent-guild","version":"1.8.0"}"""))
        assertEquals(failed(HealthProblem.TooOld("0.9.12")), Health.evaluate(200, """{"name":"agent-guild","version":"0.9.12"}"""))
        assertEquals(failed(HealthProblem.TooOld("next")), Health.evaluate(200, """{"name":"agent-guild","version":"next"}"""))
        assertEquals("This computer runs Agent Guild 1.8.0. Update it to 1.9.0 or later.", HealthProblem.TooOld("1.8.0").message)
    }

    @Test
    fun explainsFailures() {
        assertEquals(failed(HealthProblem.TokenRejected), Health.evaluate(401, ""))
        assertEquals(failed(HealthProblem.Forbidden), Health.evaluate(403, """{"error":"forbidden host"}"""))
        assertEquals(failed(HealthProblem.Unexpected(502)), Health.evaluate(502, ""))
        assertEquals(failed(HealthProblem.Unexpected(302)), Health.evaluate(302, ""))
        assertEquals(failed(HealthProblem.NotAgentGuild), Health.evaluate(200, """{"name":"jellyfin","version":"10.0.0"}"""))
        assertEquals(failed(HealthProblem.NotAgentGuild), Health.evaluate(200, "<html>"))
        assertEquals(failed(HealthProblem.NotAgentGuild), Health.evaluate(200, """{"name":"agent-guild"}"""))
        assertEquals(failed(HealthProblem.NotAgentGuild), Health.evaluate(200, """{"name":"agent-guild","version":190}"""))
        assertEquals(failed(HealthProblem.NotAgentGuild), Health.evaluate(200, """["agent-guild"]"""))
    }

    @Test
    fun checksTheInfoEndpoint() {
        assertEquals("https://mini.tail1234.ts.net:8443/api/v1/info", Health.infoUrl("https://mini.tail1234.ts.net:8443"))
        assertEquals(10_000L, Health.TIMEOUT_MILLIS)
    }

    @Test
    fun explainsNetworkErrors() {
        assertEquals(HealthProblem.Refused, Health.problem(ConnectException("refused"), online = true))
        assertEquals(HealthProblem.Unreachable, Health.problem(SocketTimeoutException(), online = true))
        assertEquals(HealthProblem.Unreachable, Health.problem(UnknownHostException(), online = true))
        assertEquals(HealthProblem.Unreachable, Health.problem(SSLHandshakeException("bad certificate"), online = true))
        assertEquals(HealthProblem.Unreachable, Health.problem(IOException(), online = true))
        assertEquals(HealthProblem.Offline, Health.problem(UnknownHostException(), online = false))
        assertEquals(HealthProblem.Offline, Health.problem(ConnectException(), online = false))
    }

    @Test
    fun readsLikeTheAppleApp() {
        assertEquals("The computer didn't accept this sign-in. Scan its current sign-in code.", HealthProblem.TokenRejected.message)
        assertEquals("This device is offline.", HealthProblem.Offline.message)
        assertEquals("Agent Guild answered with an unexpected error (502).", HealthProblem.Unexpected(502).message)
    }

    @Test
    fun offersSignInOnlyWhenScanningCanFixIt() {
        assertTrue(HealthProblem.TokenRejected.needsSignIn)
        assertTrue(HealthProblem.Refused.needsSignIn)
        val others = listOf(
            HealthProblem.Unreachable, HealthProblem.Offline, HealthProblem.Forbidden,
            HealthProblem.NotAgentGuild, HealthProblem.TooOld("1.8.0"), HealthProblem.Unexpected(500),
        )
        for (problem in others) assertFalse(problem.toString(), problem.needsSignIn)
    }

    @Test
    fun leavesThePageOnlyWhenScanningCanFixIt() {
        for (problem in listOf(HealthProblem.TokenRejected, HealthProblem.Refused)) {
            assertFalse(Recovery.showsPage(failed(problem), showing = true))
        }
        val others = listOf(
            HealthProblem.Unexpected(502), HealthProblem.Unreachable, HealthProblem.Offline,
            HealthProblem.Forbidden, HealthProblem.NotAgentGuild, HealthProblem.TooOld("1.8.0"),
        )
        for (problem in others) {
            assertTrue(Recovery.showsPage(failed(problem), showing = true))
            assertFalse(Recovery.showsPage(failed(problem), showing = false))
        }
        assertTrue(Recovery.showsPage(supported("1.9.0"), showing = false))
    }
}
