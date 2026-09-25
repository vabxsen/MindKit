package com.localai.toolkit.ai.gemini

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LeasedClientCacheTest {
    private class Client(val key: String) {
        var closes = 0
        var closeFailure: Exception? = null
        fun close() { closes++; closeFailure?.let { throw it } }
    }
    private val created = mutableListOf<Client>()
    private val cache = LeasedClientCache<String, Client>(
        create = { Client(it).also(created::add) }, dispose = Client::close,
    )

    @Test fun `same options reuse a warm client and repeated lease close is harmless`() {
        val first = cache.acquire("detailed")
        val second = cache.acquire("detailed")
        assertThat(first.client).isSameInstanceAs(second.client)
        first.close()
        first.close()
        second.close()
        assertThat(first.client.closes).isEqualTo(0)
        cache.acquire("detailed").use { assertThat(it.client).isSameInstanceAs(first.client) }
        cache.clear()
        cache.clear()
        assertThat(first.client.closes).isEqualTo(1)
    }

    @Test fun `default-option check retires but cannot close custom inference client`() {
        val inference = cache.acquire("detailed")
        val check = cache.acquire("default")
        assertThat(inference.client.closes).isEqualTo(0)
        check.close()
        assertThat(check.client.closes).isEqualTo(0)
        inference.close()
        assertThat(inference.client.closes).isEqualTo(1)
        cache.clear()
        assertThat(check.client.closes).isEqualTo(1)
    }

    @Test fun `retired client closes only after every outstanding user exits`() {
        val inference = cache.acquire("custom")
        val download = cache.acquire("custom")
        cache.clear()
        inference.close()
        assertThat(download.client.closes).isEqualTo(0)
        val replacement = cache.acquire("custom")
        assertThat(replacement.client).isNotSameInstanceAs(download.client)
        download.close()
        assertThat(download.client.closes).isEqualTo(1)
        assertThat(replacement.client.closes).isEqualTo(0)
        replacement.close()
        cache.clear()
        assertThat(replacement.client.closes).isEqualTo(1)
    }

    @Test fun `option changes dispose idle entries immediately`() {
        val first = cache.acquire("one").also { it.close() }
        val second = cache.acquire("two")
        assertThat(first.client.closes).isEqualTo(1)
        second.close()
        cache.clear()
    }

    @Test fun `failed factory leaves no partially cached client and does not close active old one`() {
        var fail = false
        val factory = LeasedClientCache<String, Client>(
            create = { if (fail) error("factory failed") else Client(it) }, dispose = Client::close,
        )
        val previous = factory.acquire("previous")
        fail = true
        assertThrows(IllegalStateException::class.java) { factory.acquire("new") }
        assertThat(previous.client.closes).isEqualTo(0)
        fail = false
        factory.acquire("new").close()
        previous.close()
        assertThat(previous.client.closes).isEqualTo(1)
        factory.clear()
    }

    @Test fun `close failure cannot leave a disposed handle cached`() {
        val first = cache.acquire("same")
        first.client.closeFailure = IllegalStateException("close failed")
        first.close()
        assertThrows(IllegalStateException::class.java) { cache.clear() }
        val replacement = cache.acquire("same")
        assertThat(replacement.client).isNotSameInstanceAs(first.client)
        assertThat(first.client.closes).isEqualTo(1)
        replacement.close()
        cache.clear()
    }

    @Test fun `use preserves a primary failure if retired client close also throws`() {
        val lease = cache.acquire("one")
        val primary = IllegalArgumentException("inference failed")
        val cleanup = IllegalStateException("close failed")
        lease.client.closeFailure = cleanup
        cache.clear()
        val caught = assertThrows(IllegalArgumentException::class.java) {
            lease.use { throw primary }
        }
        assertThat(caught).isSameInstanceAs(primary)
        assertThat(caught.suppressed).asList().containsExactly(cleanup)
        lease.close()
        assertThat(lease.client.closes).isEqualTo(1)
    }

    @Test fun `coroutine cancellation releases a retired lease`() = runTest {
        val operation = launch { cache.acquire("one").use { awaitCancellation() } }
        runCurrent()
        val active = created.single()
        cache.clear()
        assertThat(active.closes).isEqualTo(0)
        operation.cancelAndJoin()
        assertThat(active.closes).isEqualTo(1)
    }

    @Test fun `concurrent clear and lease release close each client exactly once`() {
        val executor = Executors.newFixedThreadPool(3)
        try {
            repeat(50) {
                val one = cache.acquire("same")
                val two = cache.acquire("same")
                val start = CountDownLatch(1)
                val jobs = listOf(
                    executor.submit { start.await(); one.close() },
                    executor.submit { start.await(); two.close() },
                    executor.submit { start.await(); cache.clear() },
                )
                start.countDown()
                jobs.forEach { it.get(5, TimeUnit.SECONDS) }
                assertThat(one.client.closes).isEqualTo(1)
            }
        } finally {
            executor.shutdownNow()
            cache.clear()
        }
    }
}
