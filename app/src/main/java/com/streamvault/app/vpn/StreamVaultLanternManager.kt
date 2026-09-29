package com.streamvault.app.vpn

import android.content.Context
import com.streamvault.feature.settings.api.SettingsVpnPort
import com.streamvault.feature.settings.api.SettingsVpnState
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lantern.sdk.LanternManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamVaultLanternManager @Inject constructor(
    @ApplicationContext context: Context,
) : SettingsVpnPort {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val proxyRef = MutableStateFlow<Proxy?>(null)
    private val _state = MutableStateFlow(SettingsVpnState())
    override val state: StateFlow<SettingsVpnState> = _state.asStateFlow()

    init {
        LanternManager.setup(
            context = context,
            appName = "StreamVault",
            customConfigDir = context.filesDir.resolve("lantern_config").absolutePath,
        )
    }

    override fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled, errorMessage = null)
        if (enabled) start() else stop()
    }

    fun proxyFor(uri: URI): Proxy? = proxyRef.value

    private fun start() {
        scope.launch {
            val result = runCatching {
                LanternManager.startLantern("127.0.0.1:0", proxyAll = true)
            }.getOrNull()
            if (result == null) {
                proxyRef.value = null
                _state.value = SettingsVpnState(
                    enabled = true,
                    running = false,
                    errorMessage = "تعذر تشغيل VPN داخل التطبيق.",
                )
                return@launch
            }
            proxyRef.value = Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress(result.hostString, result.port),
            )
            _state.value = SettingsVpnState(
                enabled = true,
                running = true,
                proxyPort = result.port,
            )
        }
    }

    private fun stop() {
        scope.launch {
            runCatching { LanternManager.stopLantern() }
            proxyRef.value = null
            _state.value = SettingsVpnState()
        }
    }
}

@Singleton
class StreamVaultLanternProxySelector @Inject constructor(
    private val lanternManager: StreamVaultLanternManager,
) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        return lanternManager.proxyFor(uri)?.let(::listOf) ?: listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(
        uri: URI,
        sa: java.net.SocketAddress,
        ioe: java.io.IOException,
    ) = Unit
}
