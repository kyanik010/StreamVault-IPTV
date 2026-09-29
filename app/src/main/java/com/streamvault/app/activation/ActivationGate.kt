package com.streamvault.app.activation

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.streamvault.app.BuildConfig
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.domain.usecase.XtreamProviderSetupCommand
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private enum class ActivationState {
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
    var state by remember { mutableStateOf(ActivationState.CHECKING) }
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
                expiresAt = response.expiresAt

                if (!response.activated) {
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
                    val hasProvider = providerRepository.getProviders().first().any {
                        it.type == ProviderType.XTREAM_CODES
                    }
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
                val existing = providerRepository.getProviders().first().firstOrNull {
                    it.type == ProviderType.XTREAM_CODES &&
                        it.serverUrl.trimEnd('/') == video.serverUrl.trimEnd('/') &&
                        it.username == video.username &&
                        it.password == video.password
                }
                if (existing == null) {
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
                config.audio?.m3uUrl?.takeIf { it.isNotBlank() }?.let { audioUrl ->
                    context.getSharedPreferences("streamvault_audio_source", Context.MODE_PRIVATE)
                        .edit().putString("m3u_url", audioUrl).apply()
                }
                showForm = false
                state = ActivationState.ACTIVE
            }
            .onFailure {
                if (previousState != ActivationState.TRIAL && previousState != ActivationState.ACTIVE) {
                    state = ActivationState.ERROR
                    errorText = "تعذر الاتصال بخادم التفعيل"
                }
            }
    }

    suspend fun saveTrialCredentials(host: String, username: String, password: String): Boolean {
        state = ActivationState.ACTIVATING
        errorText = null
        val result = withContext(Dispatchers.IO) {
            validateAndAddProvider.loginXtream(
                XtreamProviderSetupCommand(
                    serverUrl = host.trim(),
                    username = username.trim(),
                    password = password,
                    name = "Live TV",
                    xtreamFastSyncEnabled = true
                )
            )
        }
        return if (result is ValidateAndAddProviderResult.Success ||
            result is ValidateAndAddProviderResult.SavedWithWarning
        ) {
            showForm = false
            state = ActivationState.TRIAL
            true
        } else {
            state = ActivationState.TRIAL
            errorText = "تعذر حفظ بيانات الاشتراك. تأكد من Host واسم المستخدم وكلمة المرور."
            false
        }
    }

    LaunchedEffect(activationId) {
        check()
        while (isActive) {
            delay(60_000)
            check()
        }
    }

    val hasTrialProvider by produceState(initialValue = false, state) {
        value = providerRepository.getProviders().first().any {
            it.type == ProviderType.XTREAM_CODES
        }
    }

    if ((state == ActivationState.ACTIVE || (state == ActivationState.TRIAL && hasTrialProvider)) && !showForm) {
        content()
    } else {
        ActivationScreen(
            activationId = activationId,
            state = state,
            expiresAt = expiresAt,
            errorText = errorText,
            showForm = showForm,
            onStart = { showForm = true },
            onSave = { host, username, password ->
                scope.launch { saveTrialCredentials(host, username, password) }
            },
            onRetry = { scope.launch { check() } }
        )
    }
}

