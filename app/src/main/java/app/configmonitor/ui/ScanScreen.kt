package app.configmonitor.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.configmonitor.R
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

private val qrOptions: BarcodeScannerOptions = BarcodeScannerOptions.Builder()
    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
    .build()

/** Reads a QR code from a picture (gallery). Calls [onDone] with the text, or null if none was found. */
fun decodeQrFromUri(ctx: Context, uri: Uri, onDone: (String?) -> Unit) {
    val scanner = BarcodeScanning.getClient(qrOptions)
    try {
        val image = InputImage.fromFilePath(ctx, uri)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                onDone(codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue)
            }
            .addOnFailureListener { onDone(null) }
            .addOnCompleteListener { scanner.close() }
    } catch (e: Exception) {
        scanner.close()
        onDone(null)
    }
}

private class QrAnalyzer(
    private val scanner: BarcodeScanner,
    private val onCode: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val media = imageProxy.image
        if (media == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                val text = codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                if (text != null) onCode(text)
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}

@Composable
private fun CameraPreview(torchOn: Boolean, onCode: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(qrOptions)
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, QrAnalyzer(scanner, onCode))
                provider.unbindAll()
                camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                // no camera available
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            try {
                providerFuture.get().unbindAll()
            } catch (e: Exception) { }
            executor.shutdown()
            scanner.close()
        }
    }
    LaunchedEffect(camera, torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** Dark mask with a square window, corner brackets and a moving scan line. */
@Composable
private fun ScanOverlay(found: Boolean) {
    val sc = LocalStatusColors.current
    val reduce = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "scan")
    val line by transition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.94f,
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "line",
    )
    val accent = sc.up
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        val side = size.minDimension * 0.68f
        val left = (size.width - side) / 2f
        val top = size.height * 0.36f - side / 2f
        val radius = CornerRadius(26.dp.toPx())
        drawRect(Color(0xC705070A))
        drawRoundRect(Color.Transparent, Offset(left, top), Size(side, side), radius, blendMode = BlendMode.Clear)
        if (found) {
            drawRoundRect(accent.copy(alpha = 0.18f), Offset(left, top), Size(side, side), radius)
        }
        val arm = 34.dp.toPx()
        val sw = 4.dp.toPx()
        fun seg(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(accent, Offset(x1, y1), Offset(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
        }
        val right = left + side
        val bottom = top + side
        seg(left, top + arm, left, top); seg(left, top, left + arm, top)
        seg(right - arm, top, right, top); seg(right, top, right, top + arm)
        seg(right, bottom - arm, right, bottom); seg(right, bottom, right - arm, bottom)
        seg(left + arm, bottom, left, bottom); seg(left, bottom, left, bottom - arm)
        if (!found && !reduce) {
            val y = top + side * line
            drawLine(
                accent,
                Offset(left + 16.dp.toPx(), y),
                Offset(right - 16.dp.toPx(), y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun RoundButton(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xCC161B22))
            .clickableBox(description, onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun ScanScreen(onResult: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    var torch by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<String?>(null) }
    var miss by remember { mutableStateOf(false) }

    val onCode: (String) -> Unit = { code ->
        if (found == null) {
            found = code
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    LaunchedEffect(found) {
        val f = found
        if (f != null) {
            delay(450L)
            onResult(f)
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            decodeQrFromUri(ctx, uri) { result ->
                if (result == null) miss = true else onCode(result)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF05070A))) {
        if (granted) CameraPreview(torch, onCode)
        ScanOverlay(found != null)

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundButton(stringResource(R.string.close), onClose) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
            }
            Text(
                stringResource(R.string.scan_qr),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            RoundButton(stringResource(R.string.torch), { torch = !torch }) {
                TorchIcon(Color.White, torch)
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!granted) {
                Text(
                    stringResource(R.string.camera_denied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.perm_fix), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                }
            } else {
                Text(
                    stringResource(R.string.scan_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFC5CCD8),
                    textAlign = TextAlign.Center,
                )
            }
            if (miss) {
                Text(
                    stringResource(R.string.qr_not_found),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalStatusColors.current.downText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            TextButton(
                onClick = {
                    miss = false
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            ) {
                Text(stringResource(R.string.scan_gallery), style = MaterialTheme.typography.labelLarge, color = cs.primary)
            }
        }
    }
}
