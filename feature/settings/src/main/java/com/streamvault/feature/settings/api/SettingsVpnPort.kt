package com.streamvault.feature.settings.api

import kotlinx.coroutines.flow.StateFlow

data class SettingsVpnState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val proxyPort: Int? = null,
    val errorMessage: String? = null,
)

interface SettingsVpnPort {
    val state: StateFlow<SettingsVpnState>
    fun setEnabled(enabled: Boolean)
}
