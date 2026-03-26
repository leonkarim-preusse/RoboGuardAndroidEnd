package com.example.roboguardandroid

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.Preview
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext

import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.isGranted

import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import java.util.concurrent.Executors

import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage


import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * Data class representing the information extracted from a robot's QR code.
 * @property publicKey The robot's public key for SSL pinning.
 * @property otp One-time password for the initial handshake.
 * @property ip The IP address or hostname of the robot.
 */
data class QrData(
    val publicKey: String,
    val otp: String,
    val ip: String,
)

/**
 * Image analyzer that uses ML Kit to detect and scan QR codes from a camera stream.
 * @property onQrCodeScanned Callback triggered when a QR code is successfully detected.
 */
class QrCodeAnalyzer(
    private val onQrCodeScanned: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull()?.rawValue?.let {
                    onQrCodeScanned(it)
                }
            }
            .addOnFailureListener { }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}

/**
 * Composable screen that displays a camera preview and scans for QR codes.
 * @param onQrScanned Callback triggered when a QR code is scanned.
 */
@Composable
fun QrScannerScreen(
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var scanned by remember { mutableStateOf(false) }

    var cameraProvider: ProcessCameraProvider? by remember {
        mutableStateOf(null)
    }
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(
                    Executors.newSingleThreadExecutor(),
                    QrCodeAnalyzer { value ->
                        if (!scanned) {
                            scanned = true
                            onQrScanned(value)
                            provider.unbindAll()
                        }
                    }
                )

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )


}

/**
 * Wrapper that handles camera permission requests before showing the QR scanner.
 * @param onQrScanned Callback triggered when a QR code is scanned.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraPermissionWrapper(
    onQrScanned: (String) -> Unit
) {
    val permissionState = rememberPermissionState(
        android.Manifest.permission.CAMERA
    )

    LaunchedEffect(Unit) {
        permissionState.launchPermissionRequest()
    }

    when {
        permissionState.status.isGranted -> {
            QrScannerScreen(onQrScanned)
        }
        else -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Camera permission required")
            }
        }
    }
}

/**
 * Verifies if the provided string content is a valid JSON QR code for RoboGuard.
 * @param content The string content to verify.
 * @return True if valid, false otherwise.
 */
fun verify_QR(content: String?): Boolean {
    if (content == null) return false

    return try {
        parseQR(content)
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Parses the JSON content of a QR code into a [QrData] object.
 * @param content The JSON string content.
 * @return The parsed [QrData].
 * @throws Exception if parsing fails or required fields are missing.
 */
fun parseQR(content: String?): QrData {
    val json = JSONObject(content)

    val publicKey = json.getString("publickey")
    val otp = json.getString("otp")
    val ip = json.getString( "ip")

    require(ip.isNotBlank()) {"Missing IP"}
    require(publicKey.isNotBlank()) { "Public key empty" }
    require(otp.isNotBlank()) { "OTP empty" }

    return QrData(publicKey, otp, ip)
}

/**
 * UI screen displaying the result of the QR verification process.
 * @param isValid True if the QR code was successfully verified.
 */
@Composable
fun isQRvalidScreen(isValid:Boolean) {
    var msg: String = "Please try to scan again! QR Code could not be verified."
    if (isValid) {
        msg = "Successfully scanned QR Code! Attempting to pair with robot!"
    }
    Box(modifier = Modifier.fillMaxSize()) {
    HeaderAppName()
    Text(
        msg,
        fontSize = 40.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,

        modifier = Modifier
            .padding(top = 26.dp, bottom = 40.dp)
            .fillMaxWidth()
            .padding(20.dp)
            .align(Alignment.Center),
        textAlign = TextAlign.Center
    )
    }
}
