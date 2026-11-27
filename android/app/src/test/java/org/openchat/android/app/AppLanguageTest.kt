package org.openchat.android.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun defaultsToSystemLanguageWhenUnsetOrUnknown() {
        assertEquals(AppLanguage.Choice.SYSTEM, AppLanguage.resolve(null))
        assertEquals(AppLanguage.Choice.SYSTEM, AppLanguage.resolve(""))
        assertEquals(AppLanguage.Choice.SYSTEM, AppLanguage.resolve("UNKNOWN"))
    }

    @Test
    fun resolvesSavedExplicitLanguageChoice() {
        assertEquals(AppLanguage.Choice.EN, AppLanguage.resolve("EN"))
        assertEquals(AppLanguage.Choice.ZH, AppLanguage.resolve("ZH"))
        assertEquals(AppLanguage.Choice.SYSTEM, AppLanguage.resolve("SYSTEM"))
    }
}
