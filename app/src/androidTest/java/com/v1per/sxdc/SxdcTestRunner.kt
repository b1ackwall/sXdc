package com.v1per.sxdc

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Runs every instrumented test inside [TestSxdcApp] instead of the manifest's SxdcApp, so the
 * tests get their own database, skip startup recovery and never see the permission prompt.
 * Wired in app/build.gradle.kts as testInstrumentationRunner.
 */
class SxdcTestRunner : AndroidJUnitRunner() {

    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestSxdcApp::class.java.name, context)
}
