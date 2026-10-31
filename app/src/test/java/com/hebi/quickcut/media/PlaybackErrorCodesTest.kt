package com.hebi.quickcut.media

import org.junit.Assert.*
import org.junit.Test

class PlaybackErrorCodesTest {
    @Test fun decoderFailureUsesThePlaybackDomainRatherThanExportCodes() {
        assertEquals("ERROR_CODE_DECODING_FAILED", PlaybackErrorCodes.name(4003))
        assertEquals("ERROR_CODE_PARSING_MANIFEST_MALFORMED", PlaybackErrorCodes.name(3002))
    }

    @Test fun permissionAndReclaimedResourcesRemainActionable() {
        assertEquals("ERROR_CODE_IO_NO_PERMISSION", PlaybackErrorCodes.name(2006))
        assertEquals("ERROR_CODE_DECODING_RESOURCES_RECLAIMED", PlaybackErrorCodes.name(4006))
        assertEquals("ERROR_CODE_PERMISSION_DENIED", PlaybackErrorCodes.name(-4))
    }

    @Test fun unknownOrCustomNumericCodesCannotBeTreatedAsKnownFailures() {
        assertNull(PlaybackErrorCodes.name(98765))
        assertNull(PlaybackErrorCodes.name(-999))
        assertNull(PlaybackErrorCodes.name(Int.MAX_VALUE))
    }
}
