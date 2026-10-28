package com.example

import com.example.gecko.PinggyUrlExtractor
import com.example.service.KeepAliveService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun extractPinggyUrl_extractsLatestTcpEndpointAndIgnoresCommandInput() {
        val rawTerminalOutput = """
            user@cloudshell:~${'$'} ssh -p 443 -R0:localhost:5901 -o StrictHostKeyChecking=no tcp@a.pinggy.io
            You are authenticated as Free user.
            Allocated port 41928 for remote forward to localhost:5901
            http://rnabc-103-10-20-30.a.free.pinggy.link
            https://rnabc-103-10-20-30.a.free.pinggy.link
            tcp://rnabc-103-10-20-30.a.free.pinggy.link:41928
        """.trimIndent()

        val extracted = PinggyUrlExtractor.extractLatestPinggyUrl(rawTerminalOutput)
        assertEquals("tcp://rnabc-103-10-20-30.a.free.pinggy.link:41928", extracted)
    }

    @Test
    fun extractPinggyUrl_stripsAnsiEscapeSequences() {
        val ansiOutput = "\u001B[32mtcp://rnxyz-11-22-33-44.a.free.pinggy.link:35091\u001B[0m\r\n"
        val extracted = PinggyUrlExtractor.extractLatestPinggyUrl(ansiOutput)
        assertEquals("tcp://rnxyz-11-22-33-44.a.free.pinggy.link:35091", extracted)
    }

    @Test
    fun extractPinggyUrl_ignoresSshCommandTargetWithoutTunnelOutput() {
        val commandOnly = "ssh -p 443 -R0:localhost:5901 -o StrictHostKeyChecking=no tcp@a.pinggy.io"
        val extracted = PinggyUrlExtractor.extractLatestPinggyUrl(commandOnly)
        assertNull(extracted)
    }

    @Test
    fun formatDuration_formatsHoursMinutesSeconds() {
        assertEquals("01:02:03", KeepAliveService.formatDuration(3723L))
    }
}
