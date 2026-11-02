package com.v1per.sxdc

import com.v1per.sxdc.download.DownloadPreconditions

/**
 * The Application every instrumented test runs in (see [SxdcTestRunner]).
 *
 * - Its own database, [TEST_DATABASE_NAME], deleted at process start, so tests never read or
 *   write the install's real jobs (#20).
 * - Its own settings file, [TEST_SETTINGS_NAME], deleted at process start, so tests never see
 *   the install's destination folder or extra arguments, and never change them (#29).
 * - Its own staging folder, [TEST_STAGING_DIR_NAME] inside the install's, emptied at process
 *   start, so a service run never sweeps the install's staged files (#24).
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

    override val stagingDirName: String get() = TEST_STAGING_DIR_NAME

    override val requestsNotificationPermission: Boolean get() = false

    override fun onCreate() {
        // Before super.onCreate, so nothing in this process can have opened it yet.
        deleteDatabase(TEST_DATABASE_NAME)
        deleteSharedPreferences(TEST_SETTINGS_NAME)
        val staging = DownloadPreconditions.outputDir(this)
        check(staging.name == TEST_STAGING_DIR_NAME) { "Refusing to clear $staging" }
        staging.deleteRecursively()
        super.onCreate()
    }

    override suspend fun runStartupRecovery() {
        recoveryOverrideRan = true
    }

    companion object {
        const val TEST_DATABASE_NAME = "sxdc-test.db"
        const val TEST_SETTINGS_NAME = "sxdc.settings.test"
        const val TEST_STAGING_DIR_NAME = "sxdc-test-staging"
    }
}
