package com.example.roboguardandroid


import android.content.Context
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory


@Serializable
data class AuthResponse(
    val id: Int,
    val secret: String
)

class RobotAPI(private val context: Context) {
    internal var isCoupled: Boolean = false
    var robotIP: String? = null
    private var client: HttpClient? = null

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPrefs = EncryptedSharedPreferences.create(
        context,
        "robot_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init {
        val savedIp = sharedPrefs.getString("robot_ip", null)
        val savedCert = sharedPrefs.getString("robot_cert", null)
        if (savedIp != null && savedCert != null) {
            robotIP = savedIp
            setupHttpClient(savedCert)
            isCoupled = true
        }
    }

    private fun setupHttpClient(certBase64: String) {
        try {
            // 1. CRITICAL FIX: Bereinigen des Strings
            // Entfernt Header, Footer und alle Whitespaces/Newlines, die den Base64-Decoder verwirren
            val cleanCert = certBase64
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\\s".toRegex(), "") // Entfernt \n, \r und Leerzeichen

            // 2. Decoding
            val cf = CertificateFactory.getInstance("X.509")
            val certBytes = android.util.Base64.decode(cleanCert, android.util.Base64.DEFAULT)
            val certificate = cf.generateCertificate(ByteArrayInputStream(certBytes))

            // 3. KeyStore erstellen
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("robot_ca", certificate)
            }

            // 4. TrustManager initialisieren
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }

            // 5. SSL Context erstellen
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, tmf.trustManagers, null)
            }

            // 6. HTTP Client konfigurieren
            client = HttpClient(Android) {
                install(ContentNegotiation) { json() }
                engine {
                    sslManager = { connection ->
                        connection.sslSocketFactory = sslContext.socketFactory
                        // Hostname Verification ausschalten (nötig für lokale IPs)
                        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RobotAPI", "Failed to setup SSL client: ${e.message}")
            e.printStackTrace()
            // Optional: isCoupled auf false setzen, damit die UI weiß, dass es fehlgeschlagen ist
            isCoupled = false
        }
    }

    fun completePairing(ip: String, certBase64: String) {
        sharedPrefs.edit().putString("robot_ip", ip).putString("robot_cert", certBase64).apply()
        this.robotIP = ip
        setupHttpClient(certBase64)
        this.isCoupled = true
    }

    internal suspend fun pingRobot(): String = withContext(Dispatchers.IO) {
        // FIXED: Added safe call ?.
        client?.get("https://$robotIP:8443/ping")?.body() ?: "Client not initialized"
    }

    internal suspend fun dataToRobot(data: String): HttpResponse = withContext(Dispatchers.IO) {
        requireCoupled()
        // FIXED: Added non-null assertion !!. (safe because requireCoupled checked it)
        client!!.post("https://$robotIP:8443/save") {
            contentType(ContentType.Text.Plain)
            setBody(data)
        }
    }

    internal suspend fun secrethandshake(otp: String): AuthResponse = withContext(Dispatchers.IO) {
        requireCoupled()
        // FIXED: Added non-null assertion !!.
        val response: HttpResponse = client!!.post("https://$robotIP:8443/otp_auth") {
            headers {
                append("X-Client-otp", otp)
                append("X-Client-name", getDeviceName()) // Context no longer needed here
            }
        }
        Json.decodeFromString(response.bodyAsText())
    }

    internal fun requireCoupled() {
        if (!isCoupled || client == null) {
            throw IllegalStateException("No Robot Coupled")
        }
    }

    // FIXED: Removed context parameter as we already have 'context' as a class property
    fun getDeviceName(): String {
        return Settings.Global.getString(
            context.contentResolver,
            Settings.Global.DEVICE_NAME
        ) ?: "Unknown Device"
    }
}