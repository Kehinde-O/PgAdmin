package dev.pgapp.data

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConnectionManagerTest {
    @Test fun invalidProfileFailsWithoutNetwork() = runTest {
        val mgr = ConnectionManager(StandardTestDispatcher(testScheduler))
        val bad = ConnectionProfile(id = "1", name = "", host = "h", user = "u")
        val r = mgr.connect(bad, "pw")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is ConnectionFailure)
    }
}
