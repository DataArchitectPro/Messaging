package com.android.messaging.domain.conversation.usecase.telephony

import android.telephony.PhoneNumberUtils
import com.android.messaging.util.ReceiveOnlyPolicy
import javax.inject.Inject

internal fun interface CanPlacePhoneCall {
    operator fun invoke(destination: String?): Boolean
}

internal class CanPlacePhoneCallImpl @Inject constructor(
    private val isDeviceVoiceCapable: IsDeviceVoiceCapable,
    private val isEmergencyPhoneNumber: IsEmergencyPhoneNumber,
) : CanPlacePhoneCall {

    override operator fun invoke(destination: String?): Boolean {
        // receive-only: no screen may offer a call
        if (!ReceiveOnlyPolicy.CALLING_ENABLED) return false

        val phoneNumber = destination?.takeIf(String::isNotBlank) ?: return false

        return isDeviceVoiceCapable() &&
            PhoneNumberUtils.isWellFormedSmsAddress(phoneNumber) &&
            !isEmergencyPhoneNumber(phoneNumber)
    }
}
