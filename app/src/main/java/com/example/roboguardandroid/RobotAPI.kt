package com.example.roboguardandroid

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager


@Serializable
data class AuthCred(
    val id: Long,
    val secret: String
)

@Serializable
data class RobotCapabilities(
    val sensors: List<String> = listOf("Camera", "LIDAR", "Ultrasonic", "Collisionsensor", "Microfon"),
    val rooms: List<String> = listOf("Living Room", "Bedroom", "Bath", "Other Rooms"),
    val situational: List<String> = listOf("Discretion Mode", "pixelate objects")
)

class RobotAPI(private val context: Context) {
    internal var isCoupled: Boolean = false
    var robotIP: String? = null

    private var currentResolvedIp: String? = null
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
        try {
            val savedIp = sharedPrefs.getString("robot_ip", null)
            val savedCert = sharedPrefs.getString("robot_cert", null)

            if (!savedIp.isNullOrBlank() && !savedCert.isNullOrBlank()) {
                this.robotIP = savedIp
                setupHttpClient(savedCert)
                this.isCoupled = sharedPrefs.contains("shared_secret") && this.client != null
            }
        } catch (e: Exception) {
            Log.e("RobotAPI", "Error loading SecurePrefs: ${e.message}")
        }
    }

    fun uncoupleRobot() {
        sharedPrefs.edit().clear().apply()
        this.robotIP = null
        this.client = null
        this.isCoupled = false
        Log.i("Uncouple", "Uncoupled successfully")
    }

    private fun setupHttpClient(certBase64: String) {
        try {
            val cleanCert = certBase64
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\\s".toRegex(), "")

            val cf = CertificateFactory.getInstance("X.509")
            val certBytes = android.util.Base64.decode(cleanCert, android.util.Base64.DEFAULT)
            val serverCert = cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

            // custom Trust Manager
            val customTrustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val receivedCert = chain?.get(0) ?: throw CertificateException("No Cert")

                    // Server Key: ensuring proper format
                    val receivedKeyBase64 = android.util.Base64.encodeToString(
                        receivedCert.publicKey.encoded,
                        android.util.Base64.NO_WRAP
                    ).trim()

                    // extract key
                    val expectedKeyBase64 = android.util.Base64.encodeToString(
                        serverCert.publicKey.encoded,
                        android.util.Base64.NO_WRAP
                    ).trim()


                    android.util.Log.d("SSL_DEBUG", "SERVER: $receivedKeyBase64")
                    android.util.Log.d("SSL_DEBUG", "QR-CODE: $expectedKeyBase64")

                    if (receivedKeyBase64 != expectedKeyBase64) {
                        if ((receivedKeyBase64 == expectedKeyBase64) || (expectedKeyBase64== receivedKeyBase64)) {
                            android.util.Log.w("SSL_DEBUG", "Keys correspond")
                            return
                        }
                        throw CertificateException("Key Mismatch!")
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(customTrustManager), java.security.SecureRandom())
            }

            client = HttpClient(OkHttp) {
                install(ContentNegotiation) { json() }
                engine {
                    config {
                        // Allow TLS 1.2, 1.3, Cleartext as Fallback
                        val specs = listOf(
                            okhttp3.ConnectionSpec.MODERN_TLS,
                            okhttp3.ConnectionSpec.CLEARTEXT
                        )
                        connectionSpecs(specs)

                        // enforce 1.1 because of Orionstar robot
                        protocols(listOf(okhttp3.Protocol.HTTP_1_1))

                        sslSocketFactory(sslContext.socketFactory, customTrustManager)

                        // accept IP address as hostname
                        hostnameVerifier { _, _ -> true }
                    }
                }
            }

            android.util.Log.d("RobotAPI", "SSL Client (Public Key Pinning) succesfull")

        } catch (e: Exception) {
            android.util.Log.e("RobotAPI", "Failed to setup SSL client: ${e.message}")
            e.printStackTrace()
        }
    }

    fun completePairing(ip: String, certBase64: String) {
        this.robotIP = ip
        setupHttpClient(certBase64)

        if (this.client != null) {
            sharedPrefs.edit()
                .putString("robot_ip", ip)
                .putString("robot_cert", certBase64)
                .putBoolean("is_coupled", true)
                .apply()
            this.isCoupled = true
            Log.d("RobotAPI", "Pairing succesfull with robot. IP:: $ip")
        } else {
            Log.e("RobotAPI", "Client could pair with robot.")
        }
    }

    internal suspend fun pingRobot(): String = withContext(Dispatchers.IO) {
        ensureConnection()
        val response = client?.get("https://$currentResolvedIp:8443/ping")?.body() ?: "Client not initialized"
        Log.i("Server", "Ping: $response")
        response
    }

    internal suspend fun dataToRobot(data: String): HttpResponse = withContext(Dispatchers.IO) {
        requireCoupled()
        ensureConnection()

        val signature = createSignature(data, getSharedSecret() ?: "")
        Log.i("RobotAPI", "Signature created")

        val response = client!!.post("https://${currentResolvedIp}:8443/save") {
            headers {
                append("X-Client-Id", getId().toString())
                append("X-Client-Secret", signature)
            }
            contentType(ContentType.Text.Plain)
            setBody(data)
        }

        Log.d("RobotAPI", "Server Response: $response")
        response
    }

    internal suspend fun secrethandshake(otp: String): Boolean = withContext(Dispatchers.IO) {
        requireCoupled()
        ensureConnection()
        try {
            val response: HttpResponse = client!!.post("https://$currentResolvedIp:8443/otp_auth") {
                headers {
                    append("X-Client-otp", otp)
                    append("X-Client-name", getDeviceName())
                }
            }
            if (response.status == HttpStatusCode.OK) {
                val creds = response.body<AuthCred>()

                sharedPrefs.edit()
                    .putString("shared_secret", creds.secret)
                    .putLong("client_id", creds.id)
                    .apply()

                isCoupled = true
                Log.i("Auth", "Shared Secret securely stored")
                true
            } else {
                Log.w("Auth", "Authentification failed ${response.status}")
                uncoupleRobot()
                false
            }
        } catch (e: Exception) {
            Log.e("Auth", "Network Error in handshake ${e.message}")
            uncoupleRobot()
            false
        }
    }

    suspend fun fetchRobotCapabilities(): RobotCapabilities? = withContext(Dispatchers.IO) {
        requireCoupled()
        ensureConnection()
        try {
            val response: HttpResponse = client!!.get("https://$currentResolvedIp:8443/capabilities") {
                headers {
                    append("X-Client-Id", getId().toString())
                    append("X-Client-Secret", createSignature("", getSharedSecret() ?: ""))
                }
            }
            if (response.status == HttpStatusCode.OK) {
                val capabilities = response.body<RobotCapabilities>()
                saveCapabilities(capabilities)
                capabilities
            } else {
                Log.e("RobotAPI", "Failed to fetch capabilities: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e("RobotAPI", "Error fetching capabilities: ${e.message}")
            null
        }
    }

    private fun saveCapabilities(capabilities: RobotCapabilities) {
        val json = Json.encodeToString(capabilities)
        sharedPrefs.edit().putString("robot_capabilities", json).apply()
    }

    fun getSavedCapabilities(): RobotCapabilities {
        val json = sharedPrefs.getString("robot_capabilities", null) ?: return RobotCapabilities()
        return try {
            Json.decodeFromString<RobotCapabilities>(json)
        } catch (e: Exception) {
            RobotCapabilities()
        }
    }

    internal fun requireCoupled() {
        if (!isCoupled || client == null) {
            throw IllegalStateException("No Robot Coupled, $isCoupled, $client ")
        }
    }
    
    fun getDeviceName(): String {
        return Settings.Global.getString(
            context.contentResolver,
            Settings.Global.DEVICE_NAME
        ) ?: "Unknown Device"
    }
    fun getSharedSecret(): String? {
        return sharedPrefs.getString("shared_secret", null)
    }

    fun getId(): Long? {
        if (!sharedPrefs.contains("client_id")) return null
        return sharedPrefs.getLong("client_id", -1L)
    }
    private fun createSignature(payload: String, secretBase64: String): String {
        // decode BASE 64
        val secretBytes = android.util.Base64.decode(secretBase64, android.util.Base64.NO_WRAP)
        val hmacKey = SecretKeySpec(secretBytes, "HmacSHA256")

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)

        // ensure UTF-8
        val bytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))

        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private suspend fun ensureConnection() {
        // already resolved?
        if (currentResolvedIp == null) {
            currentResolvedIp = robotIP
        }

        // test
        try {
            val status = pingRobotInternal(currentResolvedIp!!)
            if (status == "alive") {
                return
            }
        } catch (e: Exception) {
            Log.w("RobotAPI", "Ping failed for $currentResolvedIp, searching via mDNS...")
        }

        //  Fallback: mdns search
        val freshIp = resolveHostToIp(robotIP ?: return)
        currentResolvedIp = freshIp
        Log.i("RobotAPI", "Updated IP to: $currentResolvedIp")
    }

    private suspend fun pingRobotInternal(target: String): String = withContext(Dispatchers.IO) {
        client?.get("https://$target:8443/ping")?.body() ?: "failed"
    }

    private suspend fun resolveHostToIp(host: String): String = withContext(Dispatchers.IO) {
        if (host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return@withContext host

        kotlin.coroutines.suspendCoroutine { continuation ->
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val searchName = host.removeSuffix(".local")
            var isResumed = false

            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (!isResumed) {
                        isResumed = true
                        continuation.resumeWith(Result.success(host))
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (!isResumed) {
                        isResumed = true
                        val ip = serviceInfo.host.hostAddress
                        Log.i("NSD", "Found IP for $host: $ip")
                        continuation.resumeWith(Result.success(ip))
                    }
                }
            }

            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceName == searchName) {
                        nsdManager.resolveService(serviceInfo, resolveListener)
                        nsdManager.stopServiceDiscovery(this)
                    }
                }

                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onDiscoveryStopped(regType: String) {}
                override fun onStartDiscoveryFailed(regType: String, code: Int) {
                    if (!isResumed) {
                        isResumed = true
                        continuation.resumeWith(Result.success(host))
                    }
                }
                override fun onStopDiscoveryFailed(regType: String, code: Int) {}
            }

            nsdManager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)

            // Sicherheits-Timeout nach 4 Sekunden
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                if (!isResumed) {
                    isResumed = true
                    try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (e: Exception) {}
                    continuation.resumeWith(Result.success(host))
                }
            }, 4000)
        }
    }
}
