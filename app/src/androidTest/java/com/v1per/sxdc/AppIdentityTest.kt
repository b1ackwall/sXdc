package com.v1per.sxdc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppIdentityTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun targetPackage_isDebugApplicationId() {
        assertEquals("com.v1per.sxdc.debug", instrumentation.targetContext.packageName)
    }

    @Test
    fun instrumentationPackage_endsWithTest() {
        val pkg = instrumentation.context.packageName
        assertTrue("was $pkg", pkg.endsWith(".test"))
    }

    @Test
    fun launcherLabel_isDevLabel() {
        val ctx = instrumentation.targetContext
        assertEquals("sXdc Dev", ctx.applicationInfo.loadLabel(ctx.packageManager).toString())
    }
}
