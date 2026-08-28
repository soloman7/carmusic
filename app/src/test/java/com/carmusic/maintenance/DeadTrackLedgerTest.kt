package com.carmusic.maintenance

import org.junit.Assert.assertEquals
import org.junit.Test

class DeadTrackLedgerTest {

    @Test
    fun `first failure only pends and never deletes`() {
        val ledger = DeadTrackLedger()
        assertEquals(DeadTrackLedger.Decision.PENDING, ledger.onProbed("a", playable = false))
        assertEquals(setOf("a"), ledger.pendingAfterRun())
    }

    @Test
    fun `second consecutive failure deletes`() {
        val ledger = DeadTrackLedger(initialPending = setOf("a"))
        assertEquals(DeadTrackLedger.Decision.DELETE, ledger.onProbed("a", playable = false))
        // 已确认死链即出账，不再重复出现在挂账里
        assertEquals(emptySet<String>(), ledger.pendingAfterRun())
    }

    @Test
    fun `recovery clears pending`() {
        val ledger = DeadTrackLedger(initialPending = setOf("a"))
        assertEquals(DeadTrackLedger.Decision.KEEP, ledger.onProbed("a", playable = true))
        assertEquals(emptySet<String>(), ledger.pendingAfterRun())
    }

    @Test
    fun `alternate bad-good-bad never deletes`() {
        val ledger = DeadTrackLedger()
        assertEquals(DeadTrackLedger.Decision.PENDING, ledger.onProbed("a", false))
        assertEquals(DeadTrackLedger.Decision.KEEP, ledger.onProbed("a", true))
        // 平台抽风一次后恢复：下次失败重新从挂账开始，不判死
        assertEquals(DeadTrackLedger.Decision.PENDING, ledger.onProbed("a", false))
        assertEquals(DeadTrackLedger.Decision.KEEP, ledger.onProbed("a", true))
    }

    @Test
    fun `unprobed pending ids beyond probe limit are preserved`() {
        val ledger = DeadTrackLedger(initialPending = setOf("a", "b"))
        ledger.onProbed("a", false)   // 本轮只探到 a（b 超出 MAX_PROBE_PER_RUN）
        assertEquals(setOf("a", "b") - setOf("a"), ledger.pendingAfterRun())
    }

    @Test
    fun `different tracks are independent`() {
        val ledger = DeadTrackLedger(initialPending = setOf("a"))
        assertEquals(DeadTrackLedger.Decision.DELETE, ledger.onProbed("a", false))
        assertEquals(DeadTrackLedger.Decision.PENDING, ledger.onProbed("b", false))
        assertEquals(setOf("b"), ledger.pendingAfterRun())
    }
}
