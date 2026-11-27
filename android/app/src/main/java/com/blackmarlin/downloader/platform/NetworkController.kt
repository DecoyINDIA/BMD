package com.blackmarlin.downloader.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.blackmarlin.downloader.bridge.EngineBridge
import com.blackmarlin.downloader.ui.NetworkPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class NetworkLease(val network: Network, val generation: Long, val policy: NetworkPolicy, val allowMetered: Boolean) {
    val valid = AtomicBoolean(true)
    val handle get() = network.networkHandle
}

internal interface NetworkGateway {
    fun select(policy: NetworkPolicy, allowMetered: Boolean): Network?
    fun admit(network: Network, policy: NetworkPolicy, allowMetered: Boolean): NetworkLease?
    fun release(lease: NetworkLease)
    fun metered(network: Network): Boolean
    fun eligible(network: Network, policy: NetworkPolicy, allowMetered: Boolean): Boolean
}

class NetworkController(context: Context, private val engine: EngineBridge, private val scope: CoroutineScope) : NetworkGateway {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val sequence = AtomicLong(1)
    private val leases = ConcurrentHashMap<Long, NetworkLease>()
    private val blocked = ConcurrentHashMap<Network, Boolean>()
    init {
        manager.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { blocked.remove(network); invalidate(network) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                leases.values.filter { it.network == network && !eligibleCaps(caps, it.policy, it.allowMetered) }.forEach(::invalidate)
            }
            override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) {
                blocked[network] = isBlocked
                if (isBlocked) invalidate(network)
            }
        })
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) leases.values.filter { it.network != network }.forEach(::invalidate)
            }
            override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) {
                blocked[network] = isBlocked
                if (isBlocked) invalidate(network)
            }
        })
    }
    private fun eligibleCaps(caps: NetworkCapabilities, policy: NetworkPolicy, allowMetered: Boolean) =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (policy == NetworkPolicy.ANY || allowMetered || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    override fun eligible(network: Network, policy: NetworkPolicy, allowMetered: Boolean): Boolean {
        if (blocked[network] == true) return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        val active = manager.activeNetwork
        if (active != null && active != network && manager.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return false
        return eligibleCaps(caps, policy, allowMetered)
    }
    override fun metered(network: Network) = manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
    override fun select(policy: NetworkPolicy, allowMetered: Boolean): Network? {
        val active = manager.activeNetwork
        // An active VPN must not silently be bypassed by selecting its underlying Wi-Fi.
        if (active != null && manager.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            return active.takeIf { eligible(it, policy, allowMetered) }
        }
        return (listOfNotNull(active) + manager.allNetworks.toList()).distinct().firstOrNull { eligible(it, policy, allowMetered) }
    }
    fun anyNetwork() = manager.activeNetwork != null
    override fun admit(network: Network, policy: NetworkPolicy, allowMetered: Boolean): NetworkLease? {
        if (!eligible(network, policy, allowMetered)) return null
        val lease = NetworkLease(network, sequence.getAndIncrement(), policy, allowMetered)
        leases[lease.generation] = lease
        if (!eligible(network, policy, allowMetered)) { invalidate(lease); leases.remove(lease.generation) }
        return lease.takeIf { it.valid.get() }
    }
    override fun release(lease: NetworkLease) { leases.remove(lease.generation); lease.valid.set(false) }
    private fun invalidate(network: Network) { leases.values.filter { it.network == network }.forEach(::invalidate) }
    private fun invalidate(lease: NetworkLease) {
        if (lease.valid.compareAndSet(true, false)) scope.launch { runCatching { engine.invalidateNetwork(lease.handle, lease.generation) } }
    }
}
