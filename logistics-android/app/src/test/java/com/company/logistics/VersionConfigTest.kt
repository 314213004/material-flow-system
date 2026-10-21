package com.company.logistics

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionConfigTest {

    @Test
    fun deliveryVersionIsCurrent() {
        assertEquals(56, BuildConfig.VERSION_CODE)
        assertEquals("0.5.49", BuildConfig.VERSION_NAME)
    }
}
