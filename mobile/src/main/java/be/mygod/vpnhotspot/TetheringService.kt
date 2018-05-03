package be.mygod.vpnhotspot

import android.content.Intent
import android.os.Binder
import android.support.v4.content.LocalBroadcastManager
import android.widget.Toast
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.net.IpNeighbourMonitor
import be.mygod.vpnhotspot.net.Routing
import be.mygod.vpnhotspot.net.TetheringManager
import be.mygod.vpnhotspot.net.VpnMonitor
import java.net.InetAddress
import java.net.SocketException

class TetheringService : IpNeighbourMonitoringService(), VpnMonitor.Callback {
    companion object {
        const val EXTRA_ADD_INTERFACE = "interface.add"
        const val EXTRA_REMOVE_INTERFACE = "interface.remove"
    }

    inner class TetheringBinder : Binder() {
        var fragment: TetheringFragment? = null

        fun isActive(iface: String): Boolean = synchronized(routings) { routings.keys.contains(iface) }
    }

    private val binder = TetheringBinder()
    private val routings = HashMap<String, Routing?>()
    private var upstream: String? = null
    private var dns: List<InetAddress> = emptyList()
    private var receiverRegistered = false
    private val receiver = broadcastReceiver { _, intent ->
        synchronized(routings) {
            when (intent.action) {
                TetheringManager.ACTION_TETHER_STATE_CHANGED -> {
                    val failed = (routings.keys - TetheringManager.getTetheredIfaces(intent.extras))
                            .any { routings.remove(it)?.stop() == false }
                    if (failed) Toast.makeText(this, getText(R.string.noisy_su_failure), Toast.LENGTH_SHORT).show()
                }
                App.ACTION_CLEAN_ROUTINGS -> for (iface in routings.keys) routings[iface] = null
            }
            updateRoutingsLocked()
        }
    }
    override val activeIfaces get() = synchronized(routings) { routings.keys.toList() }

    fun updateRoutingsLocked() {
        if (routings.isNotEmpty()) {
            val upstream = upstream
            if (upstream != null) {
                var failed = false
                for ((downstream, value) in routings) if (value == null) try {
                    // system tethering already has working forwarding rules
                    // so it doesn't make sense to add additional forwarding rules
                    val routing = Routing(upstream, downstream).rule().forward().masquerade().dnsRedirect(dns)
                    routings[downstream] = routing
                    if (!routing.start()) failed = true
                } catch (e: SocketException) {
                    e.printStackTrace()
                    routings.remove(downstream)
                    failed = true
                }
                if (failed) Toast.makeText(this, getText(R.string.noisy_su_failure), Toast.LENGTH_SHORT).show()
            } else if (!receiverRegistered) {
                registerReceiver(receiver, intentFilter(TetheringManager.ACTION_TETHER_STATE_CHANGED))
                LocalBroadcastManager.getInstance(this)
                        .registerReceiver(receiver, intentFilter(App.ACTION_CLEAN_ROUTINGS))
                IpNeighbourMonitor.registerCallback(this)
                VpnMonitor.registerCallback(this)
                receiverRegistered = true
            }
            postIpNeighbourAvailable()
        }
        if (routings.isEmpty()) {
            unregisterReceiver()
            ServiceNotification.stopForeground(this)
            stopSelf()
        }
        app.handler.post { binder.fragment?.adapter?.notifyDataSetChanged() }
    }

    override fun onBind(intent: Intent?) = binder

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val iface = intent.getStringExtra(EXTRA_ADD_INTERFACE)
        synchronized(routings) {
            if (iface != null) routings[iface] = null
            if (routings.remove(intent.getStringExtra(EXTRA_REMOVE_INTERFACE))?.stop() == false)
                Toast.makeText(this, getText(R.string.noisy_su_failure), Toast.LENGTH_SHORT).show()
            updateRoutingsLocked()
        }
        return START_NOT_STICKY
    }

    override fun onAvailable(ifname: String, dns: List<InetAddress>) {
        check(upstream == null || upstream == ifname)
        upstream = ifname
        this.dns = dns
        synchronized(routings) { updateRoutingsLocked() }
    }

    override fun onLost(ifname: String) {
        check(upstream == null || upstream == ifname)
        upstream = null
        this.dns = emptyList()
        var failed = false
        synchronized(routings) {
            for ((iface, routing) in routings) {
                if (routing?.stop() == false) failed = true
                routings[iface] = null
            }
        }
        if (failed) Toast.makeText(this, getText(R.string.noisy_su_failure), Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        unregisterReceiver()
        super.onDestroy()
    }

    private fun unregisterReceiver() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
            IpNeighbourMonitor.unregisterCallback(this)
            VpnMonitor.unregisterCallback(this)
            upstream = null
            receiverRegistered = false
        }
    }
}
