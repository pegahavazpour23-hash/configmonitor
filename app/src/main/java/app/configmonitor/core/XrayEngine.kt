package app.configmonitor.core

import org.json.JSONObject
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/**
 * Thin reflection wrapper around libv2ray.aar (AndroidLibXrayLite). Reflection keeps the project
 * compiling even if the .aar has not been downloaded yet; in that case [available] is false and
 * the app falls back to a plain TCP check.
 *
 * Used functions: Libv2ray.initCoreEnv, checkVersionX, measureOutboundDelay, fetchTlsCertSha256.
 * measureOutboundDelay starts a throw-away xray instance WITHOUT inbounds, sends one request
 * through the outbound and closes it again. No VpnService, no tunnel, nothing listens on the phone.
 */
object XrayEngine {
    private const val CLASS_NAME = "libv2ray.Libv2ray"

    @Volatile private var envDir: String = ""
    @Volatile private var envReady = false

    private val clazz: Class<*>? by lazy {
        try {
            Class.forName(CLASS_NAME)
        } catch (t: Throwable) {
            null
        }
    }

    val available: Boolean get() = clazz != null

    fun init(dir: String) {
        envDir = dir
    }

    private fun ensureEnv() {
        if (envReady) return
        synchronized(this) {
            if (envReady) return
            try {
                clazz?.getMethod("initCoreEnv", String::class.java, String::class.java)
                    ?.invoke(null, envDir, "")
            } catch (t: Throwable) {
                // optional
            }
            envReady = true
        }
    }

    /** e.g. "Lib v40, Xray-core v26.7.x", or null if the core is missing. */
    fun version(): String? = try {
        clazz?.getMethod("checkVersionX")?.invoke(null) as? String
    } catch (t: Throwable) {
        null
    }

    /** Round-trip time in ms of one HTTP request through the config, or throws. */
    fun measureDelay(config: String, url: String): Long {
        val c = clazz ?: throw IOException("xray core not found")
        ensureEnv()
        val m = c.getMethod("measureOutboundDelay", String::class.java, String::class.java)
        try {
            return (m.invoke(null, config, url) as Number).toLong()
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }

    /** SHA-256 of the certificate the server presents (used instead of the removed allowInsecure). */
    fun fetchCertSha256(address: String, port: Int, serverName: String, timeoutMs: Int): String? {
        val c = clazz ?: return null
        val m = try {
            c.getMethod("fetchTlsCertSha256", String::class.java)
        } catch (e: NoSuchMethodException) {
            return null
        }
        val request = JSONObject()
            .put("address", address)
            .put("port", port)
            .put("serverName", serverName)
            .put("timeoutMs", timeoutMs.toLong())
            .toString()
        val raw = try {
            m.invoke(null, request) as? String
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
        val json = JSONObject(raw ?: throw IOException("empty response"))
        val err = json.optString("error", "")
        if (err.isNotEmpty()) throw IOException(err)
        val sha = json.optString("sha256", "")
        if (sha.isEmpty()) throw IOException("no certificate")
        return sha
    }
}
