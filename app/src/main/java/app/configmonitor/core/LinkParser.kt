package app.configmonitor.core

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class Protocol(val badge: String) {
    VLESS("VLESS"),
    VMESS("VMESS"),
    TROJAN("TROJAN"),
    SHADOWSOCKS("SS"),
    SOCKS("SOCKS"),
    HYSTERIA2("HY2"),
    WIREGUARD("WG"),
}

enum class ParseError { EMPTY, UNKNOWN_SCHEME, CREDENTIAL, HOST, PORT, FORMAT }

sealed class ParseResult {
    data class Ok(val config: ProxyConfig) : ParseResult()
    data class Err(val error: ParseError) : ParseResult()
}

data class ProxyConfig(
    val protocol: Protocol,
    val host: String,
    val port: Int,
    val secret: String,                 // uuid / password / private key / auth
    val params: Map<String, String>,    // keys are lower-cased
    val remark: String,
    val unsupported: String? = null,    // reason this link cannot be tested (plugin, obfs ...)
) {
    /** Parameter lookup (case-insensitive key, blank = missing). */
    fun p(key: String): String? = params[key.lowercase()]?.takeIf { it.isNotBlank() }

    val security: String
        get() = (p("security") ?: if (protocol == Protocol.TROJAN || protocol == Protocol.HYSTERIA2) "tls" else "none")
            .lowercase()

    val network: String
        get() = when (protocol) {
            Protocol.HYSTERIA2 -> "hysteria"
            Protocol.WIREGUARD -> "udp"
            Protocol.SHADOWSOCKS, Protocol.SOCKS -> "tcp"
            else -> LinkParser.normalizeNetwork(p("type"))
        }

    /** UDP based: a plain TCP connect says nothing about the server, only the real test does. */
    val usesUdp: Boolean
        get() = protocol == Protocol.HYSTERIA2 || protocol == Protocol.WIREGUARD || network == "mkcp"

    val displayName: String get() = remark.ifBlank { host }

    val summary: String
        get() = when (protocol) {
            Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN -> "$host:$port · $security · $network"
            Protocol.SHADOWSOCKS -> "$host:$port · ${p("method") ?: "shadowsocks"}"
            Protocol.SOCKS -> "$host:$port · socks"
            Protocol.HYSTERIA2 -> "$host:$port · hysteria2"
            Protocol.WIREGUARD -> "$host:$port · wireguard"
        }
}

/** Reads vless:// vmess:// trojan:// ss:// socks:// hysteria2:// hy2:// wireguard:// share links. */
object LinkParser {
    private class Fail(val error: ParseError) : Exception()

    private class Parts(
        val user: String,
        val host: String,
        val port: Int,
        val params: Map<String, String>,
        val remark: String,
    )

    val schemes = listOf("vless", "vmess", "trojan", "ss", "socks", "socks5", "socks5h", "hysteria2", "hy2", "wireguard", "wg")

    fun normalizeNetwork(type: String?): String {
        val t = (type ?: "tcp").trim().lowercase()
        return when (t) {
            "", "tcp", "raw" -> "tcp"
            "kcp", "mkcp" -> "mkcp"
            "ws", "websocket" -> "ws"
            "grpc", "gun" -> "grpc"
            "httpupgrade" -> "httpupgrade"
            "xhttp", "splithttp" -> "xhttp"
            "h2", "http" -> "h2"
            else -> t
        }
    }

