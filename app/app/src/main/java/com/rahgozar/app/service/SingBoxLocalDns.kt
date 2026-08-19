package com.rahgozar.app.service

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.rahgozar.app.AppConfig
import com.rahgozar.app.util.LogUtil
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Resolves names through Android instead of through Go.
 *
 * Without this the sing-box core cannot resolve anything at all. Go's resolver
 * reads `/etc/resolv.conf`, which does not exist on Android, so it falls back
 * to `[::1]:53` and every lookup fails with "connection refused" — the core
 * starts, the tunnel comes up, and not one connection to a named server can be
 * made. It cost a whole debugging session to see, because the failure surfaces
 * as an ordinary connection error far from its cause.
 *
 * This is what makes a `"type": "local"` DNS server work, and it is also the
 * implicit default when a configuration names no DNS server at all
 * (`box.go`: `dnsTransportManager.Initialize`), so it is on nearly every path.
 *
 * Queries go out on the device's own network. When our tunnel is up this app
 * is excluded from it (see [PerAppProxy]), so they resolve outside the tunnel
 * — which is what the core wants when it is looking up its own server.
 */
class SingBoxLocalDns : LocalDNSTransport {

    private companion object {
        const val TAG = "SingBox-LocalDNS"

        /** Well beyond any real answer; only here so a stuck query cannot hang a thread. */
        const val QUERY_TIMEOUT_MS = 10_000L

        /** DNS SERVFAIL, for a query the platform could not answer. */
        const val RCODE_SERVFAIL = 2

        /** DNS NXDOMAIN, for a name with no address of the family asked for. */
        const val RCODE_NXDOMAIN = 3
    }

    private val executor by lazy { Executors.newCachedThreadPool() }

    /**
     * Whole-message queries are only available from API 29. Below that the
     * core asks for plain lookups instead, which is why [lookup] exists.
     */
    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val done = CountDownLatch(1)
        val signal = CancellationSignal()
        // The core cancels when its own deadline passes; without this the
        // query would run on past the answer nobody is waiting for any more.
        ctx.onCancel { signal.cancel() }

        DnsResolver.getInstance().rawQuery(
            // Null means the default network for this app, which is the
            // physical one: this app is never inside its own tunnel.
            null,
            message,
            DnsResolver.FLAG_EMPTY,
            executor,
            signal,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                    done.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    LogUtil.i(AppConfig.TAG, "$TAG: query failed: ${error.message}")
                    ctx.errnoCode(OsConstants.ECONNREFUSED)
                    done.countDown()
                }
            },
        )

        if (!done.await(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            signal.cancel()
            ctx.errnoCode(OsConstants.ETIMEDOUT)
        }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        // The core asks with a fully qualified name; the platform wants a host.
        val host = domain.trimEnd('.')
        val resolved = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            LogUtil.i(AppConfig.TAG, "$TAG: cannot resolve $host: ${e.message}")
            ctx.errorCode(RCODE_SERVFAIL)
            return
        }

        val wanted = when (network) {
            "ip4" -> resolved.filterIsInstance<Inet4Address>()
            "ip6" -> resolved.filterIsInstance<Inet6Address>()
            else -> resolved.toList()
        }
        if (wanted.isEmpty()) {
            // The name exists but not in this family — a v4-only host asked
            // for over AAAA, say. That is an empty answer, not a failure.
            ctx.errorCode(RCODE_NXDOMAIN)
            return
        }

        ctx.success(wanted.mapNotNull { it.hostAddress }.joinToString("\n"))
    }
}
