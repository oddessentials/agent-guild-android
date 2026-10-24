package com.oddessentials.agentguild.web

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvCheckTest {
    @Test
    fun aTelevisionIsATv() {
        assertTrue(TvCheck.isTv(Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES, fireTv = false))
    }

    @Test
    fun aFireTvIsATvWhateverItsUiMode() {
        assertTrue(TvCheck.isTv(Configuration.UI_MODE_TYPE_NORMAL, fireTv = true))
    }

    @Test
    fun phonesTabletsAndOtherModesAreNot() {
        for (type in listOf(
            Configuration.UI_MODE_TYPE_UNDEFINED,
            Configuration.UI_MODE_TYPE_NORMAL,
            Configuration.UI_MODE_TYPE_DESK,
            Configuration.UI_MODE_TYPE_CAR,
            Configuration.UI_MODE_TYPE_WATCH,
            Configuration.UI_MODE_TYPE_VR_HEADSET,
        )) {
            assertFalse(TvCheck.isTv(type or Configuration.UI_MODE_NIGHT_NO, fireTv = false))
        }
    }
}
