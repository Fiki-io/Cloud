package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.gecko.PinggyUrlExtractor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Cloud Shell", appName)
    }

    @Test
    fun `extract pinggy-free link and ignore dashboard promo link`() {
        val terminalOutput = """
            gardenxxxxx@cloudshell:~${'$'} ssh -p443 -R0:localhost:5901 -o stricthostkeychecking=no tcp@a.pinggy.io
            You are not authenticated.
            Your tunnel will expire in 60 minutes. Upgrade to Pinggy Pro to get unrestricted tunnels. https://dashboard.pinggy.io

             tcp://mklby-34-21-228-61.run.pinggy-free.link:40251
        """.trimIndent()

        val extracted = PinggyUrlExtractor.extractLatestPinggyUrl(terminalOutput)
        assertEquals("tcp://mklby-34-21-228-61.run.pinggy-free.link:40251", extracted)
    }
}
