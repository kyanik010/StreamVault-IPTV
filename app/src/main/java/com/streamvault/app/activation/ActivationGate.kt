package com.streamvault.app.activation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

private enum class ActivationState { CHECKING, ACTIVATING, NOT_REGISTERED, ACTIVE, SUSPENDED, EXPIRED, ERROR }

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
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun check() {
        state = ActivationState.CHECKING
        errorText = null
        runCatching { withContext(Dispatchers.IO) { requestActivation(activationId) } }
            .onSuccess { response ->
                if (!response.activated) {
                    state = when (response.status) {
                        "expired" -> ActivationState.EXPIRED
                        "suspended" -> ActivationState.SUSPENDED
                        else -> ActivationState.NOT_REGISTERED
                    }
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
                state = ActivationState.ACTIVE
            }
            .onFailure {
                state = ActivationState.ERROR
                errorText = "تعذر الاتصال بخادم التفعيل"
            }
    }

    LaunchedEffect(activationId) {
        check()
        while (isActive) {
            delay(30_000)
            if (state != ActivationState.ACTIVE) check()
        }
    }

    if (state == ActivationState.ACTIVE) content()
    else ActivationScreen(activationId, state, errorText) { scope.launch { check() } }
}

@Composable
private fun ActivationScreen(
    activationId: String,
    state: ActivationState,
    errorText: String?,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val statusText = when (state) {
        ActivationState.CHECKING -> "جاري التحقق من الجهاز..."
        ActivationState.ACTIVATING -> "جاري تجهيز الاشتراك..."
        ActivationState.NOT_REGISTERED -> "هذا الجهاز غير مفعّل"
        ActivationState.SUSPENDED -> "هذا الجهاز موقوف"
        ActivationState.EXPIRED -> "انتهى تفعيل هذا الجهاز"
        ActivationState.ERROR -> errorText ?: "تعذر التحقق من الجهاز"
        ActivationState.ACTIVE -> ""
    }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF050912), Color(0xFF0A1220), Color(0xFF03060B))))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            Modifier.widthIn(max = 620.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xCC0D1522)),
            border = BorderStroke(1.dp, Color(0x335BE7F2))
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(painterResource(com.streamvault.app.R.drawable.eagle_x_activation_logo), "Eagle X", Modifier.size(148.dp))
                Text("Eagle X", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                Text("عنوان MAC", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFFB8C0CC))
                Card(
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x332C3C4D)),
                    border = BorderStroke(1.dp, Color(0x556BE7F2))
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(activationId, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                        OutlinedButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("MAC Address", activationId))
                            Toast.makeText(context, "تم نسخ العنوان", Toast.LENGTH_SHORT).show()
                        }, shape = RoundedCornerShape(12.dp)) { Text("نسخ") }
                    }
                }
                Card(
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x44131D29))
                ) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), contentAlignment = Alignment.Center) {
                        Text(statusText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            color = Color.White, textAlign = TextAlign.Center)
                    }
                }
                Text("للتفعيل أو الحصول على اشتراك IPTV\nتواصل مع الدعم عبر مسح رمز QR",
                    Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium, color = Color(0xFFD1D7E0), textAlign = TextAlign.Center)
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Image(painterResource(com.streamvault.app.R.drawable.eagle_x_support_qr), "Support QR Code",
                        Modifier.size(176.dp).padding(9.dp))
                }
                Button(onClick = onRetry, Modifier.fillMaxWidth().height(52.dp),
                    enabled = state != ActivationState.CHECKING && state != ActivationState.ACTIVATING,
                    shape = RoundedCornerShape(16.dp)) { Text("إعادة التحقق", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private data class ActivationResponse(val activated: Boolean, val status: String?, val config: ManagedActivationConfig?)

private fun requestActivation(activationId: String): ActivationResponse {
    val connection = (URL(BuildConfig.DEVICE_ACTIVATION_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 10_000; doOutput = true
        setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
    }
    try {
        connection.outputStream.use { it.write(JSONObject().put("mac_address", activationId).toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val json = JSONObject(body)
        return ActivationResponse(json.optBoolean("activated", false), json.optString("status").takeIf(String::isNotBlank),
            json.optJSONObject("config")?.let { c ->
                ManagedActivationConfig(
                    c.optString("expires_at").takeIf(String::isNotBlank),
                    c.optJSONObject("video")?.let { ManagedVideoConfig(it.optString("server_url"), it.optString("username"), it.optString("password")) },
                    c.optJSONObject("audio")?.let { ManagedAudioConfig(it.optString("m3u_url").takeIf(String::isNotBlank)) }
                )
            })
    } finally { connection.disconnect() }
}

private fun readActivationId(context: Context): String {
    for (name in listOf("wlan0", "eth0", "en0")) {
        val mac = runCatching { NetworkInterface.getByName(name)?.hardwareAddress?.toMac() }.getOrNull()
        if (!mac.isNullOrBlank() && mac != "02:00:00:00:00:00") return mac
    }
    val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
    for (networkInterface in interfaces) {
        val mac = runCatching { networkInterface.hardwareAddress?.toMac() }.getOrNull()
        if (!mac.isNullOrBlank() && mac != "02:00:00:00:00:00") return mac
    }
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: Build.FINGERPRINT
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(("aeriotv-android:" + androidId).toByteArray(Charsets.UTF_8))
    digest.copyOf(6).also { it[0] = (it[0].toInt() and 0xFC or 0x02).toByte() }
        .joinToString(":") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
}
private fun ByteArray.toMac(): String = joinToString(":") { "%02X".format(Locale.US, it.toInt() and 0xFF) }