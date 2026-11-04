package com.r34v.r34v

import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.LibraryStore
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Existing-user UI fixtures start past onboarding; dedicated tutorial tests exercise first use. */
class ExistingUserRule : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as R34Application
            val prefs = app.library.prefs
            val key = LibraryStore.SITE_SWITCH_TUTORIAL_SEEN
            val previous = if (prefs.contains(key)) prefs.getBoolean(key, true) else null
            prefs.edit().putBoolean(key, true).commit()
            try { base.evaluate() } finally {
                prefs.edit().apply { if (previous == null) remove(key) else putBoolean(key, previous) }.commit()
            }
        }
    }
}
