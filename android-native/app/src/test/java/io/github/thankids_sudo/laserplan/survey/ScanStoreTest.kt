package io.github.thankids_sudo.laserplan.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanStoreTest {
    @Test fun idsAreValidated() {
        assertTrue(ScanStore.isSafeId("pmhx3k2")); assertTrue(ScanStore.isSafeId("s20261114-130634"))
        assertFalse(ScanStore.isSafeId("../x")); assertFalse(ScanStore.isSafeId("a/b")); assertFalse(ScanStore.isSafeId(""))
        assertFalse(ScanStore.isSafeId(null)); assertFalse(ScanStore.isSafeId("x".repeat(65)))
    }

    @Test fun eventsAreDeliveredOnceInOrder() {
        ScanEvents.post("a"); ScanEvents.post("b")
        val got = mutableListOf<String>()
        ScanEvents.drain { got += it }
        ScanEvents.drain { got += it }
        assertEquals(listOf("a", "b"), got)
    }
}
