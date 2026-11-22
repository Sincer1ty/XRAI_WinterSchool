package com.rokid.renewcxrlsample.script

import com.rokid.renewcxrlsample.presentation.HintServerClient
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkTimeoutValidationTest {
    @Test
    fun analysisClientRejectsDisabledOrNegativeTimeouts() {
        for (invalid in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                ScriptAnalysisClient(connectTimeoutMs = invalid)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ScriptAnalysisClient(readTimeoutMs = invalid)
            }
        }
    }

    @Test
    fun hintClientRejectsDisabledOrNegativeTimeouts() {
        for (invalid in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                HintServerClient(connectTimeoutMs = invalid)
            }
            assertThrows(IllegalArgumentException::class.java) {
                HintServerClient(readTimeoutMs = invalid)
            }
        }
    }
}
