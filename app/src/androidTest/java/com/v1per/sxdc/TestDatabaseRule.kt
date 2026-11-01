package com.v1per.sxdc

import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.data.SxdcDatabase
import org.junit.rules.ExternalResource

/**
 * Empties the app's database before each test, so every screen and service test starts from an
 * empty queue and history.
 *
 * Refuses to run unless the process is [TestSxdcApp], whose database is the test-only
 * [TestSxdcApp.TEST_DATABASE_NAME]: under any other Application this would clear real jobs.
 */
class TestDatabaseRule : ExternalResource() {

    override fun before() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        check(app is TestSxdcApp) {
            "Instrumented tests must run under SxdcTestRunner; the application is ${app.javaClass.name}"
        }
        val db = SxdcDatabase.get(app)
        check(db.openHelper.databaseName == TestSxdcApp.TEST_DATABASE_NAME) {
            "Refusing to clear ${db.openHelper.databaseName}"
        }
        db.clearAllTables()
    }
}
