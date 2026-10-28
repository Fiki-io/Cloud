package com.example.gecko

object PinggyUrlExtractor {

    private val ansiCsiRegex = Regex("\u001B\\[[0-9;?]*[a-zA-Z]")
    private val ansiOscRegex = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

    private val ignoredHosts = setOf(
        "dashboard.pinggy.io",
        "www.pinggy.io",
        "pinggy.io",
        "a.pinggy.io",
        "docs.pinggy.io",
        "blog.pinggy.io"
    )

    // Prioritas 1: URL Pinggy dengan skema tcp:// dan nomor port (termasuk pinggy-free.link, pinggy.link, pinggy.io)
    private val tcpSchemeRegex = Regex(
        pattern = "tcp://[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*\\.pinggy(?:-[a-zA-Z0-9]+)?\\.(?:link|io|online):\\d+",
        option = RegexOption.IGNORE_CASE
    )

    // Prioritas 2: Host:Port Pinggy dengan nomor port tanpa skema (bukan bagian dari tcp@a.pinggy.io)
    private val hostPortRegex = Regex(
        pattern = "(?<![@a-zA-Z0-9.-])[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*\\.pinggy(?:-[a-zA-Z0-9]+)?\\.(?:link|io|online):\\d+",
        option = RegexOption.IGNORE_CASE
    )

    // Prioritas 3: URL Pinggy http/https (mengecualikan domain statis seperti dashboard.pinggy.io)
    private val fullUrlRegex = Regex(
        pattern = "(?:tcp|https?)://([a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*\\.pinggy(?:-[a-zA-Z0-9]+)?\\.(?:link|io|online))(?::\\d+)?",
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
     * Mendukung domain *.pinggy-free.link, *.pinggy.link, *.pinggy.io dan mengabaikan
     * link promosi 'https://dashboard.pinggy.io' maupun argumen 'tcp@a.pinggy.io'.
     */
    fun extractLatestPinggyUrl(rawText: String): String? {
        if (rawText.isBlank() || !rawText.contains("pinggy", ignoreCase = true)) {
            return null
        }
        val cleaned = stripAnsi(rawText)

        val tcpMatches = tcpSchemeRegex.findAll(cleaned).toList()
        if (tcpMatches.isNotEmpty()) {
            return tcpMatches.last().value.trim()
        }

        val hostPortMatches = hostPortRegex.findAll(cleaned).toList()
        if (hostPortMatches.isNotEmpty()) {
            return "tcp://${hostPortMatches.last().value.trim()}"
        }

        val urlMatches = fullUrlRegex.findAll(cleaned)
            .filter { matchResult ->
                val full = matchResult.value
                val host = matchResult.groupValues.getOrNull(1)?.lowercase() ?: ""
                !full.contains("@") && host !in ignoredHosts
            }
            .map { it.value.trim().trimEnd('.', ',', ';', ')', ']') }
            .toList()

        if (urlMatches.isNotEmpty()) {
            return urlMatches.last()
        }

        return null
    }
}
