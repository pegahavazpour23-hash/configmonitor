package app.configmonitor.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a minimal xray-core JSON config (one outbound, no inbound) from a share link.
 * The config is only used for a one-off delay test: nothing listens on the phone and no VPN is created.
 */
object XrayConfigBuilder {

    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun isIpLiteral(host: String): Boolean = host.contains(':') || ipv4.matches(host)

    /** TLS server name: sni param, else the host if it is a domain, else empty (no SNI). */
    fun serverNameFor(c: ProxyConfig): String {
        c.p("sni")?.let { return it }
        c.p("peer")?.let { return it }
        return if (isIpLiteral(c.host)) "" else c.host
    }

    /** allowInsecure was removed from xray-core; such links need a pinned certificate hash instead. */
    fun needsCertPin(c: ProxyConfig): Boolean {
        if (c.security != "tls" || c.protocol == Protocol.HYSTERIA2) return false
        val flag = (c.p("allowinsecure") ?: c.p("insecure") ?: "0").lowercase()
        val wantsInsecure = flag == "1" || flag == "true"
        return wantsInsecure && c.p("pcs") == null
    }

    /**
     * @param connectAddress the already-resolved IP of the server (DNS is done by Android, not by the core)
     * @param pinnedSha256 certificate hash for TLS links that used allowInsecure
     */
    fun build(c: ProxyConfig, connectAddress: String, pinnedSha256: String?): String {
        val outbound = JSONObject().put("tag", "proxy")
        when (c.protocol) {
            Protocol.VLESS -> {
                val s = base(connectAddress, c.port)
                    .put("id", c.secret)
                    .put("encryption", c.p("encryption") ?: "none")
                c.p("flow")?.let { s.put("flow", it) }
                outbound.put("protocol", "vless").put("settings", s).put("streamSettings", stream(c, pinnedSha256))
            }
            Protocol.VMESS -> {
                val s = base(connectAddress, c.port)
                    .put("id", c.secret)
                    .put("security", c.p("encryption") ?: c.p("scy") ?: "auto")
                outbound.put("protocol", "vmess").put("settings", s).put("streamSettings", stream(c, pinnedSha256))
            }
            Protocol.TROJAN -> {
                val s = base(connectAddress, c.port).put("password", c.secret)
                outbound.put("protocol", "trojan").put("settings", s).put("streamSettings", stream(c, pinnedSha256))
            }
            Protocol.SHADOWSOCKS -> {
                val s = base(connectAddress, c.port)
                    .put("method", c.p("method") ?: "aes-128-gcm")
                    .put("password", c.secret)
                outbound.put("protocol", "shadowsocks").put("settings", s)
            }
            Protocol.SOCKS -> {
                val s = base(connectAddress, c.port)
                c.p("user")?.let { s.put("user", it) }
                c.p("pass")?.let { s.put("pass", it) }
                outbound.put("protocol", "socks").put("settings", s)
            }
            Protocol.HYSTERIA2 -> {
                val s = JSONObject().put("version", 2).put("address", connectAddress).put("port", c.port)
                outbound.put("protocol", "hysteria").put("settings", s).put("streamSettings", hysteriaStream(c))
            }
            Protocol.WIREGUARD -> {
                outbound.put("protocol", "wireguard").put("settings", wireguard(c, connectAddress))
            }
        }
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "none"))
            .put("outbounds", JSONArray().put(outbound))
            .toString()
    }

    private fun base(address: String, port: Int): JSONObject =
        JSONObject().put("address", address).put("port", port).put("level", 0)

    private fun wireguard(c: ProxyConfig, address: String): JSONObject {
        val endpointHost = if (address.contains(':')) "[$address]" else address
        val peer = JSONObject()
            .put("publicKey", c.p("publickey") ?: "")
            .put("endpoint", "$endpointHost:${c.port}")
            .put("allowedIPs", JSONArray().put("0.0.0.0/0").put("::/0"))
        c.p("presharedkey")?.let { peer.put("preSharedKey", it) }

        val addresses = JSONArray()
        (c.p("address") ?: "10.0.0.2/32").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { addresses.put(it) }

        val s = JSONObject()
            .put("secretKey", c.secret)
            .put("address", addresses)
            .put("peers", JSONArray().put(peer))
            .put("mtu", c.p("mtu")?.toIntOrNull() ?: 1280)
        c.p("reserved")?.let { r ->
            val nums = r.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (nums.size == 3) s.put("reserved", JSONArray(nums))
        }
        return s
    }

    private fun hysteriaStream(c: ProxyConfig): JSONObject {
        val tls = JSONObject().put("alpn", JSONArray().put("h3"))
        val sn = serverNameFor(c)
        if (sn.isNotEmpty()) tls.put("serverName", sn)
        c.p("pinsha256")?.let { tls.put("pinnedPeerCertSha256", it.replace(":", "")) }
        return JSONObject()
            .put("network", "hysteria")
            .put("security", "tls")
            .put("tlsSettings", tls)
            .put("hysteriaSettings", JSONObject().put("version", 2).put("auth", c.secret))
    }

    private fun stream(c: ProxyConfig, pinned: String?): JSONObject {
        val s = JSONObject()
        val net = c.network
        // the address handed to the core is an IP, so the original domain must be passed explicitly
        val hostParam: String? = c.p("host") ?: (if (isIpLiteral(c.host)) null else c.host)
        val path = c.p("path") ?: "/"

        s.put("network", net)
        when (net) {
            "ws" -> {
                val ws = JSONObject().put("path", path)
                if (hostParam != null) ws.put("headers", JSONObject().put("Host", hostParam))
                s.put("wsSettings", ws)
            }
            "grpc" -> {
                val g = JSONObject()
                    .put("serviceName", c.p("servicename") ?: "")
                    .put("multiMode", (c.p("mode") ?: "gun") == "multi")
                val authority = c.p("authority") ?: hostParam
                if (authority != null) g.put("authority", authority)
                s.put("grpcSettings", g)
            }
            "httpupgrade" -> {
                val h = JSONObject().put("path", path)
                if (hostParam != null) h.put("host", hostParam)
                s.put("httpupgradeSettings", h)
            }
            "xhttp" -> {
                val x = JSONObject().put("path", path).put("mode", c.p("mode") ?: "auto")
                if (hostParam != null) x.put("host", hostParam)
                val extra = c.p("extra")
                if (extra != null) {
                    try {
                        x.put("extra", JSONObject(extra))
                    } catch (e: Exception) {
                        // ignore a malformed "extra"
                    }
                }
                s.put("xhttpSettings", x)
            }
            else -> {
                if ((c.p("headertype") ?: "none") == "http") {
                    val request = JSONObject()
                        .put("version", "1.1")
                        .put("method", "GET")
                        .put("path", JSONArray(path.split(",")))
                        .put("headers", JSONObject().put("Host", JSONArray((hostParam ?: "").split(","))))
                    val header = JSONObject().put("type", "http").put("request", request)
                    s.put("tcpSettings", JSONObject().put("header", header))
                }
            }
        }

        when (c.security) {
            "tls" -> {
                s.put("security", "tls")
                val tls = JSONObject()
                val sn = serverNameFor(c)
                if (sn.isNotEmpty()) tls.put("serverName", sn)
                c.p("fp")?.let { tls.put("fingerprint", it) }
                c.p("alpn")?.let { raw ->
                    val list = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    tls.put("alpn", JSONArray(list))
                }
                val pin = c.p("pcs") ?: pinned
                if (pin != null) tls.put("pinnedPeerCertSha256", pin)
                c.p("vcn")?.let { tls.put("verifyPeerCertByName", it) }
                s.put("tlsSettings", tls)
            }
            "reality" -> {
                s.put("security", "reality")
                val key = c.p("pbk") ?: ""
                val r = JSONObject()
                    .put("serverName", serverNameFor(c))
                    .put("fingerprint", c.p("fp") ?: "chrome")
                    .put("password", key)     // new field name
                    .put("publicKey", key)    // old field name (older cores)
                    .put("shortId", c.p("sid") ?: "")
                    .put("spiderX", c.p("spx") ?: "")
                c.p("pqv")?.let { r.put("mldsa65Verify", it) }
                s.put("realitySettings", r)
            }
            else -> {
                s.put("security", "none")
            }
        }
        return s
    }
}
