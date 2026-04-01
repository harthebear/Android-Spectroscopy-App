package com.example.uvccameraspectrometer

import android.Manifest
import android.graphics.Bitmap
//import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
//import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.CameraException
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollState
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.chart.line.LineChart
import com.patrykandpatrick.vico.core.entry.ChartEntryModel
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.core.axis.AxisItemPlacer
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.serenegiant.usb.IFrameCallback
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset


data class SpectrumData(
    val wavelengths: List<Float>,
    val intensities: List<Float>
)

data class OverlayControls(
    val roiTopY: Int = 450,
    val roiBottomY: Int = 550,
    val calX1: Int = 300,
    val calX2: Int = 1600,
    val calLambda1: Float = 436f,
    val calLambda2: Float = 546f,
)
// ─────────────────────────────────────────────────────────────────────
// MainActivity
// ─────────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    private val debugText = mutableStateOf("")

    companion object {
        private const val TAG = "UVCComposeCamera"
        private const val DEFAULT_PREVIEW_WIDTH = 1920
        private const val DEFAULT_PREVIEW_HEIGHT = 1080
    }

    // ── UVC camera helper (from com.herohan.uvcapp) ──────────────────
    // See: https://github.com/shiyinghan/UVCAndroid/blob/main/libuvccam/src/main/java/com/herohan/uvcapp/ICameraHelper.java
    private var cameraHelper: ICameraHelper? = null

    // ── Surface that the camera renders into (held by SurfaceView) ───
    private var previewSurface: Surface? = null

    // ── Mutable state observed by Compose ────────────────────────────
    private val _statusText = mutableStateOf("Waiting for USB camera…")
    private val statusColor = mutableStateOf(0)
    private val _isCameraOpen = mutableStateOf(false)
    private val _capturedBitmap = mutableStateOf<Bitmap?>(null)
    private val _capturedFilePath = mutableStateOf<String?>(null)
    private val _pixelDataSummary = mutableStateOf<String?>(null)
    private val imageWidthState = mutableStateOf<Int?>(null)
    private val imageHeightState = mutableStateOf<Int?>(null)

    // ── Current USB device (for multi-device filtering) ──────────────
    private var currentUsbDevice: UsbDevice? = null

    // ─────────────────────────────────────────────────────────────
    // Permission handling via the modern Activity Result API.
    // ─────────────────────────────────────────────────────────────

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = grants.values.all { it }
            if (allGranted) {
                Log.i(TAG, "All permissions granted")
                initCameraHelper()
            } else {
                Log.w(TAG, "Some permissions denied: $grants")
                _statusText.value = "Permissions denied — camera cannot start."
                statusColor.value = 0
                Toast.makeText(this, "Required permissions were denied", Toast.LENGTH_LONG).show()
            }
        }

    //-----------------------------SET STATES---------------------------
    val spectrumState = mutableStateOf<SpectrumData?>(null)

    /**
     * Builds the permission list depending on the device API level.
     *
     * - CAMERA: Required by many OEM USB stacks even for UVC.
     * - WRITE_EXTERNAL_STORAGE: Needed on API ≤ 28 to save captures.
     * - READ_MEDIA_IMAGES: Needed on API ≥ 33 (Tiramisu).
     * - RECORD_AUDIO: Only if you also record video with audio.
     */
    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // API 28 (Pie) and below
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            // API 32 and below
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        }
        // Uncomment if you need audio recording alongside video:
        // perms.add(Manifest.permission.RECORD_AUDIO)

        return perms.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions first; camera init happens in the callback.
        permissionLauncher.launch(requiredPermissions())

        setContent {
            MaterialTheme {
                UvcCameraScreen(
                    statusText = _statusText.value,
                    isCameraOpen = _isCameraOpen.value,
                    capturedBitmap = _capturedBitmap.value,
                    capturedFilePath = _capturedFilePath.value,
                    pixelDataSummary = _pixelDataSummary.value,
                    onSurfaceAvailable = { surface -> onPreviewSurfaceReady(surface) },
                    onSurfaceDestroyed = { onPreviewSurfaceLost() },
                    onCaptureClick = { captureImage() },
                    spectrumData = spectrumState.value,
                    debugText = debugText.value,
                    statusColor = statusColor.value,
                    imageWidth = imageWidthState.value,
                    imageHeight = imageHeightState.value,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-init when returning to foreground (handles USB reconnects).
        if (cameraHelper == null) {
            initCameraHelper()
        }
    }
    var imageWidth: Int? = null
    var imageHeight: Int? = null

    override fun onStop() {
        super.onStop()
        releaseCameraHelper()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseCameraHelper()
    }

    // ─────────────────────────────────────────────────────────────
    // CameraHelper initialisation & release
    // See: https://github.com/shiyinghan/UVCAndroid#usage
    // ─────────────────────────────────────────────────────────────

    /**
     * Creates and configures an [ICameraHelper] instance.
     *
     * [CameraHelper] is the main client for managing the UVC camera service.
     * It handles USB device discovery, permission dialogs, opening/closing the
     * native UVC camera, and associating preview Surfaces.
     *
     * Key API points:
     *   - [ICameraHelper.setStateCallback] — receive attach/detach/open/close events.
     *   - [ICameraHelper.selectDevice] — request access to a specific USB device.
     *   - [ICameraHelper.openCamera] — open the native camera after permission is granted.
     *   - [ICameraHelper.startPreview] — begin streaming frames.
     *   - [ICameraHelper.addSurface] — attach a Surface for rendering.
     *   - [ICameraHelper.captureStillImage] — capture a JPEG still image.
     */
    private fun initCameraHelper() {
        if (cameraHelper != null) return

        try {
            cameraHelper = CameraHelper().apply {
                setStateCallback(cameraStateCallback)
            }
            Log.i(TAG, "CameraHelper initialised successfully")
            _statusText.value = "Camera helper ready. Attach a USB camera."
            statusColor.value = 2
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise CameraHelper", e)
            _statusText.value = "Error: ${e.message}"
            statusColor.value = 0
        }
    }

    private fun releaseCameraHelper() {
        try {
            cameraHelper?.let { helper ->
                previewSurface?.let { surface ->
                    try { helper.removeSurface(surface) } catch (_: Exception) {}
                }
                helper.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing CameraHelper", e)
        }
        cameraHelper = null
        currentUsbDevice = null
        _isCameraOpen.value = false
        _statusText.value = "Camera released."
        statusColor.value = 1
    }

    // ─────────────────────────────────────────────────────────────
    // ICameraHelper.StateCallback — the heart of UVC lifecycle
    //
    // Flow:  onAttach → selectDevice() → (USB permission dialog)
    //        → onDeviceOpen → openCamera() → onCameraOpen
    //        → startPreview() + addSurface()
    //
    // See demo: https://github.com/shiyinghan/UVCAndroid/blob/main/demo/
    // ─────────────────────────────────────────────────────────────

    private val cameraStateCallback = object : ICameraHelper.StateCallback {

        /**
         * Called when a USB device matching the filter is physically attached.
         * We immediately attempt to select (claim) it, which triggers the
         * system USB permission dialog if not already granted.
         */
        override fun onAttach(device: UsbDevice) {
            Log.i(TAG, "USB device attached: ${device.deviceName} " +
                    "(VID=0x${Integer.toHexString(device.vendorId)}, " +
                    "PID=0x${Integer.toHexString(device.productId)})")
            _statusText.value = "USB device detected: ${device.deviceName}"
            currentUsbDevice = device
            cameraHelper?.selectDevice(device)
        }

        /**
         * Called after USB permission is granted and the device file descriptor
         * is open. Now we can open the UVC camera itself.
         *
         * @param isFirstOpen true if this is the first open since attach.
         */
        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            Log.i(TAG, "USB device opened: ${device.deviceName}, firstOpen=$isFirstOpen")
            _statusText.value = "USB device opened. Opening camera…"
            statusColor.value = 1

            // Optionally configure UVC parameters before opening:
            // val param = UVCParam()
            // param.setQuirks(UVCCamera.UVC_QUIRK_FIX_BANDWIDTH)
            // cameraHelper?.openCamera(param)

            cameraHelper?.openCamera()
        }

        /**
         * Called when the native UVC camera is opened and ready to stream.
         * Now we start the preview and attach our rendering Surface.
         */

        override fun onCameraOpen(device: UsbDevice) {
            Log.i(TAG, "UVC camera opened for device: ${device.deviceName}")
            _isCameraOpen.value = true

            cameraHelper?.startPreview()

            // Read the negotiated preview size from the camera.
            val previewSize: Size? = cameraHelper?.previewSize
            if (previewSize != null) {
                val size = cameraHelper?.previewSize
                imageWidth = size?.width
                imageHeight = size?.height
                imageWidthState.value = previewSize.width
                imageHeightState.value = previewSize.height
                Log.i(TAG, "Preview size: ${previewSize.width}x${previewSize.height}")
                _statusText.value = "Streaming ${previewSize.width}×${previewSize.height}"
                statusColor.value = 2
            } else {

                _statusText.value = "Streaming (unknown resolution)"
                statusColor.value = 1
            }

            // Attach the Surface for rendering. The second parameter (isRecordable)
            // should be false for a simple preview surface.
            previewSurface?.let { surface ->
                cameraHelper?.addSurface(surface, false)
                Log.i(TAG, "Preview surface attached")
            }
            captureImage()
        }

        /**
         * Called when the UVC camera is closed (e.g. stopPreview + closeCamera).
         */
        override fun onCameraClose(device: UsbDevice) {
            Log.i(TAG, "UVC camera closed for device: ${device.deviceName}")
            _isCameraOpen.value = false
            _statusText.value = "Camera closed."
            statusColor.value = 0

            previewSurface?.let { surface ->
                try { cameraHelper?.removeSurface(surface) } catch (_: Exception) {}
            }
        }

        override fun onDeviceClose(device: UsbDevice?) {
            Log.i(TAG, "UVC device closed")
            _isCameraOpen.value = false
        }

        /**
         * Called when the USB device is physically detached.
         */
        override fun onDetach(device: UsbDevice) {
            Log.i(TAG, "USB device detached: ${device.deviceName}")
            _isCameraOpen.value = false
            _statusText.value = "USB camera disconnected."
            statusColor.value = 0
            currentUsbDevice = null
        }

        /**
         * Called when USB permission is explicitly denied by the user.
         */
        override fun onCancel(device: UsbDevice) {
            Log.w(TAG, "USB permission cancelled for: ${device.deviceName}")
            _statusText.value = "USB permission denied."
            statusColor.value = 0
            currentUsbDevice = null
        }

        override fun onError(device: UsbDevice?, e: CameraException?) {
            Log.e("UVC_APP", "Camera Error: ${e?.message}")
            runOnUiThread {
                _statusText.value = "Capture error: ${e?.message} Try restarting the app and/or reconnecting the camera."
                statusColor.value = 0
                Toast.makeText(
                    this@MainActivity,
                    "Capture failed: ${e?.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Surface management — bridging SurfaceView ↔ CameraHelper
    // ─────────────────────────────────────────────────────────────

    private fun onPreviewSurfaceReady(surface: Surface) {
        Log.i(TAG, "Preview surface available")
        previewSurface = surface

        // If the camera is already open (e.g. surface recreated after config change),
        // immediately attach the new surface.
        if (_isCameraOpen.value) {
            cameraHelper?.addSurface(surface, false)
        }
    }

    private fun onPreviewSurfaceLost() {
        Log.i(TAG, "Preview surface destroyed")
        previewSurface?.let { surface ->
            try { cameraHelper?.removeSurface(surface) } catch (_: Exception) {}
        }
        previewSurface = null
    }

    private fun captureImage() {
        val helper = cameraHelper
        if (helper == null || !_isCameraOpen.value) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        _statusText.value = "Capturing…"

        // Build a unique file path for the captured image.
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val captureDir = File(
            getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "UVCCaptures"
        ).apply { mkdirs() }
        val captureFile = File(captureDir, "UVC_$timestamp.jpg")

        try {
            cameraHelper?.setFrameCallback(
                // Argument 1: The serenegiant IFrameCallback object
                IFrameCallback { frame ->
                    val data = ByteArray(frame.remaining())
                    frame.get(data)
                    _statusText.value = "Spectral data processing normally."
                    statusColor.value = 2
                    val width = imageWidth ?: return@IFrameCallback
                    val height = imageHeight ?: return@IFrameCallback
                    Log.d(TAG, "frameBytes=${data.size}, expectedYUYV=${width*height*2}, expectedRGB=${width*height*3}")
                    val y0 = 500          // pick these by inspection
                    val y1 = 580          // (inclusive/exclusive OK either way)
                    val roiH = (y1 - y0).coerceAtLeast(1)

                    val spectrum = FloatArray(width)

                    for (x in 0 until width) {
                        var sum = 0f
                        for (y in y0 until y1) {
                            val idx = (y * width + x) * 3
                            val r = data[idx].toInt() and 0xFF
                            val g = data[idx + 1].toInt() and 0xFF
                            val b = data[idx + 2].toInt() and 0xFF
                            val yVal = 0.299f * r + 0.587f * g + 0.114f * b
                            sum += yVal
                        }
                        spectrum[x] = sum / roiH   // keep float
                    }
                    runOnUiThread {
                        val intensities = spectrum.map { it.toFloat() }
                        val slope = (546.0f - 436.0f) / (1365.0f - 1575.0f)
                        val intercept = 436.0f
                        val wavelengths = List(spectrum.size) { i ->
                            val w = intercept + slope * (i - 1570.0f)
                            (w * 100).toInt() / 100f
                        }

                        //---------------DEBUG-----------------
                        val wl = wavelengths
                        val it = intensities
//                        var highCntr = 0
//                        for(i in 0 until intensities.size){
//                            if(intensities[i] > 240){
//                                highCntr++
//                            }
//                        }
//                        var minCntr = 0
//                        for(i in 0 until intensities.size) {
//                            if (intensities[i] > 5) {
//                                minCntr++
//                            }
//                        }
//                        if(minCntr < 200){
//                            _statusText.value = "Signal level low. Data may be noisy or unreadable."
//                            statusColor.value = 1
//                        }
//                        else if(highCntr > 35){
//                            _statusText.value = "Sensor may be saturated. Spectral data may be clipped."
//                            statusColor.value = 1
//                        }
                        val dbg = buildString {
                            appendLine("N=${wl.size}")
                            appendLine("λ min=${wl.minOrNull()}  max=${wl.maxOrNull()}")
                            appendLine("λ first=${wl.take(5)}")
                            appendLine("λ last =${wl.takeLast(5)}")
                            appendLine("I min=${it.minOrNull()}  max=${it.maxOrNull()}")
                        }
                        val maxIdx = spectrum.indices.maxBy { spectrum[it] }
                        val maxLambda = wavelengths[maxIdx]
                        //debugText.value = "maxIdx=$maxIdx  λ(max)=$maxLambda\n" + debugText.value
                        debugText.value = ""
                        //--------------END DEBUG----------------
                        spectrumState.value = SpectrumData(wavelengths, intensities)
                    }
                },
                // Argument 2: The pixel format constant, found in the underlying serenegiant library.
                4
            )
            //constant 4 worked
        } catch (e: Exception) {
            Log.e(TAG, "Exception during capture call", e)
            _statusText.value = "Capture error: ${e.message}"
            statusColor.value = 0
        }
    }
}


// ─────────────────────────────────────────────────────────────────────
// Composable UI
// ─────────────────────────────────────────────────────────────────────

@Composable
fun OverlayLines(
    modifier: Modifier,
    imageWidth: Int,
    imageHeight: Int,
    controls: OverlayControls,
) {
    Canvas(modifier = modifier) {
        fun xToPx(xImg: Int) = (xImg.toFloat() / (imageWidth - 1).coerceAtLeast(1)) * size.width
        fun yToPx(yImg: Int) = (yImg.toFloat() / (imageHeight - 1).coerceAtLeast(1)) * size.height

        val yTop = yToPx(controls.roiTopY)
        val yBot = yToPx(controls.roiBottomY)
        val x1 = xToPx(controls.calX1)
        val x2 = xToPx(controls.calX2)

        // ROI (horizontal)
        drawLine(Color.Cyan, Offset(0f, yTop), Offset(size.width, yTop), strokeWidth = 3f)
        drawLine(Color.Cyan, Offset(0f, yBot), Offset(size.width, yBot), strokeWidth = 3f)

        // Calibration (vertical)
        drawLine(Color.Yellow, Offset(x1, 0f), Offset(x1, size.height), strokeWidth = 3f)
        drawLine(Color.Yellow, Offset(x2, 0f), Offset(x2, size.height), strokeWidth = 3f)
    }
}

@Composable
private fun SliderWithIntField(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    enabled: Boolean
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.width(110.dp))

        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f),
            enabled = enabled
        )

        Spacer(Modifier.width(12.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { new ->
                text = new
                new.toIntOrNull()?.let { onValueChange(it.coerceIn(range)) }
            },
            singleLine = true,
            modifier = Modifier.width(96.dp),
            enabled = enabled
        )
    }
}

@Composable
fun ControlsPanel(
    enabled: Boolean,
    imageWidth: Int,
    imageHeight: Int,
    controls: OverlayControls,
    onControlsChange: (OverlayControls) -> Unit,
) {
    val xRange = 0..(imageWidth - 1).coerceAtLeast(0)
    val yRange = 0..(imageHeight - 1).coerceAtLeast(0)

    fun updateRoi(topY: Int? = null, bottomY: Int? = null) {
        val t = (topY ?: controls.roiTopY).coerceIn(yRange)
        val b = (bottomY ?: controls.roiBottomY).coerceIn(yRange)
        onControlsChange(
            controls.copy(
                roiTopY = minOf(t, b),
                roiBottomY = maxOf(t, b),
            )
        )
    }

    fun update(new: OverlayControls) {
        // clamp only (no reordering except ROI handled above)
        onControlsChange(
            new.copy(
                roiTopY = new.roiTopY.coerceIn(yRange),
                roiBottomY = new.roiBottomY.coerceIn(yRange),
                calX1 = new.calX1.coerceIn(xRange),
                calX2 = new.calX2.coerceIn(xRange),
            )
        )
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SliderWithIntField(
            label = "ROI top (y)",
            value = controls.roiTopY,
            range = yRange,
            enabled = enabled,
            onValueChange = { updateRoi(topY = it) }
        )

        SliderWithIntField(
            label = "ROI bottom (y)",
            value = controls.roiBottomY,
            range = yRange,
            enabled = enabled,
            onValueChange = { updateRoi(bottomY = it) }
        )

        SliderWithIntField(
            label = "Cal x1",
            value = controls.calX1,
            range = xRange,
            enabled = enabled,
            onValueChange = { update(controls.copy(calX1 = it)) }
        )

        SliderWithIntField(
            label = "Cal x2",
            value = controls.calX2,
            range = xRange,
            enabled = enabled,
            onValueChange = { update(controls.copy(calX2 = it)) }
        )

        // Calibration wavelengths (text only)
        FloatField("λ1 (nm)", controls.calLambda1, enabled) { update(controls.copy(calLambda1 = it)) }
        FloatField("λ2 (nm)", controls.calLambda2, enabled) { update(controls.copy(calLambda2 = it)) }

        if (controls.calX1 == controls.calX2) {
            Text(
                "Calibration invalid: x1 and x2 must be different.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun PreviewWithOverlay(
    imageWidth: Int?,
    imageHeight: Int?,
    controls: OverlayControls,
    onControlsChange: (OverlayControls) -> Unit,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    isCameraOpen: Boolean,
) {
    val w = (imageWidth ?: 1920).coerceAtLeast(1)
    val h = (imageHeight ?: 1080).coerceAtLeast(1)
    val aspect = w.toFloat() / h.toFloat()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        UvcPreviewSurface(
            modifier = Modifier.fillMaxSize(),
            onSurfaceAvailable = onSurfaceAvailable,
            onSurfaceDestroyed = onSurfaceDestroyed,
        )

        if (!isCameraOpen) {
            Text(
                "No preview",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (isCameraOpen) {
            // Draw overlay lines in correct positions for current aspect ratio.
            OverlayLines(
                modifier = Modifier.fillMaxSize(),
                imageWidth = w,
                imageHeight = h,
                controls = controls,
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    ControlsPanel(
        imageWidth = w,
        imageHeight = h,
        controls = controls,
        onControlsChange = onControlsChange,
        enabled = isCameraOpen
    )
}

@Composable
private fun FloatField(
    label: String,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.width(110.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { new ->
                text = new
                new.toFloatOrNull()?.let(onValueChange)
            },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.width(140.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UvcCameraScreen(
    statusText: String,
    isCameraOpen: Boolean,
    capturedBitmap: Bitmap?,
    capturedFilePath: String?,
    pixelDataSummary: String?,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onCaptureClick: () -> Unit,
    spectrumData: SpectrumData?,
    debugText: String,
    statusColor: Int,
    imageWidth: Int?,
    imageHeight: Int?
) {
    val scrollState = rememberScrollState()

    Scaffold(
//        topBar = {
//            TopAppBar(
//                title = { Text("UVC Camera — Compose") },
//                colors = TopAppBarDefaults.topAppBarColors(
//                    containerColor = MaterialTheme.colorScheme.primaryContainer,
//                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
//                )
//            )
//        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Status indicator ─────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(8.dp),
                color =
                when (statusColor) {
                    0 -> Color(0xFFD32F2F) // red
                    1 -> Color(0xFFFBC02D) // yellow
                    2 -> Color(0xFF388E3C) // green
                    else -> Color(0x00000000)
                }
            ) {
                Text(
                    text = statusText,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

//            Box(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .aspectRatio(16f / 9f)  // Match DEFAULT_PREVIEW 1920x1080
//                    .padding(horizontal = 16.dp)
//                    .clip(RoundedCornerShape(12.dp))
//                    .background(MaterialTheme.colorScheme.surfaceVariant),
//                contentAlignment = Alignment.Center
//            ) {
//                // Lifecycle-aware SurfaceView that notifies the Activity
//                // when the Surface is created/destroyed.
////                UvcPreviewSurface(
////                    modifier = Modifier.fillMaxWidth(),
////                    onSurfaceAvailable = onSurfaceAvailable,
////                    onSurfaceDestroyed = onSurfaceDestroyed
////                )
//                var controls by remember { mutableStateOf(OverlayControls()) }
//                PreviewWithOverlay(
//                    imageWidth = imageWidth,
//                    imageHeight = imageHeight,
//                    controls = controls,
//                    onControlsChange = { controls = it },
//                    onSurfaceAvailable = onSurfaceAvailable,
//                    onSurfaceDestroyed = onSurfaceDestroyed,
//                )
//
//                if (!isCameraOpen) {
//                    Text(
//                        text = "No preview",
//                        color = MaterialTheme.colorScheme.onSurfaceVariant,
//                        style = MaterialTheme.typography.titleMedium
//                    )
//                }
//            }
            var controls by remember { mutableStateOf(OverlayControls()) }

            PreviewWithOverlay(
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                controls = controls,
                onControlsChange = { controls = it },
                onSurfaceAvailable = onSurfaceAvailable,
                onSurfaceDestroyed = onSurfaceDestroyed,
                isCameraOpen = isCameraOpen
            )

            Spacer(modifier = Modifier.height(16.dp))

//            // ── Capture button ───────────────────────────────────
//            Button(
//                onClick = onCaptureClick,
//                enabled = isCameraOpen,
//                modifier = Modifier.size(72.dp),
//                shape = CircleShape,
//                colors = ButtonDefaults.buttonColors(
//                    containerColor = MaterialTheme.colorScheme.primary
//                )
//            ) {
//                Icon(
//                    imageVector = Icons.Filled.CheckCircle,
//                    contentDescription = "Capture Image",
//                    modifier = Modifier.size(32.dp)
//                )
//            }

            Spacer(modifier = Modifier.height(16.dp))


            Spacer(modifier = Modifier.height(24.dp))
            if (spectrumData != null) {
                if (spectrumData.intensities.isEmpty() || spectrumData.wavelengths.isEmpty()) {
                    Text("Waiting for spectrum...")
                } else {

                    val pairs = spectrumData.wavelengths.zip(spectrumData.intensities)
                        .sortedBy { it.first } // wavelength increasing left->right
                    val step = 4
                    val sampled = pairs.filterIndexed { i, _ -> i % step == 0 }
                    val entries = pairs.map { (lambda, intensity) ->
                        FloatEntry(lambda, intensity)
                    }
                    val model = entryModelOf(entries)
                    val wavelengthFormatter: AxisValueFormatter<AxisPosition.Horizontal.Bottom> =
                        AxisValueFormatter { x, _ ->
                            "%.1f".format(x) // x IS the wavelength
                        }

                    val bottomAxis = rememberBottomAxis(
                        guideline = null, // no vertical gridlines
                        tick = null,      // optional: no ticks
                        valueFormatter = wavelengthFormatter,
                        itemPlacer = AxisItemPlacer.Horizontal.default(
                            spacing = 200,              // show a label about every 200 samples
                            offset = 0,
                            shiftExtremeTicks = true,
                            addExtremeLabelPadding = true,
                        ),
                    )

                    val startAxis = rememberStartAxis(guideline = null) // intensity axis
                    Chart(
                        chart = LineChart(),
                        model = model,
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        startAxis = startAxis,
                        bottomAxis = bottomAxis,
                        isZoomEnabled = false,
                        chartScrollSpec = rememberChartScrollSpec(isScrollEnabled = false),
                        chartScrollState = rememberChartScrollState(),
                        //getXStep = { 1f },
                    )

                }
            }
        }
    }
    if (debugText.isNotBlank()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                debugText,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// SurfaceView wrapper for Compose
// ─────────────────────────────────────────────────────────────────────

/**
 * Embeds a [SurfaceView] inside Jetpack Compose and forwards Surface
 * lifecycle events to the parent. The UVCAndroid library's
 * [ICameraHelper.addSurface] / [ICameraHelper.removeSurface] methods
 * require a raw [Surface] obtained from the SurfaceHolder.
 *
 * This is the recommended approach because UVCAndroid renders using
 * native code (JNI) that writes directly to the Surface buffer.
 */
@Composable
fun UvcPreviewSurface(
    modifier: Modifier = Modifier,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceAvailable(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        // The UVC library handles scaling internally.
                        // No action needed unless you want custom aspect ratio logic.
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurfaceDestroyed()
                    }
                })
            }
        }
    )
}
