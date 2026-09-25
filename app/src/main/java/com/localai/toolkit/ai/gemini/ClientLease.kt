package com.localai.toolkit.ai.gemini

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** A use-scoped reference. Closing the lease does not close other users' client. */
class ClientLease<T> internal constructor(
    val client: T,
    private val release: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

/**
 * Keeps one reusable option-keyed client. Replacing/clearing the cached entry
 * retires it; outstanding leases keep it alive until the last operation unwinds.
 * All ownership changes (including SDK close) are serialized under this lock.
 */
internal class LeasedClientCache<K, T>(
    private val create: (K) -> T,
    private val dispose: (T) -> Unit,
) {
    private class Entry<K, T>(val key: K, val client: T) {
        var users = 0
        var retired = false
        var disposed = false
    }

    private val lock = Any()
    private var cached: Entry<K, T>? = null

    fun acquire(key: K): ClientLease<T> = synchronized(lock) {
        val entry = cached?.takeIf { it.key == key } ?: run {
            retireCurrent()
            Entry(key, create(key)).also { cached = it }
        }
        entry.users++
        ClientLease(entry.client) {
            synchronized(lock) {
                check(entry.users > 0)
                entry.users--
                disposeIfUnused(entry)
            }
        }
    }

    fun clear() = synchronized(lock) { retireCurrent() }

    private fun retireCurrent() {
        val entry = cached ?: return
        // Forget first, even if native close throws: never hand out a closed client.
        cached = null
        entry.retired = true
        disposeIfUnused(entry)
    }

    private fun disposeIfUnused(entry: Entry<K, T>) {
        if (entry.retired && entry.users == 0 && !entry.disposed) {
            entry.disposed = true
            dispose(entry.client)
        }
    }
}
