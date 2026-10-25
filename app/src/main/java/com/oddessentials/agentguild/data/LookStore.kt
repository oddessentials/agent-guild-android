package com.oddessentials.agentguild.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.oddessentials.agentguild.model.Look
import com.oddessentials.agentguild.model.Looks
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Each TV page's [Look], keyed by computer id or [Looks.LOOK_DEMO], in a DataStore file of its own.
 * A look holds no secret, so it stays out of the sealed list of computers.
 */
class LookStore(private val dataStore: DataStore<Map<String, Look>>) {
    val looks: Flow<Map<String, Look>> = dataStore.data

    suspend fun current(): Map<String, Look> = looks.first()

    /** Throws when the write fails; the saved looks are then unchanged. */
    suspend fun set(key: String, look: Look) {
        dataStore.updateData { it + (key to look) }
    }

    suspend fun remove(key: String) {
        dataStore.updateData { if (key in it) it - key else it }
    }

    companion object {
        fun create(file: File, scope: CoroutineScope, serializer: Serializer<Map<String, Look>> = LookSerializer) = LookStore(
            DataStoreFactory.create(
                serializer = serializer,
                corruptionHandler = ReplaceFileCorruptionHandler { emptyMap() },
                scope = scope,
                produceFile = { file },
            ),
        )
    }
}

object LookSerializer : Serializer<Map<String, Look>> {
    override val defaultValue: Map<String, Look> = emptyMap()

    override suspend fun readFrom(input: InputStream): Map<String, Look> {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return defaultValue
        return Looks.decode(bytes.decodeToString()) ?: throw CorruptionException("The saved looks can't be read.")
    }

    override suspend fun writeTo(t: Map<String, Look>, output: OutputStream) {
        output.write(Looks.encode(t).encodeToByteArray())
    }
}
