package com.android.messaging.util

import org.junit.Assert.assertFalse
import org.junit.Test

class ReceiveOnlyPolicyTest {
    @Test
    fun sendingCallingAndMmsDownloadAreDisabled() {
        assertFalse(ReceiveOnlyPolicy.SENDING_ENABLED)
        assertFalse(ReceiveOnlyPolicy.CALLING_ENABLED)
        assertFalse(ReceiveOnlyPolicy.MMS_DOWNLOAD_ENABLED)
    }
}
