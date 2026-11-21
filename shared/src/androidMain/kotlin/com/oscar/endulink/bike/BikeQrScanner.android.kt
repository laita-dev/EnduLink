package com.oscar.endulink.bike

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.oscar.endulink.platform.AndroidAppContext
import com.oscar.endulink.ui.EnduPalette
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
actual fun rememberBikeQrScanner(
    onQrScanned: (String) -> Unit,
    onManualInputRequested: () -> Unit,
    onScannerError: (String) -> Unit,
    title: String,
    hint: String,
    manualButtonLabel: String
): () -> Unit {
    val context = LocalContext.current
    val currentOnQrScanned by rememberUpdatedState(onQrScanned)
    val currentOnManual by rememberUpdatedState(onManualInputRequested)
    val currentOnError by rememberUpdatedState(onScannerError)

    var showScannerDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showScannerDialog = true
        } else {
            currentOnError("Permiso de cámara necesario para escanear el código QR.")
            currentOnManual()
        }
    }

    if (showScannerDialog) {
        AndroidQrScannerDialog(
            title = title,
            hint = hint,
            manualButtonLabel = manualButtonLabel,
            onQrDetected = { raw ->
                showScannerDialog = false
                currentOnQrScanned(raw)
            },
            onManualFallback = {
                showScannerDialog = false
                currentOnManual()
            },
            onError = { msg ->
                showScannerDialog = false
                currentOnError(msg)
                currentOnManual()
            },
            onDismiss = {
                showScannerDialog = false
            }
        )
    }

    return {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            showScannerDialog = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}

actual fun openPlatformWifiSettings() {
    val ctx = AndroidAppContext.appContext ?: AndroidBikeKeysStorage.appContext ?: return
    try {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    } catch (_: Exception) {}
}

private object AndroidBikeWifiConnector {
    var passiveMonitorRegistered = false

    fun ensurePassiveWifiMonitor(cm: ConnectivityManager) {
        if (passiveMonitorRegistered) return
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    AndroidSocketBinder.wifiNetwork = network
                }

                override fun onLost(network: Network) {
                    if (AndroidSocketBinder.wifiNetwork == network) {
                        AndroidSocketBinder.wifiNetwork = null
                    }
                }
            })
            passiveMonitorRegistered = true
        } catch (_: Exception) {}
    }
}

