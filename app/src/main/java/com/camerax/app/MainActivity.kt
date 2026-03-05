package com.camerax.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.media.MediaScannerConnection
import android.util.Size
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.HapticFeedbackConstants
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.camerax.app.databinding.ActivityMainBinding
import com.camerax.app.debug.CrashLogger
import com.camerax.app.debug.LogFileManager
import com.camerax.app.util.RecordingTimeFormatter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Arrays
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class CaptureMode { PHOTO, VIDEO }
    private enum class FlashModeOption { OFF, ON, AUTO }
    private data class FrameShift(val dx: Int, val dy: Int)
    private data class GalleryMedia(val uri: Uri, val mimeType: String, val dateTakenMs: Long)

    private data class RecordingProfile(
        val label: String,
        val captureQuality: Quality,
        val targetHeight: Int
    )

    private data class BackLensOption(
        val cameraId: String,
        val focalLength: Float,
        val zoomRatio: Float,
        val label: String
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var activeCamera: Camera? = null

    private var previewUseCase: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var lastKnownRotation: Int = Surface.ROTATION_0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraOptionMap = linkedMapOf<String, String>()
    private var selectedCameraId: String? = null
    private var frontCameraId: String? = null
    private var backLensCameraIds: List<String> = emptyList()
    private var backLensOptions: List<BackLensOption> = emptyList()
    private var selectedBackLensIndex: Int = 0
    private var isFrontCameraActive: Boolean = false
    private var stabilizationEnabled: Boolean = true
    private var flashModeOption: FlashModeOption = FlashModeOption.OFF
    private var xcodeProcessingEnabled: Boolean = true
    private var enhancedShotEnabled: Boolean = false
    private var selectedAwbMode: Int = android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var timerSeconds: Int = 0

    private var availableApertures: FloatArray = floatArrayOf()
    private var apertureIndex: Int = 0
    private var currentMode: CaptureMode = CaptureMode.PHOTO
    private var isCountdownActive: Boolean = false
    private var modeBubbleInitialized: Boolean = false
    private val zoomButtons = mutableListOf<MaterialButton>()
    private var zoomAnimator: ValueAnimator? = null
    private var currentLinearZoom: Float = 0f
    private var preRecordingLinearZoom: Float? = null
    private var latestGalleryMediaUri: Uri? = null
    private var latestGalleryMediaMime: String? = null
    private var recordingStartElapsedMs: Long = 0L
    private val detectorBusy = AtomicBoolean(false)
    private var analysisFrameCount: Int = 0
    private var latestAmbientLux: Float? = null
    private var latestSceneLuma: Float = 0f
    private lateinit var faceDetector: FaceDetector
    private lateinit var objectDetector: ObjectDetector
    private var activeAfMode: Int = android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val lightSensor by lazy { sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) }
    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_LIGHT) return
            latestAmbientLux = event.values.firstOrNull()
            binding.lightValueText.text = formatLightStatusText(latestSceneLuma, latestAmbientLux)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private val recordingTimerRunnable = object : Runnable {
        override fun run() {
            if (recording == null) return
            val elapsedMs = SystemClock.elapsedRealtime() - recordingStartElapsedMs
            binding.recordingIndicator.text = "REC ${RecordingTimeFormatter.format(elapsedMs)}"
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private val recordingProfiles = listOf(
        RecordingProfile("4K", Quality.UHD, 2160),
        RecordingProfile("2K", Quality.FHD, 1440),
        RecordingProfile("720", Quality.HD, 720)
    )
    private var selectedProfile: RecordingProfile = recordingProfiles[1]
    private var activeRecordingTargetHeight: Int = recordingProfiles[1].targetHeight
    private val burstFrameCount = 3
    private val stabilizationCropLinearZoom = 0.12f

    private val wbLabels = listOf("Auto", "Daylight", "Cloudy", "Incandescent", "Fluorescent")
    private val wbMap = mapOf(
        "Auto" to android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO,
        "Daylight" to android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
        "Cloudy" to android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        "Incandescent" to android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
        "Fluorescent" to android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
    )

    private val timerLabels = listOf("Off", "3 sec", "10 sec")
    private val timerMap = mapOf(
        "Off" to 0,
        "3 sec" to 3,
        "10 sec" to 10
    )

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        val mandatoryGranted = mandatoryPermissions().all {
            grantResults[it] == true || ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (mandatoryGranted) {
            startCamera()
            if (!hasMediaReadPermission()) {
                binding.statusText.text = "Media permission denied (gallery preview off)"
                binding.galleryThumbPreview.setImageDrawable(ColorDrawable(Color.parseColor("#3E3E3E")))
            }
        } else {
            Toast.makeText(this, "Camera and audio permissions are required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val logFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                LogFileManager.setUserFolder(this, uri)
                LogFileManager.startSession(this)
                LogFileManager.append(this, "log_folder_selected=$uri")
                binding.statusText.text = "Log folder set"
            } catch (e: Exception) {
                LogFileManager.append(this, "log_folder_select_failed=${e.message}")
                failFast(e)
                Toast.makeText(this, "Unable to use selected log folder", Toast.LENGTH_LONG).show()
            }
        } else {
            LogFileManager.append(this, "log_folder_selection_cancelled")
        }
    }

    private val orientationListener: OrientationEventListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (isUiLockedToPortrait) return
                if (orientation == ORIENTATION_UNKNOWN) return
                val newRotation = orientationToSurfaceRotation(orientation)
                if (newRotation == lastKnownRotation) return
                lastKnownRotation = newRotation
                previewUseCase?.targetRotation = newRotation
                imageCapture?.targetRotation = newRotation
                videoCapture?.targetRotation = newRotation
                updateUiIconAndNumberRotation(newRotation)
            }
        }
    }
    private val isUiLockedToPortrait = true

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)
        LogFileManager.startSession(this)
        LogFileManager.append(this, "app_onCreate")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        setupDetectors()

        binding.shutterButton.setOnClickListener {
            when (currentMode) {
                CaptureMode.PHOTO -> runWithTimer(::takePhoto)
                CaptureMode.VIDEO -> {
                    if (recording == null) runWithTimer(::startRecording) else stopRecording()
                }
            }
        }

        binding.photoModeButton.setOnClickListener {
            if (recording == null) {
                currentMode = CaptureMode.PHOTO
                updateModeUi()
                binding.statusText.text = "Photo mode"
            }
        }

        binding.videoModeButton.setOnClickListener {
            if (recording != null) return@setOnClickListener
            currentMode = CaptureMode.VIDEO
            updateModeUi()
            binding.statusText.text = "Video mode (${selectedProfile.label})"
        }

        binding.frontCameraButton.setOnClickListener { toggleFrontCamera() }
        binding.settingsButton.setOnClickListener { showSettingsDialog() }
        binding.flashButton.setOnClickListener {
            cycleFlashMode()
        }
        binding.flashModeText.setOnClickListener {
            cycleFlashMode()
        }
        binding.zoomGridSlider.setOnZoomChangedListener { target ->
            applyPreviewZoomSmooth(target)
        }

        binding.apertureMinusButton.setOnClickListener { shiftAperture(-1) }
        binding.aperturePlusButton.setOnClickListener { shiftAperture(1) }
        binding.galleryThumb.setOnClickListener { openLatestMediaInGallery() }

        applyTopSafeInsets()

        updateTopControlsVisibility()
        applyOrientationUiLayout()
        updateModeUi(animate = false)
        updateFrontCameraUi()
        updateFlashUi()
        updateZoomUi()
        refreshActiveSettingsBadge()
        refreshGalleryThumbnailAsync()

        if (mandatoryPermissionsGranted()) {
            startCamera()
            if (!hasMediaReadPermission()) {
                binding.galleryThumbPreview.setImageDrawable(ColorDrawable(Color.parseColor("#3E3E3E")))
            }
        } else {
            permissionsLauncher.launch(requiredPermissions())
        }
    }

    override fun onStart() {
        super.onStart()
        LogFileManager.append(this, "app_onStart")
        if (!isUiLockedToPortrait && orientationListener.canDetectOrientation()) orientationListener.enable()
        registerLightSensor()
        updateUiIconAndNumberRotation(lastKnownRotation)
        refreshGalleryThumbnailAsync()
    }

    override fun onStop() {
        super.onStop()
        LogFileManager.append(this, "app_onStop")
        orientationListener.disable()
        unregisterLightSensor()
    }

    override fun onDestroy() {
        super.onDestroy()
        LogFileManager.append(this, "app_onDestroy")
        recording?.stop()
        stopRecordingTimer()
        zoomAnimator?.cancel()
        if (this::faceDetector.isInitialized) faceDetector.close()
        if (this::objectDetector.isInitialized) objectDetector.close()
        cameraExecutor.shutdown()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateTopControlsVisibility()
        applyOrientationUiLayout()
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun updateTopControlsVisibility() {
        // Keep portrait-style UI behavior in both portrait and landscape.
        binding.topControls.visibility = View.GONE
    }

    private fun applyOrientationUiLayout() {
        val previewParams =
            binding.previewView.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val dockParams =
            binding.bottomDock.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val modeToggleParams = binding.modeToggleContainer.layoutParams as LinearLayout.LayoutParams

        previewParams.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        previewParams.endToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        previewParams.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        previewParams.topToBottom = R.id.topBar
        previewParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        previewParams.bottomToTop = R.id.bottomDock
        previewParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        previewParams.marginStart = dp(12)
        previewParams.marginEnd = dp(12)
        previewParams.topMargin = dp(12)
        previewParams.bottomMargin = dp(10)

        dockParams.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        dockParams.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        dockParams.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        dockParams.endToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        dockParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        dockParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        dockParams.width = 0
        dockParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
        dockParams.marginStart = dp(12)
        dockParams.marginEnd = dp(12)
        dockParams.bottomMargin = dp(12)
        binding.bottomDock.setBackgroundResource(R.drawable.bg_overlay_panel_rounded)
        binding.bottomDock.setPadding(0, dp(10), 0, dp(12))

        modeToggleParams.width = dp(196)
        binding.bottomDock.rotation = 0f

        binding.previewView.layoutParams = previewParams
        binding.bottomDock.layoutParams = dockParams
        binding.modeToggleContainer.layoutParams = modeToggleParams
    }

    private fun applyTopSafeInsets() {
        val baseTopMargin = (
            binding.topBar.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            ).topMargin
        val extraCutoutSpacingPx = (8 * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemTop = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            val cutoutTop = insets.displayCutout?.safeInsetTop ?: 0
            val safeTopInset = max(systemTop, cutoutTop)
            val params = binding.topBar.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.topMargin = baseTopMargin + safeTopInset + extraCutoutSpacingPx
            binding.topBar.layoutParams = params
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun maybePromptForLogFolder() {
        if (LogFileManager.hasUserFolder(this)) return
        AlertDialog.Builder(this)
            .setTitle("Log Folder")
            .setMessage(
                "Pick a folder for crash/session logs. You can share the log files after a crash.\n\n" +
                    "If you skip, logs will be stored in app storage:\n${LogFileManager.defaultLogFolderPath(this)}"
            )
            .setNegativeButton("Use default") { _, _ ->
                LogFileManager.append(this, "log_folder_default_used")
            }
            .setPositiveButton("Choose folder") { _, _ ->
                logFolderPickerLauncher.launch(null)
            }
            .show()
    }

    private fun updateModeUi(animate: Boolean = true) {
        val activeColor = Color.parseColor("#141414")
        val inactiveColor = Color.parseColor("#CCFFFFFF")

        binding.photoModeButton.setTextColor(if (currentMode == CaptureMode.PHOTO) activeColor else inactiveColor)
        binding.videoModeButton.setTextColor(if (currentMode == CaptureMode.VIDEO) activeColor else inactiveColor)
        animateModeToggleBubble(animate)

        if (currentMode == CaptureMode.PHOTO) {
            binding.shutterButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        } else {
            binding.shutterButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF3B30"))
        }
    }

    private fun animateModeToggleBubble(animate: Boolean) {
        binding.modeToggleContainer.post {
            val innerWidth = binding.modeToggleContainer.width -
                binding.modeToggleContainer.paddingLeft - binding.modeToggleContainer.paddingRight
            val segmentWidth = innerWidth / 2f
            if (segmentWidth <= 0f) return@post

            val bubbleParams = binding.modeBubble.layoutParams
            val targetWidth = segmentWidth.toInt()
            if (bubbleParams.width != targetWidth) {
                bubbleParams.width = targetWidth
                binding.modeBubble.layoutParams = bubbleParams
            }

            val targetX = if (currentMode == CaptureMode.PHOTO) 0f else segmentWidth
            binding.modeBubble.animate().cancel()

            if (!modeBubbleInitialized || !animate) {
                binding.modeBubble.translationX = targetX
                binding.modeBubble.scaleX = 1f
                modeBubbleInitialized = true
                return@post
            }

            binding.modeBubble.scaleX = 1f
            binding.modeBubble.animate()
                .translationX(targetX)
                .scaleX(1.06f)
                .setDuration(320)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .withEndAction {
                    binding.modeBubble.animate()
                        .scaleX(1f)
                        .setDuration(140)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                .start()
        }
    }

    private fun updateZoomUi() {
        val activeColor = Color.parseColor("#FFD95A")
        val inactiveColor = Color.parseColor("#CCFFFFFF")

        val showBackLensControls = !isFrontCameraActive
        binding.zoomControlsRow.visibility = if (showBackLensControls && backLensOptions.isNotEmpty()) View.VISIBLE else View.GONE

        zoomButtons.forEachIndexed { index, button ->
            button.setTextColor(if (selectedBackLensIndex == index) activeColor else inactiveColor)
        }
        updateUiIconAndNumberRotation(lastKnownRotation)
    }

    private fun updateFrontCameraUi() {
        val tint = if (isFrontCameraActive) Color.parseColor("#FFD95A") else Color.WHITE
        binding.frontCameraButton.setColorFilter(tint)
    }

    private fun updateFlashUi() {
        val onColor = Color.parseColor("#FFD95A")
        val offColor = Color.WHITE
        val modeText = when (flashModeOption) {
            FlashModeOption.OFF -> "OFF"
            FlashModeOption.ON -> "ON"
            FlashModeOption.AUTO -> "AUTO"
        }
        binding.flashModeText.text = modeText
        binding.flashButton.setColorFilter(if (flashModeOption == FlashModeOption.OFF) offColor else onColor)
    }

    private fun refreshActiveSettingsBadge() {
        val flags = mutableListOf<String>()
        flags.add(selectedProfile.label)
        flags.add(if (stabilizationEnabled) "STAB" else "NO-STAB")
        flags.add(if (isFrontCameraActive) "FRONT" else "BACK")
        if (!isFrontCameraActive && flashModeOption != FlashModeOption.OFF) {
            flags.add("FLASH ${flashModeOption.name}")
        }
        flags.add(
            when (activeAfMode) {
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "AF-V"
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "AF-C"
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_AUTO -> "AF-AUTO"
                else -> "AF"
            }
        )
        if (timerSeconds > 0) flags.add("TIMER ${timerSeconds}s")
        if (selectedAwbMode != android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO) flags.add("WB")
        if (xcodeProcessingEnabled) flags.add("XCODE")
        if (enhancedShotEnabled) flags.add("ENH")
        binding.settingsStateText.text = flags.joinToString(" · ")
    }

    private fun cycleFlashMode() {
        flashModeOption = when (flashModeOption) {
            FlashModeOption.OFF -> FlashModeOption.ON
            FlashModeOption.ON -> FlashModeOption.AUTO
            FlashModeOption.AUTO -> FlashModeOption.OFF
        }
        updateFlashUi()
        applyFlashState()
        refreshActiveSettingsBadge()
    }

    private fun applyFlashState() {
        val camera = activeCamera
        val capture = imageCapture
        if (camera == null || capture == null) return

        val hasFlash = camera.cameraInfo.hasFlashUnit()
        val supported = hasFlash && !isFrontCameraActive
        binding.flashButton.isEnabled = supported
        binding.flashModeText.isEnabled = supported

        if (!supported) {
            capture.flashMode = ImageCapture.FLASH_MODE_OFF
            camera.cameraControl.enableTorch(false)
            return
        }

        when (flashModeOption) {
            FlashModeOption.OFF -> {
                capture.flashMode = ImageCapture.FLASH_MODE_OFF
                camera.cameraControl.enableTorch(false)
            }
            FlashModeOption.ON -> {
                capture.flashMode = ImageCapture.FLASH_MODE_ON
                camera.cameraControl.enableTorch(true)
            }
            FlashModeOption.AUTO -> {
                capture.flashMode = ImageCapture.FLASH_MODE_AUTO
                camera.cameraControl.enableTorch(false)
            }
        }
    }

    private fun toggleFrontCamera() {
        if (frontCameraId == null) {
            Toast.makeText(this, "No front camera detected", Toast.LENGTH_SHORT).show()
            return
        }
        if (isFrontCameraActive) {
            isFrontCameraActive = false
            selectedCameraId = preferredBackLensCameraId()
        } else {
            isFrontCameraActive = true
            selectedCameraId = frontCameraId
        }
        updateFrontCameraUi()
        updateZoomUi()
        refreshActiveSettingsBadge()
        startCamera()
    }

    private fun selectBackLens(index: Int) {
        if (index !in backLensOptions.indices) return
        val previousCameraId = selectedCameraId
        selectedBackLensIndex = index
        val selectedLens = backLensOptions[selectedBackLensIndex]
        selectedCameraId = selectedLens.cameraId
        isFrontCameraActive = false
        updateFrontCameraUi()
        updateZoomUi()
        refreshActiveSettingsBadge()
        if (previousCameraId == selectedCameraId && activeCamera != null) {
            // Same logical camera: apply lens choice via zoom ratio instead of full rebind.
            try {
                activeCamera?.cameraControl?.setZoomRatio(selectedLens.zoomRatio)
                syncZoomSlider()
                binding.statusText.text = "Lens ${selectedLens.label}"
            } catch (_: Exception) {
                startCamera()
            }
        } else {
            startCamera()
        }
    }

    private fun preferredBackLensCameraId(): String? {
        if (backLensOptions.isEmpty()) return null
        val safeIndex = selectedBackLensIndex.coerceIn(0, backLensOptions.lastIndex)
        return backLensOptions[safeIndex].cameraId
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            buildCameraOptions(cameraProvider.availableCameraInfos)
            if (selectedCameraId == null) {
                selectedCameraId = preferredBackLensCameraId()
            }
            isFrontCameraActive = selectedCameraId == frontCameraId
            updateFrontCameraUi()
            updateZoomUi()

            val preview = Preview.Builder()
                .setTargetRotation(lastKnownRotation)
                .build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            val imageCaptureUseCase = ImageCapture.Builder()
                .setTargetRotation(lastKnownRotation)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val analysis = ImageAnalysis.Builder()
                .setTargetRotation(lastKnownRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { image ->
                        processAnalysisFrame(image)
                    }
                }

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        selectedProfile.captureQuality,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            val video = VideoCapture.withOutput(recorder).also {
                it.targetRotation = lastKnownRotation
            }

            val selector = buildCameraSelector(selectedCameraId)

            try {
                cameraProvider.unbindAll()
                val groupBuilder = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(imageCaptureUseCase)
                    .addUseCase(analysis)
                    .addUseCase(video)
                binding.previewView.viewPort?.let { groupBuilder.setViewPort(it) }

                activeCamera = cameraProvider.bindToLifecycle(
                    this,
                    selector,
                    groupBuilder.build()
                )
                previewUseCase = preview
                imageCapture = imageCaptureUseCase
                imageAnalysis = analysis
                videoCapture = video

                refreshApertureOptions()
                applyCameraControls()
                applyFlashState()
                applyFrontPreviewUpscale()
                syncZoomSlider()
                refreshActiveSettingsBadge()
                binding.detectionOverlay.updateBoxes(emptyList())

                binding.statusText.text = "Ready"
                updateModeUi()
            } catch (exc: Exception) {
                binding.statusText.text = "Camera start failed"
                failFast(exc)
                Toast.makeText(this, "Unable to start camera: ${exc.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildCameraOptions(cameraInfos: List<CameraInfo>) {
        cameraOptionMap.clear()
        frontCameraId = null
        val backCandidates = mutableListOf<Pair<String, Float>>()

        cameraInfos.forEachIndexed { index, cameraInfo ->
            val c2Info = Camera2CameraInfo.from(cameraInfo)
            val cameraId = c2Info.cameraId
            val facing = c2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
            val labelPrefix = when (facing) {
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "Back"
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                else -> "Camera"
            }
            cameraOptionMap["$labelPrefix #$index (ID $cameraId)"] = cameraId

            if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT && frontCameraId == null) {
                frontCameraId = cameraId
            }

            if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                val focalArray = c2Info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                )
                val focalCandidates = focalArray
                    ?.filter { it > 0f }
                    ?.distinct()
                    ?.sorted()
                    .orEmpty()
                if (focalCandidates.isNotEmpty()) {
                    focalCandidates.forEach { backCandidates.add(cameraId to it) }
                } else {
                    backCandidates.add(cameraId to Float.MAX_VALUE)
                }
            }
        }

        if (backCandidates.isNotEmpty()) {
            val sorted = backCandidates.sortedBy { it.second }
            backLensCameraIds = sorted.map { it.first }.distinct()
            selectedBackLensIndex = selectedBackLensIndex.coerceIn(0, backLensCameraIds.lastIndex)
        } else {
            backLensCameraIds = cameraOptionMap.values.take(1)
            selectedBackLensIndex = 0
        }

        backLensOptions = buildBackLensOptions(backCandidates)
        selectedBackLensIndex = selectedBackLensIndex.coerceIn(
            0,
            (backLensOptions.lastIndex).coerceAtLeast(0)
        )
        rebuildZoomButtons()

        if (selectedCameraId == null) {
            selectedCameraId = preferredBackLensCameraId() ?: cameraOptionMap.values.firstOrNull()
        } else if (selectedCameraId !in cameraOptionMap.values) {
            selectedCameraId = preferredBackLensCameraId() ?: cameraOptionMap.values.firstOrNull()
        } else {
            val lensIndex = backLensCameraIds.indexOf(selectedCameraId)
            if (lensIndex >= 0) {
                selectedBackLensIndex = lensIndex
            }
        }
        isFrontCameraActive = selectedCameraId == frontCameraId
    }

    private fun buildBackLensOptions(backCandidates: List<Pair<String, Float>>): List<BackLensOption> {
        if (backCandidates.isEmpty()) return emptyList()

        val distinctByLens = backCandidates
            .distinctBy { "${it.first}:${"%.3f".format(Locale.US, it.second)}" }
            .sortedBy { it.second }

        val focalValues = distinctByLens.map { it.second }.sorted()
        val baseFocal = focalValues[focalValues.size / 2].takeIf { it > 0f } ?: 1f

        return distinctByLens.map { (cameraId, focal) ->
            val ratio = (focal / baseFocal).coerceAtLeast(0.1f)
            BackLensOption(
                cameraId = cameraId,
                focalLength = focal,
                zoomRatio = ratio,
                label = formatLensLabel(ratio)
            )
        }
    }

    private fun rebuildZoomButtons() {
        binding.zoomControlsRow.removeAllViews()
        zoomButtons.clear()

        backLensOptions.forEachIndexed { index, lens ->
            val button = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = lens.label
                isAllCaps = false
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                layoutParams = LinearLayout.LayoutParams(dp(58), dp(34)).apply {
                    if (index > 0) marginStart = dp(10)
                }
                setOnClickListener { selectBackLens(index) }
            }
            zoomButtons.add(button)
            binding.zoomControlsRow.addView(button)
        }
        updateUiIconAndNumberRotation(lastKnownRotation)
    }

    private fun updateUiIconAndNumberRotation(surfaceRotation: Int) {
        if (isUiLockedToPortrait) {
            val iconViews = listOf<View>(
                binding.frontCameraButton,
                binding.zoomGridSlider,
                binding.flashButton,
                binding.settingsButton
            )
            iconViews.forEach { it.rotation = 0f }
            zoomButtons.forEach { it.rotation = 0f }
            return
        }

        val targetDegrees = when (surfaceRotation) {
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> 270f
            else -> 0f
        }

        // Rotate icon-like controls only.
        val iconViews = listOf<View>(
            binding.frontCameraButton,
            binding.zoomGridSlider,
            binding.flashButton,
            binding.settingsButton
        )
        iconViews.forEach { view ->
            view.animate()
                .rotation(targetDegrees)
                .setDuration(160L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        // Rotate numeric lens buttons only (e.g. 1x, 2x, 0.5x).
        zoomButtons.forEach { button ->
            val label = button.text?.toString().orEmpty().trim()
            val startsWithNumber = label.firstOrNull()?.isDigit() == true
            button.animate()
                .rotation(if (startsWithNumber) targetDegrees else 0f)
                .setDuration(160L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun formatLensLabel(ratio: Float): String {
        val commonRatios = listOf(0.5f, 1f, 2f, 3f)
        val snapped = commonRatios.minByOrNull { kotlin.math.abs(it - ratio) } ?: ratio
        if (kotlin.math.abs(snapped - ratio) <= 0.3f) {
            return if (snapped >= 1f && kotlin.math.abs(snapped - snapped.toInt().toFloat()) < 0.05f) {
                "${snapped.toInt()}x"
            } else {
                String.format(Locale.US, "%.1fx", snapped)
            }
        }

        val roundedInt = ratio.toInt()
        val isNearInt = kotlin.math.abs(ratio - roundedInt.toFloat()) < 0.12f
        return if (isNearInt && roundedInt >= 1) {
            "${roundedInt}x"
        } else {
            String.format(Locale.US, "%.1fx", ratio)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun buildCameraSelector(cameraId: String?): CameraSelector {
        if (cameraId == null) return CameraSelector.DEFAULT_BACK_CAMERA
        return CameraSelector.Builder()
            .addCameraFilter(object : CameraFilter {
                override fun filter(cameraInfos: List<CameraInfo>): List<CameraInfo> {
                    return cameraInfos.filter {
                        Camera2CameraInfo.from(it).cameraId == cameraId
                    }
                }
            })
            .build()
    }

    private fun runWithTimer(action: () -> Unit) {
        if (isCountdownActive) return
        if (timerSeconds <= 0) {
            triggerShutterFeedback(isVideoAction = currentMode == CaptureMode.VIDEO)
            action()
            return
        }

        isCountdownActive = true
        binding.shutterButton.isEnabled = false
        binding.statusText.text = "Starting in ${timerSeconds}s"
        mainHandler.postDelayed({
            isCountdownActive = false
            binding.shutterButton.isEnabled = true
            triggerShutterFeedback(isVideoAction = currentMode == CaptureMode.VIDEO)
            action()
        }, timerSeconds * 1000L)
    }

    private fun processAnalysisFrame(image: androidx.camera.core.ImageProxy) {
        val luma = image.computeLuma()
        latestSceneLuma = luma
        binding.lightGraph.post {
            binding.lightGraph.addValue(luma)
            binding.lightValueText.text = formatLightStatusText(luma, latestAmbientLux)
        }

        analysisFrameCount++
        val runDetectionThisFrame = analysisFrameCount % 2 == 0
        if (!runDetectionThisFrame || detectorBusy.get()) {
            image.close()
            return
        }

        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            return
        }

        detectorBusy.set(true)
        val inputImage = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        val imageWidth = image.width
        val imageHeight = image.height
        faceDetector.process(inputImage)
            .continueWithTask { faceTask ->
                val faces = if (faceTask.isSuccessful) faceTask.result ?: emptyList() else emptyList()
                objectDetector.process(inputImage).continueWith { objectTask ->
                    val objects = if (objectTask.isSuccessful) objectTask.result ?: emptyList() else emptyList()
                    Pair(faces, objects)
                }
            }
            .addOnCompleteListener { task ->
                val result = if (task.isSuccessful) task.result else null
                val faces = result?.first ?: emptyList()
                val objects = result?.second ?: emptyList()
                updateDetectionOverlay(faces.map { it.boundingBox }, objects.map { it.boundingBox }, imageWidth, imageHeight)
                detectorBusy.set(false)
                image.close()
            }
    }

    private fun updateDetectionOverlay(faceRects: List<Rect>, objectRects: List<Rect>, srcWidth: Int, srcHeight: Int) {
        binding.detectionOverlay.post {
            val overlayW = binding.detectionOverlay.width.toFloat().coerceAtLeast(1f)
            val overlayH = binding.detectionOverlay.height.toFloat().coerceAtLeast(1f)
            val sourceW = srcWidth.toFloat().coerceAtLeast(1f)
            val sourceH = srcHeight.toFloat().coerceAtLeast(1f)
            val sx = overlayW / sourceW
            val sy = overlayH / sourceH

            val boxes = mutableListOf<com.camerax.app.ui.DetectionOverlayView.Box>()
            faceRects.forEach { rect ->
                boxes.add(
                    com.camerax.app.ui.DetectionOverlayView.Box(
                        left = rect.left * sx,
                        top = rect.top * sy,
                        right = rect.right * sx,
                        bottom = rect.bottom * sy,
                        isFace = true
                    )
                )
            }
            objectRects.forEach { rect ->
                boxes.add(
                    com.camerax.app.ui.DetectionOverlayView.Box(
                        left = rect.left * sx,
                        top = rect.top * sy,
                        right = rect.right * sx,
                        bottom = rect.bottom * sy,
                        isFace = false
                    )
                )
            }
            binding.detectionOverlay.updateBoxes(boxes)
        }
    }

    private fun formatLightStatusText(sceneLuma: Float, ambientLux: Float?): String {
        return if (ambientLux != null) {
            "L:${sceneLuma.toInt()} · Lux:${ambientLux.toInt()}"
        } else {
            "L:${sceneLuma.toInt()}"
        }
    }

    private fun syncZoomSlider() {
        val camera = activeCamera ?: return
        val zoomState = camera.cameraInfo.zoomState.value
        val linear = zoomState?.linearZoom ?: 0f
        currentLinearZoom = linear
        binding.zoomGridSlider.setZoomFraction(linear)
    }

    private fun applyPreviewZoomSmooth(targetLinearZoom: Float) {
        val camera = activeCamera ?: return
        val target = targetLinearZoom.coerceIn(0f, 1f)

        zoomAnimator?.cancel()
        val start = currentLinearZoom
        zoomAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 220L
            addUpdateListener { animator ->
                val value = (animator.animatedValue as Float).coerceIn(0f, 1f)
                currentLinearZoom = value
                camera.cameraControl.setLinearZoom(value)
            }
            start()
        }
    }

    private fun applyFrontPreviewUpscale() {
        if (isFrontCameraActive) {
            binding.previewView.scaleX = 1.08f
            binding.previewView.scaleY = 1.08f
        } else {
            binding.previewView.scaleX = 1f
            binding.previewView.scaleY = 1f
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        binding.shutterButton.isEnabled = false

        val takeAction = {
            if (enhancedShotEnabled) {
                captureEnhancedPhoto(capture)
            } else if (xcodeProcessingEnabled) {
                captureBurstCompositePhoto(capture)
            } else {
                capturePhotoToMediaStore(capture)
            }
        }

        if (isFrontCameraActive) {
            runFrontScreenFlashSequence {
                takeAction()
            }
        } else {
            takeAction()
        }
    }

    private fun capturePhotoToMediaStore(capture: ImageCapture) {
        try {
            val outputOptions = createPhotoMediaStoreOutputOptions()
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        val uri = outputFileResults.savedUri
                        clearDisplayFlashOverlay()
                        binding.shutterButton.isEnabled = true
                        binding.statusText.text = if (isFrontCameraActive) "Selfie saved" else "Photo saved"
                        LogFileManager.append(this@MainActivity, "photo_saved_uri=$uri")
                        refreshGalleryThumbnailAsync()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        clearDisplayFlashOverlay()
                        binding.shutterButton.isEnabled = true
                        binding.statusText.text = "Photo failed"
                        LogFileManager.append(this@MainActivity, "photo_error=${exception.message}")
                        Toast.makeText(
                            this@MainActivity,
                            "Photo error: ${exception.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        } catch (e: Exception) {
            clearDisplayFlashOverlay()
            binding.shutterButton.isEnabled = true
            binding.statusText.text = "Photo failed"
            LogFileManager.append(this, "photo_exception=${e.message}")
            failFast(e)
            Toast.makeText(this, "Photo capture failed", Toast.LENGTH_LONG).show()
        }
    }

    private fun captureBurstCompositePhoto(capture: ImageCapture) {
        binding.statusText.text = "Xcode pre-burst..."
        val burstFolder = File(cacheDir, "xcode_burst").apply { mkdirs() }
        // Two immediate preframes + one main frame.
        val frameFiles = (0 until burstFrameCount).map { index ->
            File(burstFolder, "frame_${System.currentTimeMillis()}_$index.jpg")
        }
        val finalTempFile = createTempOutputPhotoFile()

        captureBurstFrameRecursive(capture, frameFiles, 0, 0L) { error ->
            if (error != null) {
                frameFiles.forEach { it.delete() }
                clearDisplayFlashOverlay()
                binding.shutterButton.isEnabled = true
                binding.statusText.text = "Photo failed"
                Toast.makeText(this, "Burst failed: ${error.message}", Toast.LENGTH_LONG).show()
                return@captureBurstFrameRecursive
            }

            cameraExecutor.execute {
                val merged = mergeFramesToJpeg(frameFiles, finalTempFile)
                frameFiles.forEach { it.delete() }

                runOnUiThread {
                    MediaScannerConnection.scanFile(
                        this@MainActivity, arrayOf(finalTempFile.absolutePath), arrayOf("image/jpeg"), null
                    )
                    clearDisplayFlashOverlay()
                    binding.shutterButton.isEnabled = true
                    if (merged) {
                        val savedUri = persistPhotoToMediaStore(finalTempFile)
                        if (savedUri != null) {
                            binding.statusText.text = if (isFrontCameraActive) {
                                "Selfie saved (Xcode)"
                            } else {
                                "Photo saved (Xcode)"
                            }
                            LogFileManager.append(this, "photo_saved_xcode_uri=$savedUri")
                            refreshGalleryThumbnailAsync()
                        } else {
                            binding.statusText.text = "Photo saved (local only)"
                            Toast.makeText(this, "Gallery save failed", Toast.LENGTH_LONG).show()
                            LogFileManager.append(this, "photo_saved_xcode_local=${finalTempFile.absolutePath}")
                        }
                    } else {
                        binding.statusText.text = "Photo failed"
                        Toast.makeText(this, "Xcode merge failed", Toast.LENGTH_LONG).show()
                    }
                    finalTempFile.delete()
                }
            }
        }
    }

    private fun captureEnhancedPhoto(capture: ImageCapture) {
        binding.statusText.text = "Enhanced shot..."
        val sourceFile = createTempOutputPhotoFile()
        val finalTempFile = createTempOutputPhotoFile()
        captureSinglePhotoToFile(
            capture = capture,
            outputFile = sourceFile,
            onSaved = {
                cameraExecutor.execute {
                    val merged = mergeEnhancedSingleFrameToJpeg(sourceFile, finalTempFile)
                    sourceFile.delete()
                    runOnUiThread {
                        clearDisplayFlashOverlay()
                        binding.shutterButton.isEnabled = true
                        if (merged) {
                            val savedUri = persistPhotoToMediaStore(finalTempFile)
                            if (savedUri != null) {
                                binding.statusText.text = "Photo saved (Enhanced)"
                                LogFileManager.append(this, "photo_saved_enhanced_uri=$savedUri")
                                refreshGalleryThumbnailAsync()
                            } else {
                                binding.statusText.text = "Photo saved (local only)"
                                Toast.makeText(this, "Gallery save failed", Toast.LENGTH_LONG).show()
                                LogFileManager.append(this, "photo_saved_enhanced_local=${finalTempFile.absolutePath}")
                            }
                        } else {
                            binding.statusText.text = "Photo failed"
                            Toast.makeText(this, "Enhanced pipeline failed", Toast.LENGTH_LONG).show()
                        }
                        finalTempFile.delete()
                    }
                }
            },
            onError = { error ->
                sourceFile.delete()
                clearDisplayFlashOverlay()
                binding.shutterButton.isEnabled = true
                binding.statusText.text = "Photo failed"
                Toast.makeText(this, "Enhanced shot failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun captureBurstFrameRecursive(
        capture: ImageCapture,
        frameFiles: List<File>,
        index: Int,
        delayMs: Long,
        onDone: (Throwable?) -> Unit
    ) {
        if (index >= frameFiles.size) {
            onDone(null)
            return
        }

        captureSinglePhotoToFile(
            capture = capture,
            outputFile = frameFiles[index],
            onSaved = {
                mainHandler.postDelayed(
                    { captureBurstFrameRecursive(capture, frameFiles, index + 1, delayMs, onDone) },
                    delayMs
                )
            },
            onError = { error -> onDone(error) }
        )
    }

    private fun mergeFramesToJpeg(frameFiles: List<File>, outputFile: File): Boolean {
        if (frameFiles.isEmpty()) return false
        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmaps = frameFiles.mapNotNull { BitmapFactory.decodeFile(it.absolutePath, decodeOptions) }
        if (bitmaps.isEmpty()) return false

        return try {
            // Last captured frame is the main image; preframes are denoise helpers.
            val main = bitmaps.last()
            val ordered = listOf(main) + bitmaps.dropLast(1)
            val base = ordered.first()
            val width = base.width
            val height = base.height
            val compatible = ordered.filter { it.width == width && it.height == height }
            if (compatible.isEmpty()) return false
            val shifts = estimateFrameShifts(compatible, maxShiftPx = 12)
            val overlap = computeOverlapRect(width, height, shifts)
            if (overlap == null) return false

            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val outRow = IntArray(width)
            val aSamples = IntArray(compatible.size)
            val rSamples = IntArray(compatible.size)
            val gSamples = IntArray(compatible.size)
            val bSamples = IntArray(compatible.size)
            val baseRows = IntArray(width)

            for (y in 0 until height) {
                base.getPixels(baseRows, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    if (x < overlap.left || x > overlap.right || y < overlap.top || y > overlap.bottom) {
                        outRow[x] = baseRows[x]
                        continue
                    }
                    var count = 0
                    for (i in compatible.indices) {
                        val sx = x + shifts[i].dx
                        val sy = y + shifts[i].dy
                        if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue
                        val px = compatible[i].getPixel(sx, sy)
                        aSamples[count] = (px ushr 24) and 0xFF
                        rSamples[count] = (px ushr 16) and 0xFF
                        gSamples[count] = (px ushr 8) and 0xFF
                        bSamples[count] = px and 0xFF
                        count++
                    }
                    if (count <= 0) {
                        outRow[x] = baseRows[x]
                    } else {
                        val outA = robustChannelMerge(aSamples, count)
                        val outR = robustChannelMerge(rSamples, count)
                        val outG = robustChannelMerge(gSamples, count)
                        val outB = robustChannelMerge(bSamples, count)
                        // Keep main-frame sharpness: blend robust merge with main frame.
                        val mainPx = baseRows[x]
                        val mainA = (mainPx ushr 24) and 0xFF
                        val mainR = (mainPx ushr 16) and 0xFF
                        val mainG = (mainPx ushr 8) and 0xFF
                        val mainB = mainPx and 0xFF
                        val fA = ((mainA * 3) + outA) / 4
                        val fR = ((mainR * 3) + outR) / 4
                        val fG = ((mainG * 3) + outG) / 4
                        val fB = ((mainB * 3) + outB) / 4
                        outRow[x] = (fA shl 24) or (fR shl 16) or (fG shl 8) or fB
                    }
                }
                out.setPixels(outRow, 0, width, 0, y, width, 1)
            }

            FileOutputStream(outputFile).use { outStream ->
                out.compress(Bitmap.CompressFormat.JPEG, 96, outStream)
            }
            out.recycle()
            true
        } catch (t: Throwable) {
            failFast(t)
            false
        } finally {
            bitmaps.forEach { it.recycle() }
        }
    }

    private fun mergeEnhancedSingleFrameToJpeg(sourceFile: File, outputFile: File): Boolean {
        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val source = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions) ?: return false

        return try {
            val width = source.width
            val height = source.height
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val sourceRow = IntArray(width)
            val outRow = IntArray(width)
            for (y in 0 until height) {
                source.getPixels(sourceRow, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    val px = sourceRow[x]
                    val a = (px ushr 24) and 0xFF
                    val r = (px ushr 16) and 0xFF
                    val g = (px ushr 8) and 0xFF
                    val b = px and 0xFF
                    val gray = ((r * 77 + g * 150 + b * 29) shr 8)

                    val bw = gray
                    val hi = clamp255(((gray - 128f) * 1.25f + 128f))
                    val lo = clamp255(((gray - 128f) * 0.75f + 128f))

                    val red = clamp255(r * 1.08f)
                    val green = clamp255(g * 1.05f)
                    val blue = clamp255(b * 1.10f)

                    val outR = clamp255(bw * 0.18f + hi * 0.18f + lo * 0.10f + red * 0.34f + r * 0.20f)
                    val outG = clamp255(bw * 0.18f + hi * 0.18f + lo * 0.10f + green * 0.34f + g * 0.20f)
                    val outB = clamp255(bw * 0.18f + hi * 0.18f + lo * 0.10f + blue * 0.34f + b * 0.20f)

                    outRow[x] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
                out.setPixels(outRow, 0, width, 0, y, width, 1)
            }

            FileOutputStream(outputFile).use { outStream ->
                out.compress(Bitmap.CompressFormat.JPEG, 96, outStream)
            }
            out.recycle()
            true
        } catch (t: Throwable) {
            failFast(t)
            false
        } finally {
            source.recycle()
        }
    }

    private fun clamp255(value: Float): Int {
        return value.toInt().coerceIn(0, 255)
    }

    private data class OverlapRect(val left: Int, val top: Int, val right: Int, val bottom: Int)
    private data class LumaSample(val width: Int, val height: Int, val pixels: IntArray)

    private fun estimateFrameShifts(bitmaps: List<Bitmap>, maxShiftPx: Int): List<FrameShift> {
        if (bitmaps.isEmpty()) return emptyList()
        val base = bitmaps.first()
        val targetMaxDim = 360f
        val baseMaxDim = max(base.width, base.height).toFloat()
        val downscale = if (baseMaxDim > targetMaxDim) targetMaxDim / baseMaxDim else 1f
        val sampleStep = if (downscale < 0.6f) 2 else 3

        val baseLuma = toLumaSample(base, downscale)
        val shifts = MutableList(bitmaps.size) { FrameShift(0, 0) }
        shifts[0] = FrameShift(0, 0)

        for (i in 1 until bitmaps.size) {
            val candidateLuma = toLumaSample(bitmaps[i], downscale)
            val searchRadius = max(2, (maxShiftPx * downscale).roundToInt())
            val scaledShift = estimateShiftBySad(
                baseLuma = baseLuma,
                candidateLuma = candidateLuma,
                maxShift = searchRadius,
                step = sampleStep
            )
            val scaleBack = 1f / downscale
            shifts[i] = FrameShift(
                dx = (scaledShift.dx * scaleBack).roundToInt(),
                dy = (scaledShift.dy * scaleBack).roundToInt()
            )
        }
        return shifts
    }

    private fun toLumaSample(bitmap: Bitmap, downscale: Float): LumaSample {
        val src = if (downscale < 0.999f) {
            val sw = max(1, (bitmap.width * downscale).roundToInt())
            val sh = max(1, (bitmap.height * downscale).roundToInt())
            Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        } else {
            bitmap
        }

        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            pixels[i] = (r * 77 + g * 150 + b * 29) shr 8
        }
        val result = LumaSample(src.width, src.height, pixels)
        if (src !== bitmap) src.recycle()
        return result
    }

    private fun estimateShiftBySad(
        baseLuma: LumaSample,
        candidateLuma: LumaSample,
        maxShift: Int,
        step: Int
    ): FrameShift {
        val width = baseLuma.width
        val height = baseLuma.height
        if (width <= 0 || height <= 0) return FrameShift(0, 0)
        if (candidateLuma.width != width || candidateLuma.height != height) return FrameShift(0, 0)
        val basePixels = baseLuma.pixels
        val candPixels = candidateLuma.pixels

        val marginX = width / 5
        val marginY = height / 5
        var bestDx = 0
        var bestDy = 0
        var bestScore = Long.MAX_VALUE

        for (dy in -maxShift..maxShift) {
            for (dx in -maxShift..maxShift) {
                var score = 0L
                var count = 0
                var y = marginY
                while (y < height - marginY) {
                    val cy = y + dy
                    if (cy in 0 until height) {
                        var x = marginX
                        while (x < width - marginX) {
                            val cx = x + dx
                            if (cx in 0 until width) {
                                val bi = y * width + x
                                val ci = cy * width + cx
                                score += abs(basePixels[bi] - candPixels[ci]).toLong()
                                count++
                            }
                            x += step
                        }
                    }
                    y += step
                }
                if (count <= 0) continue
                val norm = score / count
                if (norm < bestScore) {
                    bestScore = norm
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return FrameShift(bestDx, bestDy)
    }

    private fun computeOverlapRect(width: Int, height: Int, shifts: List<FrameShift>): OverlapRect? {
        if (shifts.isEmpty()) return null
        var left = 0
        var top = 0
        var right = width - 1
        var bottom = height - 1
        for (shift in shifts) {
            left = max(left, -shift.dx)
            top = max(top, -shift.dy)
            right = minOf(right, (width - 1) - shift.dx)
            bottom = minOf(bottom, (height - 1) - shift.dy)
        }
        if (left >= right || top >= bottom) return null
        return OverlapRect(left, top, right, bottom)
    }

    private fun robustChannelMerge(values: IntArray, count: Int): Int {
        if (count <= 0) return 0
        if (count == 1) return values[0].coerceIn(0, 255)
        Arrays.sort(values, 0, count)
        val median = if (count % 2 == 1) {
            values[count / 2]
        } else {
            (values[(count / 2) - 1] + values[count / 2]) / 2
        }
        if (count < 3) return median.coerceIn(0, 255)
        var sum = 0
        for (i in 0 until count) sum += values[i]
        val mean = sum / count
        return ((median * 3 + mean) / 4).coerceIn(0, 255)
    }

    private fun shiftedPixelOrBase(
        bitmap: Bitmap,
        shift: FrameShift,
        x: Int,
        y: Int,
        fallback: Int,
        width: Int,
        height: Int
    ): Int {
        val sx = x + shift.dx
        val sy = y + shift.dy
        if (sx < 0 || sy < 0 || sx >= width || sy >= height) return fallback
        return bitmap.getPixel(sx, sy)
    }

    private fun createTempOutputPhotoFile(): File {
        val name = "${SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(Date())}.jpg"
        return File(cacheDir, name)
    }

    private fun createPhotoMediaStoreOutputOptions(): ImageCapture.OutputFileOptions {
        val name = "${SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/CameraX")
            }
        }
        return ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()
    }

    private fun persistPhotoToMediaStore(tempFile: File): Uri? {
        val name = "${SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/CameraX")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null
        return try {
            contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(tempFile).use { input -> input.copyTo(out) }
            } ?: return null
            uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            null
        }
    }

    private fun captureSinglePhotoToFile(
        capture: ImageCapture,
        outputFile: File,
        onSaved: () -> Unit,
        onError: (ImageCaptureException) -> Unit
    ) {
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onSaved()
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            }
        )
    }

    private fun startRecording() {
        val capture = videoCapture ?: return
        binding.shutterButton.isEnabled = false
        capture.targetRotation = lastKnownRotation
        activeRecordingTargetHeight = selectedProfile.targetHeight
        applyRecordingStabilizationCrop()

        val outputOptions = mediaStoreVideoOptions()
        var pendingRecording: PendingRecording = capture.output.prepareRecording(this, outputOptions)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingRecording = pendingRecording.withAudioEnabled()
        }

        recording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    binding.shutterButton.isEnabled = true
                    setRecordingIndicator(true)
                    startRecordingTimer()
                    binding.statusText.text = "Recording (${selectedProfile.label})"
                    binding.shutterButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                    vibrateClick(28L)
                    LogFileManager.append(this, "video_record_start")
                }

                is VideoRecordEvent.Finalize -> {
                    binding.shutterButton.isEnabled = true
                    setRecordingIndicator(false)
                    stopRecordingTimer()
                    recording = null
                    restorePostRecordingZoom()
                    if (event.hasError()) {
                        binding.statusText.text = "Recording failed"
                        LogFileManager.append(this, "video_record_error=${event.error} cause=${event.cause?.message}")
                        Toast.makeText(
                            this,
                            "Video save failed (${event.error})",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        LogFileManager.append(this, "video_saved_uri=${event.outputResults.outputUri}")
                        maybeUpscaleVideo(event.outputResults.outputUri, activeRecordingTargetHeight)
                        refreshGalleryThumbnailAsync()
                    }
                    updateModeUi()
                }
            }
        }
    }

    private fun stopRecording() {
        triggerShutterFeedback(isVideoAction = true)
        stopRecordingTimer()
        recording?.stop()
        recording = null
        restorePostRecordingZoom()
        binding.statusText.text = "Finishing..."
        updateModeUi()
    }

    private fun setRecordingIndicator(isRecording: Boolean) {
        if (isRecording) {
            binding.recordingIndicator.visibility = View.VISIBLE
            binding.recordingIndicator.text = "REC 00:00"
            binding.recordingIndicator.alpha = 1f
            binding.recordingIndicator.animate().cancel()
            binding.recordingIndicator.animate()
                .alpha(0.35f)
                .setDuration(550L)
                .withEndAction {
                    if (binding.recordingIndicator.visibility == View.VISIBLE) {
                        binding.recordingIndicator.animate()
                            .alpha(1f)
                            .setDuration(550L)
                            .withEndAction {
                                if (binding.recordingIndicator.visibility == View.VISIBLE) {
                                    setRecordingIndicator(true)
                                }
                            }
                            .start()
                    }
                }
                .start()
        } else {
            binding.recordingIndicator.animate().cancel()
            binding.recordingIndicator.visibility = View.GONE
            binding.recordingIndicator.alpha = 1f
            binding.recordingIndicator.text = "REC 00:00"
        }
    }

    private fun startRecordingTimer() {
        recordingStartElapsedMs = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(recordingTimerRunnable)
        mainHandler.post(recordingTimerRunnable)
    }

    private fun stopRecordingTimer() {
        mainHandler.removeCallbacks(recordingTimerRunnable)
    }

    private fun applyRecordingStabilizationCrop() {
        val camera = activeCamera ?: return
        if (!stabilizationEnabled) return

        val current = camera.cameraInfo.zoomState.value?.linearZoom ?: currentLinearZoom
        preRecordingLinearZoom = current
        val target = max(current, stabilizationCropLinearZoom)
        currentLinearZoom = target
        binding.zoomGridSlider.setZoomFraction(target)
        camera.cameraControl.setLinearZoom(target)
    }

    private fun restorePostRecordingZoom() {
        val previous = preRecordingLinearZoom ?: return
        val camera = activeCamera ?: return
        preRecordingLinearZoom = null
        currentLinearZoom = previous
        binding.zoomGridSlider.setZoomFraction(previous)
        camera.cameraControl.setLinearZoom(previous)
    }

    private fun triggerShutterFeedback(isVideoAction: Boolean) {
        binding.shutterButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        vibrateClick(if (isVideoAction) 28L else 18L)

        binding.shutterButton.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(65)
            .withEndAction {
                binding.shutterButton.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(95)
                    .start()
            }
            .start()

        if (!isVideoAction && !isFrontCameraActive) {
            binding.captureFlashOverlay.animate().cancel()
            binding.captureFlashOverlay.alpha = 0.22f
            binding.captureFlashOverlay.animate()
                .alpha(0f)
                .setDuration(160)
                .start()
        }
    }

    private fun runFrontScreenFlashSequence(onReady: () -> Unit) {
        binding.captureFlashOverlay.animate().cancel()
        binding.captureFlashOverlay.alpha = 0f
        binding.captureFlashOverlay.visibility = View.VISIBLE
        binding.captureFlashOverlay.animate()
            .alpha(0.5f)
            .setDuration(90)
            .withEndAction {
                binding.captureFlashOverlay.animate()
                    .alpha(0.9f)
                    .setDuration(90)
                    .withEndAction {
                        binding.captureFlashOverlay.animate()
                            .alpha(1f)
                            .setDuration(70)
                            .withEndAction {
                                binding.statusText.text = "Front flash ready"
                                onReady()
                            }
                            .start()
                    }
                    .start()
            }
            .start()
    }

    private fun clearDisplayFlashOverlay() {
        binding.captureFlashOverlay.animate().cancel()
        binding.captureFlashOverlay.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction { binding.captureFlashOverlay.visibility = View.VISIBLE }
            .start()
    }

    private fun vibrateClick(durationMs: Long) {
        try {
            val vibrator = getSystemService(Vibrator::class.java) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: SecurityException) {
            failFast(e)
            // Some OEM builds can still deny vibrate despite manifest permission.
        }
    }

    private fun mediaStoreVideoOptions(): MediaStoreOutputOptions {
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis()) + ".mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX")
            }
        }

        return MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(contentValues)
            .build()
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)

        val qualitySpinner = dialogView.findViewById<Spinner>(R.id.qualitySpinner)
        val stabilizationSwitch = dialogView.findViewById<Switch>(R.id.stabilizationSwitch)
        val wbSpinner = dialogView.findViewById<Spinner>(R.id.wbSpinner)
        val timerSpinner = dialogView.findViewById<Spinner>(R.id.timerSpinner)
        val xcodeProcessingSwitch = dialogView.findViewById<Switch>(R.id.xcodeProcessingSwitch)
        val enhancedShotSwitch = dialogView.findViewById<Switch>(R.id.enhancedShotSwitch)

        val qualityLabels = recordingProfiles.map { it.label }

        qualitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qualityLabels)
        wbSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, wbLabels)
        timerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, timerLabels)

        qualitySpinner.setSelection(qualityLabels.indexOf(selectedProfile.label).coerceAtLeast(0))
        wbSpinner.setSelection(wbLabels.indexOfFirst { wbMap[it] == selectedAwbMode }.coerceAtLeast(0))
        timerSpinner.setSelection(timerLabels.indexOfFirst { timerMap[it] == timerSeconds }.coerceAtLeast(0))

        stabilizationSwitch.isChecked = stabilizationEnabled
        xcodeProcessingSwitch.isChecked = xcodeProcessingEnabled
        enhancedShotSwitch.isChecked = enhancedShotEnabled

        val dialog = AlertDialog.Builder(this)
            .setTitle("Camera Settings")
            .setView(dialogView)
            .setNeutralButton("Log folder") { _, _ ->
                logFolderPickerLauncher.launch(null)
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val chosenQuality = qualityLabels[qualitySpinner.selectedItemPosition]
                selectedProfile = recordingProfiles.firstOrNull { it.label == chosenQuality } ?: recordingProfiles[1]
                stabilizationEnabled = stabilizationSwitch.isChecked

                val wbLabel = wbLabels[wbSpinner.selectedItemPosition]
                selectedAwbMode = wbMap[wbLabel] ?: android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO

                val timerLabel = timerLabels[timerSpinner.selectedItemPosition]
                timerSeconds = timerMap[timerLabel] ?: 0
                xcodeProcessingEnabled = xcodeProcessingSwitch.isChecked
                enhancedShotEnabled = enhancedShotSwitch.isChecked

                startCamera()
                binding.statusText.text = "Settings applied"
                refreshActiveSettingsBadge()
            }
            .create()

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun refreshApertureOptions() {
        val camera = activeCamera ?: return
        val cameraInfo = Camera2CameraInfo.from(camera.cameraInfo)
        val apertures = cameraInfo.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)

        availableApertures = apertures ?: floatArrayOf()
        apertureIndex = 0

        if (availableApertures.isEmpty()) {
            binding.apertureText.text = "f/auto"
            binding.apertureMinusButton.isEnabled = false
            binding.aperturePlusButton.isEnabled = false
        } else {
            binding.apertureMinusButton.isEnabled = true
            binding.aperturePlusButton.isEnabled = true
            binding.apertureText.text = "f/${"%.1f".format(Locale.US, availableApertures[apertureIndex])}"
        }
    }

    private fun shiftAperture(delta: Int) {
        if (availableApertures.isEmpty()) {
            Toast.makeText(this, "Aperture not supported on this camera", Toast.LENGTH_SHORT).show()
            return
        }

        apertureIndex = (apertureIndex + delta).coerceIn(0, availableApertures.lastIndex)
        binding.apertureText.text = "f/${"%.1f".format(Locale.US, availableApertures[apertureIndex])}"
        applyCameraControls()
    }

    private fun applyCameraControls() {
        val camera = activeCamera ?: return
        val camera2Control = Camera2CameraControl.from(camera.cameraControl)
        val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)

        val supportedVideoStabilizationModes = camera2Info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        )
        val supportedAwbModes = camera2Info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES
        )
        val supportedAfModes = camera2Info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
        )

        val videoStabilizationMode = if (stabilizationEnabled &&
            supportedVideoStabilizationModes.hasMode(android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        ) {
            android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
        } else {
            android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        }

        val awbMode = if (supportedAwbModes.hasMode(selectedAwbMode)) {
            selectedAwbMode
        } else {
            android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO
        }
        val preferredAfMode = if (currentMode == CaptureMode.VIDEO) {
            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        } else {
            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        val afMode = when {
            supportedAfModes.hasMode(preferredAfMode) -> preferredAfMode
            supportedAfModes.hasMode(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF
        }
        activeAfMode = afMode

        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                videoStabilizationMode
            )
            .setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE,
                awbMode
            )
            .setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                afMode
            )

        if (availableApertures.isNotEmpty()) {
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.LENS_APERTURE,
                availableApertures[apertureIndex]
            )
        }

        camera2Control.setCaptureRequestOptions(builder.build())
        refreshActiveSettingsBadge()
    }

    private fun setupDetectors() {
        val faceOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build()
        faceDetector = FaceDetection.getClient(faceOptions)

        val objectOptions = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build()
        objectDetector = ObjectDetection.getClient(objectOptions)
    }

    private fun registerLightSensor() {
        val sensor = lightSensor ?: return
        sensorManager?.registerListener(
            lightSensorListener,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
    }

    private fun unregisterLightSensor() {
        sensorManager?.unregisterListener(lightSensorListener)
    }

    private fun mandatoryPermissionsGranted(): Boolean {
        return mandatoryPermissions().all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasMediaReadPermission(): Boolean {
        return mediaReadPermissions().all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun mandatoryPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private fun mediaReadPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        permissions.addAll(mandatoryPermissions().toList())
        permissions.addAll(mediaReadPermissions().toList())
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return permissions.toTypedArray()
    }

    private fun orientationToSurfaceRotation(orientation: Int): Int {
        return when {
            orientation in 45..134 -> Surface.ROTATION_270
            orientation in 135..224 -> Surface.ROTATION_180
            orientation in 225..314 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
    }

    private fun readVideoDisplayHeight(uri: Uri): Int {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            retriever.release()
            if (rotation == 90 || rotation == 270) width else height
        } catch (_: Exception) {
            0
        }
    }

    @OptIn(UnstableApi::class)
    private fun maybeUpscaleVideo(inputUri: Uri, targetHeight: Int) {
        if (inputUri == Uri.EMPTY || targetHeight <= 0) {
            binding.statusText.text = "Video saved"
            refreshGalleryThumbnailAsync()
            return
        }

        val inputHeight = readVideoDisplayHeight(inputUri)
        if (inputHeight <= 0 || inputHeight >= targetHeight) {
            binding.statusText.text = "Video saved"
            refreshGalleryThumbnailAsync()
            return
        }

        binding.statusText.text = "Upscaling to ${targetHeight}p..."

        val tempFile = File(cacheDir, "upscale_${System.currentTimeMillis()}_${targetHeight}p.mp4")

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
            .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(targetHeight))))
            .build()

        val composition = Composition.Builder(EditedMediaItemSequence(editedMediaItem)).build()

        val transformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val outputUri = persistUpscaledVideo(tempFile, targetHeight)
                    tempFile.delete()
                    if (outputUri != null) {
                        contentResolver.delete(inputUri, null, null)
                        binding.statusText.text = "Video saved (${targetHeight}p upscaled)"
                    } else {
                        binding.statusText.text = "Video saved (upscale copy failed)"
                    }
                    refreshGalleryThumbnailAsync()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    tempFile.delete()
                    binding.statusText.text = "Video saved (upscale skipped)"
                    refreshGalleryThumbnailAsync()
                }
            })
            .build()

        try {
            transformer.start(composition, tempFile.absolutePath)
        } catch (e: Exception) {
            failFast(e)
            tempFile.delete()
            binding.statusText.text = "Video saved (upscale skipped)"
            refreshGalleryThumbnailAsync()
        }
    }

    private fun persistUpscaledVideo(tempFile: File, targetHeight: Int): Uri? {
        val name = "${SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())}_${targetHeight}p.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        return try {
            contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(tempFile).use { input ->
                    input.copyTo(out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(uri, done, null, null)
            }
            uri
        } catch (_: Exception) {
            contentResolver.delete(uri, null, null)
            null
        }
    }

    private fun openLatestMediaInGallery() {
        if (!hasMediaReadPermission()) {
            permissionsLauncher.launch(requiredPermissions())
            Toast.makeText(this, "Allow media access to open gallery preview", Toast.LENGTH_SHORT).show()
            return
        }
        val cachedUri = latestGalleryMediaUri
        val cachedMime = latestGalleryMediaMime
        if (cachedUri != null && cachedMime != null) {
            launchGalleryViewIntent(cachedUri, cachedMime)
            return
        }

        cameraExecutor.execute {
            val latest = queryLatestGalleryMedia()
            runOnUiThread {
                if (latest != null) {
                    latestGalleryMediaUri = latest.uri
                    latestGalleryMediaMime = latest.mimeType
                    launchGalleryViewIntent(latest.uri, latest.mimeType)
                } else {
                    val fallbackIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    try {
                        startActivity(Intent.createChooser(fallbackIntent, "Open gallery"))
                    } catch (_: Exception) {
                        Toast.makeText(this, "No gallery app available", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun launchGalleryViewIntent(uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Open gallery"))
        } catch (_: Exception) {
            Toast.makeText(this, "No gallery app available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshGalleryThumbnailAsync() {
        if (!hasMediaReadPermission()) {
            latestGalleryMediaUri = null
            latestGalleryMediaMime = null
            binding.galleryThumbPreview.setImageDrawable(ColorDrawable(Color.parseColor("#3E3E3E")))
            return
        }
        cameraExecutor.execute {
            val latest = queryLatestGalleryMedia()
            runOnUiThread {
                if (latest == null) {
                    latestGalleryMediaUri = null
                    latestGalleryMediaMime = null
                    binding.galleryThumbPreview.setImageDrawable(ColorDrawable(Color.parseColor("#3E3E3E")))
                    return@runOnUiThread
                }

                latestGalleryMediaUri = latest.uri
                latestGalleryMediaMime = latest.mimeType

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val thumbnail = contentResolver.loadThumbnail(latest.uri, Size(160, 160), null)
                        binding.galleryThumbPreview.setImageBitmap(thumbnail)
                    } else {
                        binding.galleryThumbPreview.setImageURI(latest.uri)
                    }
                } catch (_: Exception) {
                    binding.galleryThumbPreview.setImageDrawable(ColorDrawable(Color.parseColor("#3E3E3E")))
                }
            }
        }
    }

    private fun queryLatestGalleryMedia(): GalleryMedia? {
        return try {
            val image = queryLatestImageMedia()
            val video = queryLatestVideoMedia()
            when {
                image == null -> video
                video == null -> image
                image.dateTakenMs >= video.dateTakenMs -> image
                else -> video
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun queryLatestImageMedia(): GalleryMedia? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            val dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
            val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)) * 1000L
            val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
            return GalleryMedia(uri, "image/*", if (dateTaken > 0L) dateTaken else dateAdded)
        }
        return null
    }

    private fun queryLatestVideoMedia(): GalleryMedia? {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_TAKEN} DESC, ${MediaStore.Video.Media.DATE_ADDED} DESC"
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
            val dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN))
            val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)) * 1000L
            val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
            return GalleryMedia(uri, "video/*", if (dateTaken > 0L) dateTaken else dateAdded)
        }
        return null
    }

    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }

    private fun failFast(throwable: Throwable) {
        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) throw RuntimeException("Fail-fast", throwable)
    }
}

private fun IntArray?.hasMode(value: Int): Boolean {
    return this?.contains(value) == true
}

private fun androidx.camera.core.ImageProxy.computeLuma(): Float {
    val buffer = planes[0].buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)

    var sum = 0L
    var count = 0
    val step = 8
    var i = 0
    while (i < data.size) {
        sum += (data[i].toInt() and 0xFF)
        count++
        i += step
    }

    if (count == 0) return 0f
    return sum.toFloat() / count
}
