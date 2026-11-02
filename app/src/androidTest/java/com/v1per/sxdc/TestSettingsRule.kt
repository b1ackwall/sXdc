package com.v1per.sxdc

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Empties the app's settings before and after each test, so every test starts with no destination
 * folder and no extra arguments.
 *
 * Refuses to run unless the process is [TestSxdcApp], whose settings file is the test-only
 * [TestSxdcApp.TEST_SETTINGS_NAME]: under any other Application this would clear real settings.
 */
class TestSettingsRule : ExternalResource() {

    /** The test settings file, for tests that need to store a value SxdcSettings cannot write. */
    lateinit var prefs: SharedPreferences
        private set

    override fun before() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        check(app is TestSxdcApp) {
            "Instrumented tests must run under SxdcTestRunner; the application is ${app.javaClass.name}"
        }
        check(app.settingsName == TestSxdcApp.TEST_SETTINGS_NAME) { "Refusing to clear ${app.settingsName}" }
        prefs = app.getSharedPreferences(app.settingsName, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    override fun after() {
        prefs.edit().clear().commit()
    }
}
