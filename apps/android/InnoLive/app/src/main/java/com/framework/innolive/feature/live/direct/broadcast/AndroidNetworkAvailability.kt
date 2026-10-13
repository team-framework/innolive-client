package com.framework.innolive.feature.live.direct.broadcast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.framework.innolive.feature.live.hasValidatedInternet

/**
 * 기본 네트워크 콜백으로 검증된 인터넷을 알립니다. 등록에 실패해도 [isAvailable] 확인은 동작하며,
 * 재연결 감시자는 주기 확인으로 이어 갑니다.
 */
internal class AndroidNetworkAvailability(context: Context) : NetworkAvailability {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var closed = false
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun isAvailable(): Boolean = try {
        val manager = connectivityManager ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        hasValidatedInternet(capabilities::hasCapability)
    } catch (_: RuntimeException) {
        // 상태를 알 수 없으면 재연결을 막지 않습니다.
        true
    }

    override fun start(onAvailable: () -> Unit) {
        val onValidated = onAvailable
        val manager = connectivityManager ?: return
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isAvailable()) onValidated()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (hasValidatedInternet(capabilities::hasCapability)) onValidated()
            }
        }
        synchronized(lock) {
            if (closed || callback != null) return
            try {
                manager.registerDefaultNetworkCallback(networkCallback)
                callback = networkCallback
            } catch (_: RuntimeException) {
                // 콜백 수 제한 등으로 실패하면 주기 확인만 씁니다.
            }
        }
    }

    override fun close() {
        val networkCallback = synchronized(lock) {
            closed = true
            callback.also { callback = null }
        } ?: return
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
    }
}
