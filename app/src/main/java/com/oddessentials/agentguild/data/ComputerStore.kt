package com.oddessentials.agentguild.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.oddessentials.agentguild.model.Computer
import com.oddessentials.agentguild.model.SignInLink
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** The saved computers, sealed as one file in DataStore. */
class ComputerStore(private val dataStore: DataStore<List<Computer>>, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    val computers: Flow<List<Computer>> = dataStore.data

    suspend fun current(): List<Computer> = computers.first()

    suspend fun add(link: SignInLink, name: String): Computer {
        lateinit var result: Computer
        dataStore.updateData { list -> Computers.add(list, link, name, newId).also { result = it.second }.first }
        return result
    }

    /** False when the computer was removed meanwhile. */
    suspend fun update(id: String, link: SignInLink): Boolean {
        var found = false
        dataStore.updateData { list -> Computers.update(list, id, link)?.also { found = true } ?: list }
        return found
    }

    suspend fun rename(id: String, name: String): Boolean {
        var found = false
        dataStore.updateData { list -> Computers.rename(list, id, name)?.also { found = true } ?: list }
        return found
    }

    suspend fun remove(id: String) {
        dataStore.updateData { list -> Computers.remove(list, id) }
    }

    companion object {
        /**
         * A file that can't be opened, such as one restored without its Keystore key, reads as an
         * empty list and is replaced.
         */
        fun create(file: File, sealer: Sealer, scope: CoroutineScope, newId: () -> String = { UUID.randomUUID().toString() }) =
            ComputerStore(
                DataStoreFactory.create(
                    serializer = SealedSerializer(sealer),
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyList() },
                    scope = scope,
                    produceFile = { file },
                ),
                newId,
            )
    }
}

internal class SealedSerializer(private val sealer: Sealer) : Serializer<List<Computer>> {
    override val defaultValue: List<Computer> = emptyList()

    override suspend fun readFrom(input: InputStream): List<Computer> {
        val sealed = input.readBytes()
        if (sealed.isEmpty()) return defaultValue
        val text = try {
            sealer.open(sealed).decodeToString()
        } catch (error: Exception) {
            throw CorruptionException("The saved computers can't be opened.", error)
        }
        return Computers.decode(text) ?: throw CorruptionException("The saved computers can't be read.")
    }

    override suspend fun writeTo(t: List<Computer>, output: OutputStream) {
        output.write(sealer.seal(Computers.encode(t).encodeToByteArray()))
    }
}
