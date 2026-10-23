package com.oddessentials.agentguild.model

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadBodyTest {
    /** Hands out at most [chunk] bytes per read, as a network stream may. */
    private class Trickle(private val bytes: ByteArray, private val chunk: Int) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xff else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == bytes.size) return -1
            val count = minOf(chunk, length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun readsAnEmptyBody() {
        assertArrayEquals(ByteArray(0), Health.readBody(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun readsToTheEndOfAShortBody() {
        val body = """{"name":"agent-guild","version":"1.9.0"}""".encodeToByteArray()
        assertArrayEquals(body, Health.readBody(ByteArrayInputStream(body)))
    }

    @Test
    fun putsTogetherPartialReads() {
        val body = bytes(10_000)
        assertArrayEquals(body, Health.readBody(Trickle(body, chunk = 1), limit = 64 * 1024))
        assertArrayEquals(body, Health.readBody(Trickle(body, chunk = 7), limit = 64 * 1024))
    }

    @Test
    fun readsABodyOfExactlyTheLimit() {
        val body = bytes(Health.MAX_BODY)
        assertArrayEquals(body, Health.readBody(Trickle(body, chunk = 4096)))
    }

    @Test
    fun refusesABodyOverTheLimit() {
        assertNull(Health.readBody(Trickle(bytes(Health.MAX_BODY + 1), chunk = 4096)))
        assertNull(Health.readBody(Trickle(bytes(Health.MAX_BODY * 3), chunk = 1000)))
        assertNull(Health.readBody(Trickle(bytes(11), chunk = 3), limit = 10))
    }
}
