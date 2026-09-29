package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamvault.feature.settings.R
import com.streamvault.feature.settings.api.SettingsVpnPort

@Composable
internal fun SettingsVpnSection(
    vpnPort: SettingsVpnPort,
    modifier: Modifier = Modifier,
) {
    val state = vpnPort.state.collectAsStateWithLifecycle().value
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8),
    ) {
        SwitchSettingsRow(
            title = stringResource(R.string.settings_vpn_enable),
            subtitle = when {
                state.running -> stringResource(R.string.settings_vpn_connected, state.proxyPort ?: 0)
                state.errorMessage != null -> state.errorMessage
                else -> stringResource(R.string.settings_vpn_disconnected)
            },
            checked = state.enabled,
            onCheckedChange = vpnPort::setEnabled,
        )
    }
}