@Suppress("DEPRECATION")
actual fun connectToBikeWifi(
    ssid: String,
    password: String,
    onResult: (success: Boolean, message: String) -> Unit
) {
    val cleanSsid = ssid.trim()
    if (cleanSsid.isEmpty()) {
        onResult(false, "SSID de la moto vacío")
        return
    }
    val ctx = AndroidAppContext.appContext ?: AndroidBikeKeysStorage.appContext
    if (ctx == null) {
        onResult(false, "Contexto Android no disponible")
        return
    }

    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    if (cm == null || wifiManager == null) {
        onResult(false, "Servicio Wi-Fi de Android no disponible")
        return
    }

    AndroidBikeWifiConnector.ensurePassiveWifiMonitor(cm)

    if (password.isNotEmpty()) {
        try {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Clave Wi-Fi Moto", password))
        } catch (_: Exception) {}
    }

    // Si ya estamos conectados a ese SSID o al rango IP de la moto, solo enlazamos sockets sin abrir diálogos.
    try {
        val currentSsid = wifiManager.connectionInfo?.ssid?.trim('"').orEmpty()
        val activeWifi = cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (activeWifi != null) {
            AndroidSocketBinder.wifiNetwork = activeWifi
        }
        if (currentSsid.equals(cleanSsid, ignoreCase = true)) {
            onResult(true, "Ya conectado al Wi-Fi de la moto ($cleanSsid)")
            return
        }
    } catch (_: Exception) {}

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Registrar sugerencia Wi-Fi del sistema en segundo plano (sin usar WifiNetworkSpecifier,
        // que abre el selector «elegir un dispositivo» y corta el Wi-Fi actual).
        var suggestion: WifiNetworkSuggestion? = null
        try {
            val suggestionBuilder = WifiNetworkSuggestion.Builder()
                .setSsid(cleanSsid)
                .setIsAppInteractionRequired(false)
            if (password.isNotEmpty()) {
                suggestionBuilder.setWpa2Passphrase(password)
            }
            val built = suggestionBuilder.build()
            suggestion = built
            try {
                wifiManager.removeNetworkSuggestions(listOf(built))
            } catch (_: Exception) {}
            wifiManager.addNetworkSuggestions(listOf(built))
            try {
                wifiManager.startScan()
            } catch (_: Exception) {}
        } catch (_: Exception) {}

        // En Android 11+ (API 30+), ofrecer guardar la red directamente en los Ajustes Wi-Fi del sistema
        // para que el móvil se conecte siempre automáticamente al encender la moto.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && suggestion != null) {
            try {
                val addIntent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
                    putParcelableArrayListExtra(
                        Settings.EXTRA_WIFI_NETWORK_LIST,
                        arrayListOf(suggestion)
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(addIntent)
                onResult(
                    true,
                    "Guarda «$cleanSsid» en el aviso de Android para que el móvil se conecte solo al encender la moto"
                )
                return
            } catch (_: Exception) {}
        }

        openPlatformWifiSettings()
        onResult(
            true,
            "Red «$cleanSsid» registrada (clave $password copiada). Conéctate en Ajustes Wi-Fi la primera vez y la pantalla saldrá sola."
        )
    } else {
        // Android 8/9: WifiConfiguration clásico
        try {
            if (!wifiManager.isWifiEnabled) {
                wifiManager.isWifiEnabled = true
            }
            val conf = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"$cleanSsid\""
                if (password.isEmpty()) {
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"$password\""
                }
            }
            val existing = wifiManager.configuredNetworks?.firstOrNull {
                it.SSID?.trim('"').equals(cleanSsid, ignoreCase = true)
            }
            val netId = existing?.networkId ?: wifiManager.addNetwork(conf)
            if (netId == -1) {
                openPlatformWifiSettings()
                onResult(true, "Clave copiada ($password). Selecciona $cleanSsid en Ajustes Wi-Fi.")
                return
            }
            wifiManager.enableNetwork(netId, true)
            wifiManager.reconnect()
            onResult(true, "Conectando a $cleanSsid…")
        } catch (e: Exception) {
            openPlatformWifiSettings()
            onResult(true, "Clave copiada ($password). Selecciona $cleanSsid en Ajustes Wi-Fi.")
        }
    }
}

