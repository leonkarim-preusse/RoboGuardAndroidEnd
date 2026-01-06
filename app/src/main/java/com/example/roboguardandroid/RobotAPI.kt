package com.example.roboguardandroid


import android.content.Context
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.net.ssl.HostnameVerifier
import io.ktor.client.call.body
import kotlinx.serialization.json.Json
import android.provider.Settings



@Serializable
data class AuthResponse(
    val id: Int,
    val secret: String
)

class RobotAPI() {

    internal var isCoupled: Boolean = false
    private var robotIP: String? = null
    val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json()
        }
        engine {
            // Use sslManager to access the HttpsURLConnection
            sslManager = { connection ->
                connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
        }
    }

    internal suspend fun pingRobot(): String = withContext(Dispatchers.IO) {
        requireCoupled()
            client.get("https://$robotIP:8443/ping").body()
    }

    internal suspend fun dataToRobot(data: String): HttpResponse {
        requireCoupled()
        val response: HttpResponse = client.post("https://$robotIP:8443/save") {
            contentType(ContentType.Text.Plain)
            setBody(data)

        }
        return response
    }

    internal suspend fun secrethandshake(otp: String): AuthResponse {

        val response: HttpResponse = client.post("https://$robotIP:8443/otp_auth") {
            headers {
                append("X-Client-otp", otp)
                append("X-Client-name", getDeviceName())
            }
        }
        return Json.decodeFromString(
            response.bodyAsText()
        )
    }


    internal fun requireCoupled() {
        if (!isCoupled) {
            throw IllegalStateException("No Robot Coupled")
        }
    }

    fun getDeviceName(context: Context): String {
        return Settings.Global.getString(
            context.contentResolver,
            Settings.Global.DEVICE_NAME
        ) ?: "Unknown Device"
    }
}

