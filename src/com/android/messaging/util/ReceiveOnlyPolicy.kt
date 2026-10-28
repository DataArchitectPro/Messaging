package com.android.messaging.util

/**
 * receive-only: single switch for everything that would send an SMS/MMS, place a call or use the
 * mobile network for MMS. The manifest also lacks SEND_SMS and CALL_PHONE, so even a bug here
 * cannot make the OS send a message. Keep every constant `false`.
 */
object ReceiveOnlyPolicy {
    const val SENDING_ENABLED = false
    const val CALLING_ENABLED = false
    const val MMS_DOWNLOAD_ENABLED = false

    const val LOG_PREFIX = "receive-only: blocked "
}