@Composable
private fun AndroidQrScannerDialog(
    title: String,
    hint: String,
    manualButtonLabel: String,
    onQrDetected: (String) -> Unit,
    onManualFallback: () -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentDetected by rememberUpdatedState(onQrDetected)
    val currentError by rememberUpdatedState(onError)

    val handled = remember { AtomicBoolean(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            // COMPATIBLE (TextureView) evita fallos de z-ordering dentro de Dialog Compose
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val qrDecoder = remember { ZxingQrDecoder() }

    DisposableEffect(lifecycleOwner, previewView) {
        var isDisposed = false
        var cameraProvider: ProcessCameraProvider? = null
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener(
            {
                if (isDisposed) return@addListener
                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        analyzeQrFrame(
                            imageProxy = imageProxy,
                            decoder = qrDecoder,
                            handled = handled,
                            onDetected = { raw ->
                                mainExecutor.execute {
                                    currentDetected(raw)
                                }
                            }
                        )
                    }

                    val selector = when {
                        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> {
                            currentError("No se encontró cámara disponible en el dispositivo.")
                            return@addListener
                        }
                    }

                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        imageAnalysis
                    )
                    boundCamera = camera
                    hasFlash = camera.cameraInfo.hasFlashUnit()
                } catch (e: Exception) {
                    if (!isDisposed) {
                        currentError("No se pudo abrir la cámara: ${e.message ?: "Error desconocido"}")
                    }
                }
            },
            mainExecutor
        )

        onDispose {
            isDisposed = true
            runCatching { boundCamera?.cameraControl?.enableTorch(false) }
            runCatching { cameraProvider?.unbindAll() }
            runCatching { analysisExecutor.shutdown() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // Máscara oscura con recorte central + esquinas de enfoque naranjas
            Canvas(modifier = Modifier.fillMaxSize()) {
                val boxSize = (size.minDimension * 0.64f).coerceIn(210.dp.toPx(), 290.dp.toPx())
                val left = (size.width - boxSize) / 2f
                val top = (size.height - boxSize) / 2f - 18.dp.toPx()
                val rect = Rect(left, top, left + boxSize, top + boxSize)
                val corner = CornerRadius(18.dp.toPx(), 18.dp.toPx())

                val cutout = Path().apply {
                    addRoundRect(RoundRect(rect, corner))
                }
                clipPath(cutout, clipOp = ClipOp.Difference) {
                    drawRect(Color.Black.copy(alpha = 0.58f))
                }

                // Marco sutil
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.25f),
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = corner,
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Esquinas de enfoque en naranja EnduLink
                val accent = EnduPalette.Accent
                val len = 28.dp.toPx()
                val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val r = 16.dp.toPx()

                val tl = Path().apply {
                    moveTo(rect.left, rect.top + len)
                    lineTo(rect.left, rect.top + r)
                    quadraticTo(rect.left, rect.top, rect.left + r, rect.top)
                    lineTo(rect.left + len, rect.top)
                }
                val tr = Path().apply {
                    moveTo(rect.right - len, rect.top)
                    lineTo(rect.right - r, rect.top)
                    quadraticTo(rect.right, rect.top, rect.right, rect.top + r)
                    lineTo(rect.right, rect.top + len)
                }
                val bl = Path().apply {
                    moveTo(rect.left, rect.bottom - len)
                    lineTo(rect.left, rect.bottom - r)
                    quadraticTo(rect.left, rect.bottom, rect.left + r, rect.bottom)
                    lineTo(rect.left + len, rect.bottom)
                }
                val br = Path().apply {
                    moveTo(rect.right - len, rect.bottom)
                    lineTo(rect.right - r, rect.bottom)
                    quadraticTo(rect.right, rect.bottom, rect.right, rect.bottom - r)
                    lineTo(rect.right, rect.bottom - len)
                }
                drawPath(tl, accent, style = stroke)
                drawPath(tr, accent, style = stroke)
                drawPath(bl, accent, style = stroke)
                drawPath(br, accent, style = stroke)
            }

            // Barra superior
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                        .clickable { onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Cerrar",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                if (hasFlash) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (torchEnabled) EnduPalette.Accent else Color.Black.copy(alpha = 0.6f)
                            )
                            .border(
                                1.dp,
                                if (torchEnabled) EnduPalette.Accent else Color.White.copy(alpha = 0.18f),
                                CircleShape
                            )
                            .clickable {
                                val next = !torchEnabled
                                torchEnabled = next
                                boundCamera?.cameraControl?.enableTorch(next)
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = if (torchEnabled) "Linterna ON" else "Linterna",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(64.dp))
                }
            }

            // Controles inferiores
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 22.dp)
                    .align(Alignment.BottomCenter),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = hint,
                    color = Color.White,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )

                Button(
                    onClick = onManualFallback,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.72f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.border(
                        1.dp,
                        Color.White.copy(alpha = 0.25f),
                        RoundedCornerShape(12.dp)
                    )
                ) {
                    Text(
                        text = manualButtonLabel,
                        color = EnduPalette.Accent,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                TextButton(onClick = onDismiss) {
                    Text("Cancelar", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                }
            }
        }
    }
}

