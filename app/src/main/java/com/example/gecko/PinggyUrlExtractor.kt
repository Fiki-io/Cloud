package com.example.gecko

object PinggyUrlExtractor {

    private val ansiCsiRegex = Regex("\u001B\\[[0-9;?]*[a-zA-Z]")
    private val ansiOscRegex = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

    // Prioritas 1: URL Pinggy dengan skema tcp:// dan nomor port (standar tunnel VNC/TCP Pinggy)
    private val tcpSchemeRegex = Regex(
        pattern = "tcp://[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*\\.pinggy\\.(?:link|io):\\d+",
        option = RegexOption.IGNORE_CASE
    )

    // Prioritas 2: URL Pinggy dengan skema http:// atau https:// atau tcp:// tanpa/dengan port
    private val fullUrlRegex = Regex(
        pattern = "(?:tcp|https?)://[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*\\.pinggy\\.(?:link|io)(?::\\d+)?(?:/[^\\s\"'<>]*)?",
        option = RegexOption.IGNORE_CASE
    )

    // Prioritas 3: Host:Port Pinggy tanpa skema (bukan bagian dari tcp@a.pinggy.io)
    private val hostPortRegex = Regex(
        pattern = "(?<![@a-zA-Z0-9.-])[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*\\.pinggy\\.(?:link|io):\\d+",
        option = RegexOption.IGNORE_CASE
    )

    fun stripAnsi(input: String): String {
        if (input.isEmpty()) return ""
        return input
            .replace(ansiOscRegex, "")
            .replace(ansiCsiRegex, "")
            .replace("\r", "")
    }

    /**
     * Mengekstrak link tunnel Pinggy terbaru dari output terminal.
     * Mengabaikan argumen perintah 'tcp@a.pinggy.io' dan memprioritaskan endpoint TCP terbaru.
     */
    fun extractLatestPinggyUrl(rawText: String): String? {
        if (rawText.isBlank() || !rawText.contains("pinggy.", ignoreCase = true)) {
            return null
        }
        val cleaned = stripAnsi(rawText)

        val tcpMatches = tcpSchemeRegex.findAll(cleaned).toList()
        if (tcpMatches.isNotEmpty()) {
            return tcpMatches.last().value.trim()
        }

        val urlMatches = fullUrlRegex.findAll(cleaned)
            .map { it.value.trim().trimEnd('.', ',', ';', ')', ']') }
            .filter { !it.contains("@") && !it.endsWith("a.pinggy.io", ignoreCase = true) }
            .toList()
        if (urlMatches.isNotEmpty()) {
            return urlMatches.last()
        }

        val hostPortMatches = hostPortRegex.findAll(cleaned).toList()
        if (hostPortMatches.isNotEmpty()) {
            return "tcp://${hostPortMatches.last().value.trim()}"
        }

        return null
    }
}
