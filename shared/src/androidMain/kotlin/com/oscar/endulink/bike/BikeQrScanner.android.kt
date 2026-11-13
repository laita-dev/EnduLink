package com.oscar.endulink.bike

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
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
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.oscar.endulink.platform.AndroidAppContext
import com.oscar.endulink.ui.EnduPalette
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
actual fun rememberBikeQrScanner(
    onQrScanned: (String) -> Unit,
    onManualInputRequested: () -> Unit,
    onScannerError: (String) -> Unit
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
            currentOnError("Permiso de cámara necesario para escanear el QR de la moto.")
            currentOnManual()
        }
    }

    if (showScannerDialog) {
        AndroidQrScannerDialog(
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

@Composable
private fun AndroidQrScannerDialog(
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
    val barcodeScanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

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
                            scanner = barcodeScanner,
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
            runCatching { barcodeScanner.close() }
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
                    text = "Escanear QR Moto",
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
                    text = "Apunta la cámara al código QR de la pantalla TFT de tu moto",
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
                        text = "Introducir o pegar QR manualmente",
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

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun analyzeQrFrame(
    imageProxy: ImageProxy,
    scanner: BarcodeScanner,
    handled: AtomicBoolean,
    onDetected: (String) -> Unit
) {
    if (handled.get()) {
        imageProxy.close()
        return
    }
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            val raw = barcodes.firstNotNullOfOrNull { barcode ->
                barcode.rawValue?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (raw != null && handled.compareAndSet(false, true)) {
                onDetected(raw)
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