private class ZxingQrDecoder {
    private val reader = QRCodeReader()
    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.CHARACTER_SET to "UTF-8",
        DecodeHintType.TRY_HARDER to true
    )
    private var yBuffer = ByteArray(0)
    private var frameCounter = 0

    fun decode(imageProxy: ImageProxy): String? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val width = imageProxy.width
        val height = imageProxy.height
        if (width <= 0 || height <= 0) return null

        val needed = width * height
        if (yBuffer.size < needed) {
            yBuffer = ByteArray(needed)
        }

        val buf = plane.buffer
        val rowStride = plane.rowStride
        buf.rewind()
        val remaining = buf.remaining()

        if (rowStride == width && remaining >= needed) {
            buf.get(yBuffer, 0, needed)
        } else {
            var dstOffset = 0
            for (row in 0 until height) {
                val srcPos = row * rowStride
                if (srcPos + width > remaining) return null
                buf.position(srcPos)
                buf.get(yBuffer, dstOffset, width)
                dstOffset += width
            }
        }

        val baseSource = PlanarYUVLuminanceSource(
            yBuffer,
            width,
            height,
            0,
            0,
            width,
            height,
            false
        )
        val invertThisFrame = (frameCounter++ and 1) == 1
        val fullSource: LuminanceSource = if (invertThisFrame) baseSource.invert() else baseSource
        tryDecodeSource(fullSource, useGlobal = false)?.let { return it }
        tryDecodeSource(baseSource, useGlobal = true)?.let { return it }

        // Segundo paso específico para pantallas TFT (borde blanco de solo 1 módulo y brillo LCD):
        // recortamos el visor central y añadimos quiet zone blanco artificial de 24 px + umbralizado.
        val boxSide = (minOf(width, height) * 0.64f).toInt().coerceIn(120, minOf(width, height))
        val pad = 24
        val paddedSide = boxSide + pad * 2
        val startX = (width - boxSide) / 2
        val startY = (height - boxSide) / 2
        val rawCropped = ByteArray(paddedSide * paddedSide) { 0xFF.toByte() }
        val threshHigh = ByteArray(paddedSide * paddedSide) { 0xFF.toByte() }

        for (r in 0 until boxSide) {
            val srcRow = (startY + r) * width + startX
            val dstRow = (r + pad) * paddedSide + pad
            for (c in 0 until boxSide) {
                val lum = yBuffer[srcRow + c].toInt() and 0xFF
                rawCropped[dstRow + c] = lum.toByte()
                threshHigh[dstRow + c] = if (lum >= 158) 0xFF.toByte() else 0x00.toByte()
            }
        }

        val paddedSource = PlanarYUVLuminanceSource(
            rawCropped,
            paddedSide,
            paddedSide,
            0,
            0,
            paddedSide,
            paddedSide,
            false
        )
        tryDecodeSource(paddedSource, useGlobal = false)?.let { return it }
        tryDecodeSource(paddedSource, useGlobal = true)?.let { return it }

        val threshSource = PlanarYUVLuminanceSource(
            threshHigh,
            paddedSide,
            paddedSide,
            0,
            0,
            paddedSide,
            paddedSide,
            false
        )
        return tryDecodeSource(threshSource, useGlobal = false)
    }

    private fun tryDecodeSource(source: LuminanceSource, useGlobal: Boolean): String? {
        val binarizer = if (useGlobal) GlobalHistogramBinarizer(source) else HybridBinarizer(source)
        val bitmap = BinaryBitmap(binarizer)
        return try {
            reader.decode(bitmap, hints).text?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}

private fun analyzeQrFrame(
    imageProxy: ImageProxy,
    decoder: ZxingQrDecoder,
    handled: AtomicBoolean,
    onDetected: (String) -> Unit
) {
    try {
        if (handled.get()) return
        val raw = decoder.decode(imageProxy)
        if (raw != null && handled.compareAndSet(false, true)) {
            onDetected(raw)
        }
    } finally {
        imageProxy.close()
    }
}

