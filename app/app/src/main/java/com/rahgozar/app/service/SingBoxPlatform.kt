package com.rahgozar.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Process
import com.rahgozar.app.AppConfig
import com.rahgozar.app.util.LogUtil
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetSocketAddress
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

/**
 * The tunnel a [SingBoxPlatform] builds its interface on.
 *
 * Exists so the platform can also serve a core that has no tunnel at all: the
 * delay test runs a real sing-box instance with a local proxy inbound instead
 * of a tun, and everything else here — the interface monitor, the network
 * list, process lookup — is the same for both.
 */
interface SingBoxTunnel {
    /** Builds the tun the core described and returns its descriptor. */
    fun openTun(options: TunOptions): Int

    /** Keeps a socket out of the tunnel it is carrying. */
    fun protectSocket(fd: Int): Boolean
}

/**
 * What the sing-box core asks the platform for, answered with Android.
 *
 * The shape is the same inversion as OpenVPN's TunBuilderBase: the core does
 * not open its own tun, it hands us [SingBoxTunnel.openTun] and we answer with
 * a descriptor from [VpnService.Builder] — which is why per-app proxy keeps
 * working on sing-box servers.
 *
 * Most of the surface is stubs. The interface also carries sing-box's shell,
 * SFTP, tailscale-SSH and Linux-bridge features; none of them have a meaning
 * inside an Android VPN app, so they refuse loudly rather than pretend.
 *
 * @param tunnel null in the delay-test process, where the core is asked for a
 *   local proxy rather than an interface.
 */
