package com.example.roboguardandroid

import android.content.Context
import android.provider.Settings
import android.util.Base64
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
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager


@Serializable
data class AuthCred(
    val id: Long,
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
                        if (receivedKeyBase64.contains(expectedKeyBase64) || expectedKeyBase64.contains(receivedKeyBase64)) {
                            android.util.Log.w("SSL_DEBUG", "This is a bad idea because I just accepted a key that only contains the right one")
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
        val response = client?.get("https://$robotIP:8443/ping")?.body() ?: "Client not initialized"
        Log.i("Server", "Ping: $response").toString()
    }

    internal suspend fun dataToRobot(data: String): HttpResponse = withContext(Dispatchers.IO) {
        requireCoupled()

        val signature = createSignature(data, getSharedSecret() ?: "")
        Log.i("RobotAPI", "Signature created")

        val response = client!!.post("https://$robotIP:8443/save") {
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
        try {
            val response: HttpResponse = client!!.post("https://$robotIP:8443/otp_auth") {
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
}