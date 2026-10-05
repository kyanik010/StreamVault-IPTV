package com.streamvault.app.activation

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.app.BuildConfig
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.domain.usecase.XtreamProviderSetupCommand
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class ActivationState {
    CHECKING, TRIAL, ACTIVATING, NOT_REGISTERED, ACTIVE, SUSPENDED, EXPIRED, ERROR
}

@Composable
fun ActivationGate(
    providerRepository: ProviderRepository,
    validateAndAddProvider: ValidateAndAddProvider,
    configStore: ActivationConfigStore,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activationId = remember { readActivationId(context) }
    val activationPrefs = remember {
        context.getSharedPreferences("streamvault_activation_state", Context.MODE_PRIVATE)
    }
    var state by remember {
        mutableStateOf(
            if (activationPrefs.getBoolean("last_active", false)) ActivationState.ACTIVE
            else ActivationState.CHECKING
        )
    }
    var expiresAt by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showForm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun check() {
        val previousState = state
        if (previousState != ActivationState.ACTIVE && previousState != ActivationState.TRIAL) {
            state = ActivationState.CHECKING
        }
        errorText = null

        runCatching { withContext(Dispatchers.IO) { requestActivation(activationId) } }
            .onSuccess { response ->
                activationPrefs.edit().putLong("last_check_at", System.currentTimeMillis()).apply()
                expiresAt = response.expiresAt
                if (response.activated) retryPendingRegistration(context, activationId)

                if (!response.activated) {
                    activationPrefs.edit().putBoolean("last_active", false).apply()
                    showForm = false
                    state = when (response.status) {
                        "expired" -> ActivationState.EXPIRED
                        "suspended" -> ActivationState.SUSPENDED
                        else -> ActivationState.NOT_REGISTERED
                    }
                    return@onSuccess
                }

                if (response.status == "trial" && response.config == null) {
                    state = ActivationState.TRIAL
                    activationPrefs.edit().putBoolean("last_active", true).apply()
                    showForm = false
                    return@onSuccess
                }

                val config = response.config
                if (config?.video == null) {
                    state = ActivationState.ERROR
                    errorText = "بيانات التفعيل غير مكتملة"
                    return@onSuccess
                }

                configStore.set(config)
                state = ActivationState.ACTIVATING
                val video = config.video
                val existing = withContext(Dispatchers.IO) {
                    providerRepository.hasMatchingXtreamProvider(
                        serverUrl = video.serverUrl,
                        username = video.username,
                        password = video.password
                    )
                }
                if (!existing) {
                    val addResult = withContext(Dispatchers.IO) {
                        validateAndAddProvider.loginXtream(
                            XtreamProviderSetupCommand(
                                serverUrl = video.serverUrl,
                                username = video.username,
                                password = video.password,
                                name = "Live TV",
                                xtreamFastSyncEnabled = true
                            )
                        )
                    }
                    if (addResult !is ValidateAndAddProviderResult.Success &&
                        addResult !is ValidateAndAddProviderResult.SavedWithWarning) {
                        state = ActivationState.ERROR
                        errorText = "تعذر تحميل خدمة الفيديو"
                        return@onSuccess
                    }
                }
                context.getSharedPreferences("streamvault_audio_source", Context.MODE_PRIVATE)
                    .edit()
                    .apply {
                        val audioUrl = config.audio?.m3uUrl?.trim().orEmpty()
                        if (audioUrl.isBlank()) remove("m3u_url") else putString("m3u_url", audioUrl)
                    }
                activationPrefs.edit().putBoolean("last_active", true).apply()
                showForm = false
                state = ActivationState.ACTIVE
            }
            .onFailure {
                activationPrefs.edit().putLong("last_check_at", System.currentTimeMillis()).apply()
                if (previousState != ActivationState.TRIAL && previousState != ActivationState.ACTIVE) {
                    state = ActivationState.ERROR
                    errorText = "تعذر الاتصال بخادم التفعيل"
                }
            }
    }

    suspend fun saveTrialCredentials(host: String, username: String, password: String): Boolean {
        state = ActivationState.ACTIVATING
        errorText = null
        val cleanHost = host.trim()
        val cleanUsername = username.trim()
        val registration = withContext(Dispatchers.IO) {
            registerTrialCredentials(activationId, cleanHost, cleanUsername, password)
        }
        if (!registration) {
            state = ActivationState.TRIAL
            errorText = "تعذر تسجيل بيانات الاشتراك في لوحة الإدارة."
            return false
        }
        context.getSharedPreferences("streamvault_trial_registration", Context.MODE_PRIVATE).edit()
            .putString("host", cleanHost).putString("username", cleanUsername).putString("password", password).apply()
        val result = withContext(Dispatchers.IO) {
            validateAndAddProvider.loginXtream(
                XtreamProviderSetupCommand(cleanHost, cleanUsername, password, "Live TV", xtreamFastSyncEnabled = true)
            )
        }
        return if (result is ValidateAndAddProviderResult.Success || result is ValidateAndAddProviderResult.SavedWithWarning) {
            context.getSharedPreferences("streamvault_trial_registration", Context.MODE_PRIVATE).edit().clear().apply()
            showForm = false
            state = ActivationState.TRIAL
            true
        } else {
            state = ActivationState.TRIAL
            errorText = "تم تسجيل الاشتراك، لكن تعذر تجهيز خدمة الفيديو محلياً."
            false
        }
    }

    LaunchedEffect(activationId) {
        val now = System.currentTimeMillis()
        val lastCheckAt = activationPrefs.getLong("last_check_at", 0L)
        val revalidationWindowMs = 24L * 60L * 60L * 1000L
        val audioPrefs = context.getSharedPreferences("streamvault_audio_source", Context.MODE_PRIVATE)
        val hasManagedAudioM3u = audioPrefs.getString("m3u_url", null)?.trim().orEmpty().isNotBlank()
        val shouldRefreshManagedAudio = activationPrefs.getBoolean("last_active", false) && !hasManagedAudioM3u
        if (now - lastCheckAt >= revalidationWindowMs || shouldRefreshManagedAudio) check()
        while (isActive) {
            delay(revalidationWindowMs)
            check()
        }
    }

    val hasTrialProvider by produceState(initialValue = false, state) {
        value = providerRepository.getProviders().first().any { it.type == ProviderType.XTREAM_CODES }
    }
    if ((state == ActivationState.ACTIVE || (state == ActivationState.TRIAL && hasTrialProvider)) && !showForm) {
        content()
    } else {
        ActivationScreen(
            activationId = activationId, state = state, expiresAt = expiresAt, errorText = errorText,
            showForm = showForm, onStart = { showForm = true },
            onSave = { host, username, password -> scope.launch { saveTrialCredentials(host, username, password) } },
            onRetry = { scope.launch { check() } }
        )
    }
}

// The existing ActivationScreen and helper functions below are unchanged.
