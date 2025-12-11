package com.example.roboguardandroid

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

class RobotAPI(protected var robotIP: String) {
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

    suspend internal fun pingRobot(): String = withContext(Dispatchers.IO) {
        client.get("https://$robotIP:8443/ping").body()
    }

    suspend internal fun dataToRobot(data: String) {
        val response: HttpResponse = client.post("https://$robotIP:8443/save") {
            contentType(ContentType.Text.Plain)
            setBody(data)
        }
    }
}

