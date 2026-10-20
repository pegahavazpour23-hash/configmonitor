package app.configmonitor.core

/** Parses "google.com", "google.com:443", "https://google.com/x", "1.1.1.1:53", "[::1]:443". */
object AddressParser {
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /** Returns host to port, or null if the text is not a usable address. */
    fun parse(input: String): Pair<String, Int>? {
        var s = input.trim()
        if (s.isEmpty()) return null
        s = s.replace(scheme, "")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        if (s.isEmpty()) return null

        var host: String
        var port = 443
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            host = s.substring(1, end)
            val rest = s.substring(end + 1)
            if (rest.startsWith(":")) {
                port = rest.substring(1).toIntOrNull() ?: return null
            }
        } else if (s.count { it == ':' } == 1) {
            host = s.substringBefore(':')
            port = s.substringAfter(':').toIntOrNull() ?: return null
        } else {
            host = s
        }
        host = host.trim()
        if (host.isEmpty() || host.any { it.isWhitespace() }) return null
        if (port !in 1..65535) return null
        return host to port
    }
}
