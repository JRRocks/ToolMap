package fr.toolmap.share

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.toolmap.core.problem.Problems
import fr.toolmap.p2p.QrCodes
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The camera preview with a QR code reader on the luminance plane of every frame (ZXing, no Play Services). [onCode]
 * is called once with the first readable text; the caller closes the scanner. The caller holds the camera permission.
 */
@Composable
fun QrScanner(
    onCode: (String) -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val found = remember { AtomicBoolean(false) }
    val view = remember { PreviewView(context) }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    DisposableEffect(lifecycleOwner) {
        var provider: ProcessCameraProvider? = null
        // Closed before CameraX was ready: nothing is bound then, or the camera would stay bound to the activity
        // until it is destroyed (P36 review). The listener and onDispose both run on the main thread.
        var disposed = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameras = future.get()
                provider = cameras
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis =
                    ImageAnalysis
                        .Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(executor) { image ->
                                read(image, found, mainExecutor) { text -> currentOnCode(text) }
                            }
                        }
                cameras.unbindAll()
                cameras.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: IllegalArgumentException) {
                // No back camera, or the use cases cannot be combined on this device.
                Problems.expected("share.qr.camera", e)
                currentOnUnavailable()
            } catch (e: IllegalStateException) {
                // A use case bound to another lifecycle, or a call off the main thread: a bug of this screen.
                Problems.report("share.qr.bind", e)
                currentOnUnavailable()
            } catch (e: java.util.concurrent.ExecutionException) {
                // CameraX could not start: no camera it can use on this device.
                Problems.expected("share.qr.provider", e)
                currentOnUnavailable()
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            provider?.unbindAll()
            shutdown(executor)
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

@SuppressLint("UnsafeOptInUsageError")
private fun read(
    image: ImageProxy,
    found: AtomicBoolean,
    mainExecutor: Executor,
    onCode: (String) -> Unit,
) {
    try {
        if (found.get()) return
        val plane = image.planes.firstOrNull() ?: return
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val text =
            try {
                QrCodes.decode(data, plane.rowStride, image.width, image.height)
            } catch (e: IllegalArgumentException) {
                // The frame does not fit its plane: a bug of this reading, which then fails on every frame.
                Problems.report(
                    "share.qr.decode",
                    e,
                    "width" to image.width,
                    "height" to image.height,
                    "stride" to plane.rowStride,
                )
                null
            }
        if (text != null && found.compareAndSet(false, true)) {
            mainExecutor.execute { onCode(text) }
        }
    } finally {
        image.close()
    }
}

private fun shutdown(executor: ExecutorService) {
    executor.shutdown()
}
