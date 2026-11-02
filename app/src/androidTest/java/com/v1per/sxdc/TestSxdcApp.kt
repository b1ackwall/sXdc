package com.v1per.sxdc

/**
 * The Application every instrumented test runs in (see [SxdcTestRunner]).
 *
 * - Its own database, [TEST_DATABASE_NAME], deleted at process start, so tests never read or
 *   write the install's real jobs (#20).
 * - Its own settings file, [TEST_SETTINGS_NAME], deleted at process start, so tests never see
 *   the install's destination folder or extra arguments, and never change them (#29).
 * - No startup recovery, which would otherwise resolve the real install's jobs (#17).
 * - No POST_NOTIFICATIONS prompt, whose system dialog could cover the screen under test (#25).
 *
 * Python start and channel creation still run in SxdcApp.onCreate; the screens and the service
 * need both.
 */
class TestSxdcApp : SxdcApp() {

    /** Set by the override below, which proves the production recovery pass was replaced. */
    @Volatile
    var recoveryOverrideRan = false
        private set

    override val databaseName: String get() = TEST_DATABASE_NAME

    override val settingsName: String get() = TEST_SETTINGS_NAME

    override val requestsNotificationPermission: Boolean get() = false

    override fun onCreate() {
        // Before super.onCreate, so nothing in this process can have opened it yet.
        deleteDatabase(TEST_DATABASE_NAME)
        deleteSharedPreferences(TEST_SETTINGS_NAME)
        super.onCreate()
    }

    override suspend fun runStartupRecovery() {
        recoveryOverrideRan = true
    }

    companion object {
        const val TEST_DATABASE_NAME = "sxdc-test.db"
        const val TEST_SETTINGS_NAME = "sxdc.settings.test"
    }
}
