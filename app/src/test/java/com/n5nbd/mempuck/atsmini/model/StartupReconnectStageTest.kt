package com.n5nbd.mempuck.atsmini.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupReconnectStageTest {
    @Test
    fun radioSnapshotStartsWithoutReconnectSplash() {
        assertEquals(StartupReconnectStage.Idle, RadioSnapshot().startupReconnectStage)
    }
}
