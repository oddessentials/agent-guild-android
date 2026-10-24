package com.oddessentials.agentguild

import androidx.datastore.core.Serializer
import com.oddessentials.agentguild.data.LookSerializer
import com.oddessentials.agentguild.data.LookStore
import com.oddessentials.agentguild.model.Look
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** A look store in a file of its own, whose writes fail while [failing] is true. Close it after the test. */
class FailingLooks(app: AgentGuildApp) : AutoCloseable {
    private class Writes : Serializer<Map<String, Look>> by LookSerializer {
        @Volatile var failing = false

        override suspend fun writeTo(t: Map<String, Look>, output: OutputStream) {
            if (failing) throw IOException("disk full")
            LookSerializer.writeTo(t, output)
        }
    }

    private val writes = Writes()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val store = LookStore.create(File(app.cacheDir, "looks-${UUID.randomUUID()}.json"), scope, writes)

    var failing: Boolean
        get() = writes.failing
        set(value) {
            writes.failing = value
        }

    override fun close() = scope.cancel()
}
