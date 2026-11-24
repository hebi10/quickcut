package com.hebi.quickcut.batch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchLayoutPolicyTest {
    @Test fun limitedHeightUsesSingleScroll() {
        assertTrue(BatchLayoutPolicy.singleScroll(480f, 1f))
        assertTrue(BatchLayoutPolicy.singleScroll(579f, 1f))
        assertFalse(BatchLayoutPolicy.singleScroll(580f, 1f))
    }

    @Test fun largeFontUsesSingleScrollWithoutChangingSpaciousLayout() {
        assertTrue(BatchLayoutPolicy.singleScroll(780f, 1.6f))
        assertTrue(BatchLayoutPolicy.singleScroll(700f, 2f))
        assertFalse(BatchLayoutPolicy.singleScroll(800f, 1.4f))
        assertFalse(BatchLayoutPolicy.singleScroll(820f, 2f))
    }
}