    fun decodeBase64(text: String): String? {
        var t = text.trim().replace("\n", "").replace("\r", "")
        if (t.isEmpty()) return null
        while (t.length % 4 != 0) t += "="
        for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE)) {
            try {
                return String(Base64.decode(t, flags), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                // try the next alphabet
            }
        }
        return null
    }

    fun parse(input: String): ParseResult {
        val link = input.trim()
        if (link.isEmpty()) return ParseResult.Err(ParseError.EMPTY)
        val sep = link.indexOf("://")
        if (sep <= 0) return ParseResult.Err(ParseError.UNKNOWN_SCHEME)
        val scheme = link.substring(0, sep).lowercase()
        val rest = link.substring(sep + 3)
        return try {
            ParseResult.Ok(
                when (scheme) {
                    "vless" -> fromUri(rest, Protocol.VLESS)
                    "trojan" -> fromUri(rest, Protocol.TROJAN)
                    "vmess" -> fromVmess(rest)
                    "ss" -> fromShadowsocks(rest)
                    "socks", "socks5", "socks5h" -> fromSocks(rest)
                    "hysteria2", "hy2" -> fromUri(rest, Protocol.HYSTERIA2)
                    "wireguard", "wg" -> fromUri(rest, Protocol.WIREGUARD)
                    else -> throw Fail(ParseError.UNKNOWN_SCHEME)
                }
            )
        } catch (f: Fail) {
            ParseResult.Err(f.error)
        } catch (e: Exception) {
            ParseResult.Err(ParseError.FORMAT)
        }
    }

    // ---------------------------------------------------------------- pieces

    private fun hostPort(text: String): Pair<String, Int> {
        val host: String
        val portText: String
        if (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end < 0) throw Fail(ParseError.HOST)
            host = text.substring(1, end)
            portText = text.substring(end + 1).removePrefix(":")
        } else {
            val colon = text.lastIndexOf(':')
            if (colon < 0) throw Fail(ParseError.PORT)
            host = text.substring(0, colon)
            portText = text.substring(colon + 1)
        }
        val h = host.trim()
        if (h.isEmpty() || h.any { it.isWhitespace() }) throw Fail(ParseError.HOST)
        val first = portText.trim().split(',', '-').firstOrNull()?.trim() ?: ""
        val port = first.toIntOrNull()
        if (port == null || port !in 1..65535) throw Fail(ParseError.PORT)
        return h to port
    }

    private fun queryMap(query: String): LinkedHashMap<String, String> {
        val params = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq < 0) pair else pair.substring(0, eq)
            val value = if (eq < 0) "" else pair.substring(eq + 1)
            params[Uri.decode(key).lowercase()] = Uri.decode(value)
        }
        return params
    }

    private fun split(input: String, requireUser: Boolean): Parts {
        var rest = input
        var remark = ""
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            remark = Uri.decode(rest.substring(hash + 1)).trim()
            rest = rest.substring(0, hash)
        }
        var query = ""
        val q = rest.indexOf('?')
        if (q >= 0) {
            query = rest.substring(q + 1)
            rest = rest.substring(0, q)
        }
        rest = rest.trimEnd('/')
        var user = ""
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            user = rest.substring(0, at)
            rest = rest.substring(at + 1)
        } else if (requireUser) {
            throw Fail(ParseError.CREDENTIAL)
        }
        val hp = hostPort(rest)
        return Parts(Uri.decode(user), hp.first, hp.second, queryMap(query), remark)
    }

    private fun isTrue(v: String?): Boolean = v == "1" || v.equals("true", ignoreCase = true)

    /** vless:// trojan:// hysteria2:// wireguard:// and the URI form of vmess:// */
    private fun fromUri(rest: String, protocol: Protocol): ProxyConfig {
        val parts = split(rest, requireUser = true)
        val user = parts.user.trim()
        if (user.isEmpty()) throw Fail(ParseError.CREDENTIAL)
        var unsupported: String? = null
        val params = LinkedHashMap<String, String>(parts.params)
        when (protocol) {
            Protocol.HYSTERIA2 -> {
                if (!params["obfs"].isNullOrBlank()) {
                    unsupported = "obfs"
                } else if (isTrue(params["insecure"]) && params["pinsha256"].isNullOrBlank()) {
                    unsupported = "self-signed certificate"
                }
            }
            Protocol.WIREGUARD -> {
                if (params["publickey"].isNullOrBlank()) throw Fail(ParseError.FORMAT)
                if (params["address"].isNullOrBlank()) unsupported = "missing address"
            }
            else -> { }
        }
        return ProxyConfig(protocol, parts.host, parts.port, user, params, parts.remark, unsupported)
    }

    private fun fromVmess(rest: String): ProxyConfig {
        val body = rest.substringBefore('#')
        if (body.contains('@')) return fromUri(rest, Protocol.VMESS)
        val decoded = decodeBase64(body) ?: throw Fail(ParseError.FORMAT)
        val j = JSONObject(decoded)
        val host = j.optString("add").trim()
        if (host.isEmpty()) throw Fail(ParseError.HOST)
        val port = j.optString("port").trim().toIntOrNull()
        if (port == null || port !in 1..65535) throw Fail(ParseError.PORT)
        val id = j.optString("id").trim()
        if (id.isEmpty()) throw Fail(ParseError.CREDENTIAL)

        val net = j.optString("net").ifBlank { "tcp" }
        val headerType = j.optString("type")
        val params = LinkedHashMap<String, String>()
        params["type"] = net
        params["host"] = j.optString("host")
        params["path"] = j.optString("path")
        params["security"] = if (j.optString("tls").equals("tls", ignoreCase = true)) "tls" else "none"
        params["sni"] = j.optString("sni")
        params["alpn"] = j.optString("alpn")
        params["fp"] = j.optString("fp")
        params["encryption"] = j.optString("scy").ifBlank { "auto" }
        if (net == "grpc") {
            params["servicename"] = j.optString("path")
            params["mode"] = headerType
        } else {
            params["headertype"] = headerType
        }
        return ProxyConfig(Protocol.VMESS, host, port, id, params, j.optString("ps").trim())
    }

    private fun fromShadowsocks(input: String): ProxyConfig {
        var rest = input
        var remark = ""
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            remark = Uri.decode(rest.substring(hash + 1)).trim()
            rest = rest.substring(0, hash)
        }
        var query = ""
        val q = rest.indexOf('?')
        if (q >= 0) {
            query = rest.substring(q + 1)
            rest = rest.substring(0, q)
        }
        rest = rest.trimEnd('/')

        val credentials: String
        val hostPart: String
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            val user = rest.substring(0, at)
            credentials = if (user.contains(':')) {
                Uri.decode(user)
            } else {
                decodeBase64(Uri.decode(user)) ?: throw Fail(ParseError.FORMAT)
            }
            hostPart = rest.substring(at + 1)
        } else {
            // legacy form: ss://base64(method:password@host:port)
            val decoded = decodeBase64(rest) ?: throw Fail(ParseError.FORMAT)
            val a = decoded.lastIndexOf('@')
            if (a < 0) throw Fail(ParseError.FORMAT)
            credentials = decoded.substring(0, a)
            hostPart = decoded.substring(a + 1)
        }
        val colon = credentials.indexOf(':')
        if (colon <= 0) throw Fail(ParseError.CREDENTIAL)
        val method = credentials.substring(0, colon).trim().lowercase()
        val password = credentials.substring(colon + 1)
        val hp = hostPort(hostPart)

        val params = queryMap(query)
        params["method"] = method
        val unsupported = if (!params["plugin"].isNullOrBlank()) "plugin" else null
        return ProxyConfig(Protocol.SHADOWSOCKS, hp.first, hp.second, password, params, remark, unsupported)
    }

    private fun fromSocks(rest: String): ProxyConfig {
        val parts = split(rest, requireUser = false)
        val params = LinkedHashMap<String, String>(parts.params)
        val raw = parts.user
        if (raw.isNotEmpty()) {
            val cred = if (raw.contains(':')) raw else (decodeBase64(raw) ?: raw)
            val c = cred.indexOf(':')
            if (c > 0) {
                params["user"] = cred.substring(0, c)
                params["pass"] = cred.substring(c + 1)
            }
        }
        return ProxyConfig(Protocol.SOCKS, parts.host, parts.port, "", params, parts.remark)
    }

    // ---------------------------------------------------------------- bulk text

    fun isSubscriptionUrl(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("http://") || t.startsWith("https://")) && t.none { it.isWhitespace() }
    }

    /** Splits pasted text (or a decoded subscription) into individual share links. */
    fun extractLinks(text: String): List<String> {
        val source = if (text.contains("://")) text else (decodeBase64(text) ?: text)
        return source.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { s ->
                val i = s.indexOf("://")
                i > 0 && s.substring(0, i).lowercase() in schemes
            }
    }

    /** Downloads a subscription link and returns the share links inside it. Call off the main thread. */
    fun fetchSubscription(url: String): List<String> {
        val conn = URL(url.trim()).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "ConfigMonitor/2.0")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > 3_000_000) throw IOException("response too large")
            return extractLinks(String(bytes, Charsets.UTF_8))
        } finally {
            conn.disconnect()
        }
    }
}
