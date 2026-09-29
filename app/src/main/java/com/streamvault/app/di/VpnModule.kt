package com.streamvault.app.di

import com.streamvault.app.vpn.StreamVaultLanternManager
import com.streamvault.app.vpn.StreamVaultLanternProxySelector
import com.streamvault.feature.settings.api.SettingsVpnPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.net.ProxySelector
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VpnModule {
    @Binds
    @Singleton
    abstract fun bindSettingsVpnPort(manager: StreamVaultLanternManager): SettingsVpnPort

    @Binds
    @Singleton
    abstract fun bindProxySelector(selector: StreamVaultLanternProxySelector): ProxySelector
}
