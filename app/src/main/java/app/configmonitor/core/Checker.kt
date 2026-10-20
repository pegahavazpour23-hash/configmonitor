package app.configmonitor.core

import android.os.SystemClock
import app.configmonitor.data.AppSettings
import app.configmonitor.data.ErrorCode
import app.configmonitor.data.Target
import app.configmonitor.data.TargetKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

sealed class CheckOutcome {
    data class Success(val latencyMs: Long) : CheckOutcome()
    data class Failure(val code: ErrorCode, val detail: String? = null) : CheckOutcome()
    data class Unsupported(val reason: String) : CheckOutcome()
}

/**
 * Runs one check for a target.
 *
 *  CONFIG : DNS (Android resolver) -> TCP connect (fast fail, TCP based protocols only)
 *           -> real delay test through the xray core.
 *           If the core is missing or "accurate mode" is off, the TCP connect time is used.
 *  ADDRESS: DNS -> TCP connect to host:port (default 443).
 *
 * No VPN, no tunnel: the only traffic is the test itself.
 */
class Checker {
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "cm-blocking").apply { isDaemon = true }
    }
    private val coreSlots = Semaphore(4)

    suspend fun check(target: Target, s: AppSettings): CheckOutcome = withContext(Dispatchers.IO) {
        try {
            when (target.kind) {
                TargetKind.CONFIG -> checkConfig(target, s)
                TargetKind.ADDRESS -> checkAddress(target, s)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            classify(e)
        }
    }

    private suspend fun checkAddress(t: Target, s: AppSettings): CheckOutcome {
        val parsed = AddressParser.parse(t.raw) ?: return CheckOutcome.Unsupported("invalid address")
        val ip = resolve(parsed.first)
        return CheckOutcome.Success(tcpConnect(ip, parsed.second, s.tcpTimeoutSec * 1000))
    }

    private suspend fun checkConfig(t: Target, s: AppSettings): CheckOutcome {
        val c = (LinkParser.parse(t.raw) as? ParseResult.Ok)?.config
            ?: return CheckOutcome.Unsupported("invalid link")
        c.unsupported?.let { return CheckOutcome.Unsupported(it) }
        val net = c.network
        if (net == "h2" || net == "quic" || net == "mkcp") {
            // removed from xray-core (h2, quic) or UDP based with settings that changed (mKCP)
            return CheckOutcome.Unsupported("transport: $net")
        }

        val ip = resolve(c.host)
        val coreReady = s.accurateMode && XrayEngine.available
        if (c.usesUdp && !coreReady) return CheckOutcome.Unsupported("needs the xray core (UDP)")

        val tcpMs = if (c.usesUdp) -1L else tcpConnect(ip, c.port, s.tcpTimeoutSec * 1000)
        if (!coreReady) return CheckOutcome.Success(tcpMs)

        val address = (ip.hostAddress ?: c.host).substringBefore('%')
        var pin: String? = null
        if (XrayConfigBuilder.needsCertPin(c)) {
            pin = blocking(8_000L) {
                XrayEngine.fetchCertSha256(address, c.port, XrayConfigBuilder.serverNameFor(c), 6_000)
            }
        }
        val json = XrayConfigBuilder.build(c, address, pin)
        val url = testUrl(s)
        val ms = coreSlots.withPermit {
            blocking(15_000L) { XrayEngine.measureDelay(json, url) }
        }
        return if (ms >= 0) CheckOutcome.Success(ms) else CheckOutcome.Failure(ErrorCode.OTHER, "core test failed")
    }

    private fun testUrl(s: AppSettings): String {
        val u = s.testUrl.trim()
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://www.gstatic.com/generate_204"
    }

    private suspend fun resolve(host: String): InetAddress {
        val all = try {
            blocking(5_000L) { InetAddress.getAllByName(host) }
        } catch (e: UnknownHostException) {
            throw e
        } catch (e: IOException) {
            throw UnknownHostException("DNS timeout")
        }
        return all.firstOrNull { it is Inet4Address } ?: all.first()
    }

    /** Blocking connect; bounded by [timeoutMs]. Returns the connect time in ms. */
    private fun tcpConnect(ip: InetAddress, port: Int, timeoutMs: Int): Long {
        val start = SystemClock.elapsedRealtime()
        Socket().use { sock ->
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(ip, port), timeoutMs)
        }
        return SystemClock.elapsedRealtime() - start
    }

    /**
     * Runs [block] on a helper thread and gives up waiting after [timeoutMs]. A call that cannot be
     * interrupted (DNS, native code) keeps running in the background, but the monitor moves on.
     */
    private suspend fun <T> blocking(timeoutMs: Long, block: () -> T): T {
        val result: Result<T> = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Result<T>> { cont ->
                val future = pool.submit(Runnable {
                    val r = runCatching { block() }
                    cont.resume(r)
                })
                cont.invokeOnCancellation { future.cancel(true) }
            }
        } ?: throw IOException("Timed out")
        return result.getOrThrow()
    }

    private fun classify(e: Throwable): CheckOutcome.Failure {
        val msg = (e.message ?: "").lowercase()
        val code = when {
            e is UnknownHostException -> ErrorCode.DNS
            e is SocketTimeoutException -> ErrorCode.TCP_TIMEOUT
            e is ConnectException -> if (msg.contains("refused")) ErrorCode.REFUSED else ErrorCode.TCP_TIMEOUT
            msg.contains("tls") || msg.contains("certificate") || msg.contains("x509") -> ErrorCode.TLS
            msg.contains("timed out") || msg.contains("timeout") || msg.contains("deadline") -> ErrorCode.TIMEOUT
            msg.contains("eof") || msg.contains("reset") || msg.contains("closed") || msg.contains("refused") ->
                ErrorCode.REJECTED
            else -> ErrorCode.OTHER
        }
        val detail = (e.message ?: e.javaClass.simpleName).lineSequence().first().take(140)
        return CheckOutcome.Failure(code, detail)
    }
}
