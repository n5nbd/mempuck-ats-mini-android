package com.n5nbd.mempuck.atsmini.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupReconnectStageTest {
    @Test
    fun radioSnapshotStartsWithOneContinuousStartupSplash() {
        val snapshot = RadioSnapshot()

        assertEquals(StartupReconnectStage.Idle, snapshot.startupReconnectStage)
        assertEquals(StartupReconnectOutcome.Pending, snapshot.startupReconnectOutcome)
    }
}
