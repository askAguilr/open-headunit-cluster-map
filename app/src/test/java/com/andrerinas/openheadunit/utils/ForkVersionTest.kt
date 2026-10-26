package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class ForkVersionTest {
    @Test fun `fork suffix preserves alpha numbering for future update checks`() {
        val installed = AppVersion.parse(BuildConfig.VERSION_NAME)!!
        val nextAlpha = AppVersion.parse("${installed.major}.${installed.minor}.${installed.patch}-alpha${installed.preReleaseNumber + 1}-clustermap")!!
        assertEquals(0, installed.preReleaseStage)
        assertTrue(installed.preReleaseNumber >= 1)
        assertTrue(nextAlpha > installed)
    }
}