@Composable
private fun ActivationScreen(
    activationId: String,
    state: ActivationState,
    expiresAt: String?,
    errorText: String?,
    showForm: Boolean,
    onStart: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onRetry: () -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val daysRemaining = remember(expiresAt) {
        expiresAt?.let {
            runCatching {
                maxOf(0L, ChronoUnit.DAYS.between(Instant.now(), Instant.parse(it)) + 1)
            }.getOrNull()
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF080A0F), Color(0xFF10141C), Color(0xFF080A0F))
                )
            )
            .padding(horizontal = 36.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            Modifier.widthIn(max = 700.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xEE151A23)),
            border = BorderStroke(1.dp, Color(0x334D7CFE))
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Image(
                    painterResource(com.streamvault.app.R.drawable.eagle_x_activation_logo),
                    "Eagle X2",
                    Modifier.size(112.dp)
                )
                Text(
                    "Eagle X2",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF10141C))
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("معرّف الجهاز", color = Color(0xFFA7AFBF), fontWeight = FontWeight.Medium)
                        Text(activationId, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                when {
                    state == ActivationState.EXPIRED -> {
                        Text(
                            "عذراً، لقد انتهت فترتك التجريبية",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    }
                    showForm -> {
                        Text(
                            "إضافة الاشتراك",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            "أدخل بيانات Xtream Codes للبدء",
                            color = Color(0xFFA7AFBF),
                            textAlign = TextAlign.Center
                        )
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Host") }
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Username") }
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation()
                        )
                        Button(
                            onClick = { onSave(host, username, password) },
                            enabled = host.isNotBlank() && username.isNotBlank() && password.isNotBlank() &&
                                state != ActivationState.ACTIVATING,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("حفظ", fontWeight = FontWeight.Bold)
                        }
                    }
                    state == ActivationState.TRIAL -> {
                        Text(
                            "مرحباً بك 👋",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            "لديك فترة تجريبية مجانية لمدة 7 أيام",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFA7AFBF),
                            textAlign = TextAlign.Center
                        )
                        if (daysRemaining != null) {
                            Text(
                                "متبقي $daysRemaining أيام",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6BE7F2)
                            )
                        }
                        Button(
                            onClick = onStart,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("اضغط هنا لتبدأ", fontWeight = FontWeight.Bold)
                        }
                    }
                    else -> {
                        val statusText = when (state) {
                            ActivationState.CHECKING -> "جاري التحقق..."
                            ActivationState.ACTIVATING -> "جاري تجهيز الاشتراك..."
                            ActivationState.NOT_REGISTERED -> "جهاز جديد"
                            ActivationState.SUSPENDED -> "هذا الجهاز موقوف"
                            ActivationState.ERROR -> errorText ?: "تعذر التحقق من الجهاز"
                            else -> ""
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                if (errorText != null && state != ActivationState.EXPIRED) {
                    Text(
                        errorText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFF9B9B),
                        textAlign = TextAlign.Center
                    )
                }

                Text(
                    "لتفعيل التطبيق أو الحصول على اشتراك IPTV\nتواصل مع الدعم عبر مسح رمز QR",
                    Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFD1D7E0),
                    textAlign = TextAlign.Center
                )
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Image(
                        painterResource(com.streamvault.app.R.drawable.eagle_x_support_qr),
                        "Support QR Code",
                        Modifier.size(176.dp).padding(9.dp)
                    )
                }
                if (state == ActivationState.EXPIRED || state == ActivationState.ERROR ||
                    state == ActivationState.NOT_REGISTERED || state == ActivationState.SUSPENDED
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("إعادة التحقق", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private data class ActivationResponse(
    val activated: Boolean,
    val status: String?,
    val expiresAt: String?,
    val config: ManagedActivationConfig?
)

private fun requestActivation(activationId: String): ActivationResponse {
    val connection = (URL(BuildConfig.DEVICE_ACTIVATION_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 10_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
    }
    try {
        connection.outputStream.use {
            it.write(
                JSONObject().put("device_id", activationId)
                    .toString().toByteArray(Charsets.UTF_8)
            )
        }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val json = JSONObject(body)
        return ActivationResponse(
            activated = json.optBoolean("activated", false),
            status = json.optString("status").takeIf(String::isNotBlank),
            expiresAt = json.optString("expires_at").takeIf(String::isNotBlank),
            config = json.optJSONObject("config")?.let { c ->
                ManagedActivationConfig(
                    c.optString("expires_at").takeIf(String::isNotBlank),
                    c.optJSONObject("video")?.let {
                        ManagedVideoConfig(
                            it.optString("server_url"),
                            it.optString("username"),
                            it.optString("password")
                        )
                    },
                    c.optJSONObject("audio")?.let {
                        ManagedAudioConfig(it.optString("m3u_url").takeIf(String::isNotBlank))
                    }
                )
            }
        )
    } finally {
        connection.disconnect()
    }
}

private fun readActivationId(context: Context): String {
    val prefs = context.getSharedPreferences("streamvault_device_identity", Context.MODE_PRIVATE)
    val stored = prefs.getString("device_id", null)
    if (!stored.isNullOrBlank()) return stored

    // Android ID is scoped to the device/user and normally survives app
    // uninstall/reinstall. It is preferable to Wi-Fi MAC, which Android
    // restricts/randomizes on modern releases.
    val androidId = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    ) ?: Build.FINGERPRINT

    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(("streamvault-device:" + androidId).toByteArray(Charsets.UTF_8))

    val token = digest.take(9).joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
    val deviceId = "EV-" + token.chunked(3).joinToString("")

    prefs.edit().putString("device_id", deviceId).apply()
    return deviceId
}

private fun ByteArray.toMac(): String =
    joinToString(":") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