class SingBoxPlatform(
    private val context: Context,
    private val tunnel: SingBoxTunnel?,
) : PlatformInterface {

    private companion object {
        const val TAG = "SingBox-Platform"

        // linkFlags() on the Go side expects raw unix IFF_* bits, which
        // java.net.NetworkInterface does not expose, so they are rebuilt from
        // what it does expose.
        const val IFF_UP = 0x1
        const val IFF_BROADCAST = 0x2
        const val IFF_LOOPBACK = 0x8
        const val IFF_POINTOPOINT = 0x10
        const val IFF_MULTICAST = 0x1000
    }

    private val connectivity: ConnectivityManager
        get() = context.getSystemService(ConnectivityManager::class.java)

    // ---------------------------------------------------------------- tun ----

    override fun openTun(options: TunOptions): Int {
        val tunnel = tunnel ?: throw Exception("this process runs no tunnel")
        return tunnel.openTun(options)
    }

    // ------------------------------------------------------------- sockets ----

    /**
     * Only where there is a tunnel to stay out of. The delay test dials
     * ordinary sockets: this app's own package is excluded from every tunnel
     * it builds (see [PerAppProxy]), so a test never routes into one.
     */
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = tunnel != null

    /**
     * The sing-box twin of openvpn3's socket_protect: every socket the core
     * dials must be kept out of the tunnel it is carrying, or the connection
     * routes into itself and dies on the spot.
     */
    override fun autoDetectInterfaceControl(fd: Int) {
        val tunnel = tunnel ?: return
        if (!tunnel.protectSocket(fd)) {
            throw Exception("protect() rejected the socket")
        }
    }

    // ------------------------------------------------------ process finding ----

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw Exception("connection owner lookup needs API 29")
        }
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) {
            throw Exception("connection owner not found")
        }
        val owner = ConnectionOwner()
        owner.setUserId(uid)
        context.packageManager.getPackagesForUid(uid)?.let { packages ->
            owner.setAndroidPackageNames(StringArray(packages.toList()))
        }
        return owner
    }

    // ---------------------------------------------------- interface monitor ----

    private var monitorCallback: ConnectivityManager.NetworkCallback? = null

    /** Last state handed to the core, so identical updates are not repeated. */
    private var lastReported: Triple<String, Int, Boolean>? = null

    /**
     * Same request NetworkMonitor uses, for the same reason: once the tun is
     * up, registerDefaultNetworkCallback would report the tunnel itself as the
     * default network, and the core would try to dial the server through it.
     */
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        // Reported before returning, and this is not a nicety.
        //
        // The core starts, this monitor registers, and the framework's first
        // callback lands a few milliseconds later — by which time traffic may
        // already be in flight. To the core that first callback is the default
        // interface *changing*, so it tears down whatever had just been
        // opened: "network changed", ten milliseconds in. On a delay test,
        // whose whole life is two seconds, that is the difference between a
        // number and a server reported dead — and it hit perhaps half the
        // measurements before this call existed.
        runCatching { connectivity.activeNetwork }.getOrNull()?.let { report(it, listener) }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = report(network, listener)

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                report(network, listener)

            override fun onLost(network: Network) {
                synchronized(this@SingBoxPlatform) { lastReported = null }
                // Index -1 is the protocol for "there is no default network".
                listener.updateDefaultInterface("", -1, false, false)
            }
        }
        monitorCallback = callback
        connectivity.requestNetwork(request, callback)
    }

    /**
     * onCapabilitiesChanged fires on things as small as a signal-strength
     * change, so a report that says nothing new is dropped here rather than
     * being handed to the core, which treats every update as a reason to
     * re-examine its connections.
     */
    private fun report(network: Network, listener: InterfaceUpdateListener) {
        val name = connectivity.getLinkProperties(network)?.interfaceName ?: return
        val index = runCatching { java.net.NetworkInterface.getByName(name)?.index }
            .getOrNull() ?: return
        val expensive = connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false

        val state = Triple(name, index, expensive)
        synchronized(this) {
            if (lastReported == state) return
            lastReported = state
        }
        listener.updateDefaultInterface(name, index, expensive, false)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        monitorCallback?.let {
            runCatching { connectivity.unregisterNetworkCallback(it) }
        }
        monitorCallback = null
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        // What java.net knows (index, MTU, flags, addresses) joined with what
        // ConnectivityManager knows (transport type, DNS servers, metering),
        // matched up by interface name.
        data class NetworkExtra(
            val type: Int,
            val dnsServers: List<String>,
            val metered: Boolean,
        )

        val extras = HashMap<String, NetworkExtra>()
        @Suppress("DEPRECATION")
        for (network in connectivity.allNetworks) {
            val lp = connectivity.getLinkProperties(network) ?: continue
            val name = lp.interfaceName ?: continue
            val caps = connectivity.getNetworkCapabilities(network)
            val type = when {
                caps == null -> io.nekohasekai.libbox.Libbox.InterfaceTypeOther
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                    io.nekohasekai.libbox.Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    io.nekohasekai.libbox.Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                    io.nekohasekai.libbox.Libbox.InterfaceTypeEthernet
                else -> io.nekohasekai.libbox.Libbox.InterfaceTypeOther
            }
            extras[name] = NetworkExtra(
                type = type,
                dnsServers = lp.dnsServers.mapNotNull { it.hostAddress },
                metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            )
        }

        val result = ArrayList<LibboxNetworkInterface>()
        val interfaces = runCatching { java.net.NetworkInterface.getNetworkInterfaces() }
            .getOrNull() ?: return InterfaceArray(emptyList())
        for (iface in interfaces) {
            val boxIface = LibboxNetworkInterface()
            boxIface.setName(iface.name)
            boxIface.setIndex(iface.index)
            boxIface.setMTU(runCatching { iface.mtu }.getOrDefault(0))

            var flags = 0
            runCatching {
                if (iface.isUp) flags = flags or IFF_UP or IFF_BROADCAST
                if (iface.isLoopback) flags = flags or IFF_LOOPBACK
                if (iface.isPointToPoint) flags = flags or IFF_POINTOPOINT
                if (iface.supportsMulticast()) flags = flags or IFF_MULTICAST
            }
            boxIface.setFlags(flags)

            // "fe80::1%wlan0/64" is not a prefix netip will parse — the zone
            // has to go.
            boxIface.setAddresses(
                StringArray(
                    iface.interfaceAddresses.mapNotNull { address ->
                        address.address.hostAddress?.substringBefore('%')
                            ?.let { "$it/${address.networkPrefixLength}" }
                    }
                )
            )

            extras[iface.name]?.let { extra ->
                boxIface.setType(extra.type)
                boxIface.setDNSServer(StringArray(extra.dnsServers))
                boxIface.setMetered(extra.metered)
            } ?: boxIface.setType(io.nekohasekai.libbox.Libbox.InterfaceTypeOther)

            result.add(boxIface)
        }
        return InterfaceArray(result)
    }

    // ------------------------------------------------------------- passives ----

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    /**
     * Real SSID needs a location permission this app does not ask for; null
     * tells the core "no Wi-Fi state", which only disables SSID-match rules.
     */
    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() = Unit

    override fun sendNotification(notification: Notification?) {
        LogUtil.i(AppConfig.TAG, "$TAG: core notification: ${notification?.getTitle()} ${notification?.getBody()}")
    }

    override fun registerMyInterface(name: String) = Unit

    /**
     * Not optional, despite the null the interface allows: Go cannot resolve
     * anything on Android by itself. See [SingBoxLocalDns].
     */
    override fun localDNSTransport(): LocalDNSTransport = SingBoxLocalDns()

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun tailscaleHostname(): String = ""

    // ------------------------------------------------------------- refusals ----

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        throw Exception("shell sessions are not supported")
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession {
        throw Exception("shell sessions are not supported")
    }

    override fun lookupUser(username: String?): PlatformUser {
        throw Exception("user lookup is not supported")
    }

    override fun lookupSFTPServer(): String {
        throw Exception("SFTP is not supported")
    }

    override fun readSystemSSHHostKey(): String {
        throw Exception("SSH host keys are not supported")
    }

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession {
        throw Exception("bridges are not supported")
    }

    // -------------------------------------------------------------- helpers ----

    class StringArray(private val values: List<String>) : StringIterator {
        private var index = 0
        override fun len(): Int = values.size
        override fun hasNext(): Boolean = index < values.size
        override fun next(): String = values[index++]
    }

    private class InterfaceArray(
        private val values: List<LibboxNetworkInterface>,
    ) : NetworkInterfaceIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < values.size
        override fun next(): LibboxNetworkInterface = values[index++]
    }
}
