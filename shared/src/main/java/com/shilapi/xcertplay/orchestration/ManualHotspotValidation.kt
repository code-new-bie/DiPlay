package com.shilapi.xcertplay.orchestration

enum class ManualHotspotValidationError {
    NAME_REQUIRED,
    NAME_TOO_LONG,
    INVALID_CHARACTER,
    PASSWORD_LENGTH,
}

/** Rules an existing (car) hotspot must meet before CarPlay can hand its credentials to the iPhone. */
object ManualHotspotValidation {
    /** Security implied by the password: the car hotspot UI only offers open or WPA2 networks. */
    fun securityFor(passphrase: String): ManualHotspotSecurity =
        if (passphrase.isEmpty()) ManualHotspotSecurity.OPEN else ManualHotspotSecurity.WPA2

    /** Returns a stable error code for the UI to localize, or null when the credentials can be used. */
    fun validate(ssid: String, passphrase: String): ManualHotspotValidationError? = when {
        ssid.isBlank() -> ManualHotspotValidationError.NAME_REQUIRED
        ssid.encodeToByteArray().size > 32 -> ManualHotspotValidationError.NAME_TOO_LONG
        '\u0000' in ssid || '\u0000' in passphrase -> ManualHotspotValidationError.INVALID_CHARACTER
        passphrase.isNotEmpty() && passphrase.length !in 8..63 -> ManualHotspotValidationError.PASSWORD_LENGTH
        else -> null
    }
}
