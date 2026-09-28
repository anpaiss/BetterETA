package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Review 27/09 (Gemini): l'import dello storico e il ciclo del giro possono
 * lavorare insieme sugli stessi oggetti. Senza sincronizzazione il
 * salvataggio che scorre la HashMap della memoria tratti mentre l'altro
 * thread inserisce lanciava ConcurrentModificationException.
 */
class ConcurrencyTest {

    private fun race(a: () -> Unit, b: () -> Unit): Throwable? {
        val error = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val t1 = thread { start.await(); runCatching(a).onFailure { error.compareAndSet(null, it) } }
        val t2 = thread { start.await(); runCatching(b).onFailure { error.compareAndSet(null, it) } }
        start.countDown()
        t1.join(); t2.join()
        return error.get()
    }

    @Test(timeout = 60_000)
    fun `way memory survives saving while another thread records`() {
        // Senza tetto di voci: niente riordino a ogni inserimento, il test resta veloce
        val mem = WaySpeedMemory(maxEntries = 1_000_000)
        val writing = AtomicBoolean(true)
        val err = race(
            { try { for (i in 0 until 50_000) mem.record((i + 1) * 2L, 5.0, 1_000L + i, 100.0) } finally { writing.set(false) } },
            { while (writing.get()) { mem.serialize(); Thread.sleep(1) } },
        )
        assertNull("eccezione: $err", err)
        assertEquals(50_000, mem.size)
    }

    @Test(timeout = 60_000)
    fun `speed model survives learning from two threads`() {
        val model = AdaptiveSpeedModel()
        val err = race(
            { repeat(50_000) { model.update(5.0 + it % 3, (it % 20 - 10).toDouble(), Surface.PAVED) } },
            { repeat(50_000) { model.predict((it % 20 - 10).toDouble(), Surface.GRAVEL); if (it % 5_000 == 0) model.snapshot() } },
        )
        assertNull("eccezione: $err", err)
    }
}
