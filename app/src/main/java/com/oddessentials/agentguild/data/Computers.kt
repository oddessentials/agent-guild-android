package com.oddessentials.agentguild.data

import com.oddessentials.agentguild.model.Computer
import com.oddessentials.agentguild.model.Json
import com.oddessentials.agentguild.model.Origin
import com.oddessentials.agentguild.model.SignInLink

/** The changes to the list of computers, apart from where it is kept. */
object Computers {
    /** Signing in to an address already listed replaces its token, and its name when one is given. */
    fun add(list: List<Computer>, link: SignInLink, name: String, newId: () -> String): Pair<List<Computer>, Computer> {
        val trimmed = name.trim()
        val index = list.indexOfFirst { it.address == link.address }
        if (index >= 0) {
            val updated = list[index].copy(token = link.token, name = trimmed.ifEmpty { list[index].name })
            return list.toMutableList().apply { set(index, updated) } to updated
        }
        val added = Computer(newId(), trimmed.ifEmpty { link.defaultName }, link.address, link.token)
        return list + added to added
    }

    /** Signing in again keeps the entry and its name, and replaces its address and token. Null when it was removed. */
    fun update(list: List<Computer>, id: String, link: SignInLink): List<Computer>? {
        if (list.none { it.id == id }) return null
        return list.map { if (it.id == id) it.copy(address = link.address, token = link.token) else it }
    }

    fun rename(list: List<Computer>, id: String, name: String): List<Computer>? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || list.none { it.id == id }) return null
        return list.map { if (it.id == id) it.copy(name = trimmed) else it }
    }

    fun remove(list: List<Computer>, id: String) = list.filterNot { it.id == id }

    fun encode(list: List<Computer>): String = list.joinToString(",", "{\"computers\":[", "]}") {
        "{\"id\":${Json.quote(it.id)},\"name\":${Json.quote(it.name)},\"address\":${Json.quote(it.address)},\"token\":${Json.quote(it.token)}}"
    }

    /** Leaves out an entry that isn't complete or whose address isn't a web origin. */
    fun decode(text: String): List<Computer>? {
        val root = Json.parseOrNull(text) as? Map<*, *> ?: return null
        val entries = root["computers"] as? List<*> ?: return null
        return entries.mapNotNull { entry ->
            val fields = entry as? Map<*, *> ?: return@mapNotNull null
            val id = fields["id"] as? String
            val name = fields["name"] as? String
            val address = fields["address"] as? String
            val token = fields["token"] as? String
            if (id == null || name == null || address == null || token == null || Origin.parse(address) == null) null
            else Computer(id, name, address, token)
        }
    }
}
