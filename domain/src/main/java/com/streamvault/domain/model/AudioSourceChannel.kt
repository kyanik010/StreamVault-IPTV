package com.streamvault.domain.model

data class AudioSourceChannel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
)
