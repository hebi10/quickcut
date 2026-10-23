package com.hebi.quickcut.media

/** Android 10's OmxInfoBuilder omits softwareOnly for the platform OMX.google codecs.
 * Retain Media3's classification everywhere else, and require both other platform flags
 * to agree before applying this compatibility rule to an already eligible decoder. */
internal fun isSoftwareVideoDecoder(
    sdkInt: Int,
    name: String,
    softwareOnly: Boolean,
    hardwareAccelerated: Boolean,
    vendor: Boolean,
): Boolean = softwareOnly || (
    sdkInt == 29 && name.startsWith("OMX.google.") && !hardwareAccelerated && !vendor
)
