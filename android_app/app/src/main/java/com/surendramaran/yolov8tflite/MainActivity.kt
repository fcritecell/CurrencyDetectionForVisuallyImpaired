package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var sharedPreferences: SharedPreferences

    private var isFrontCamera = false
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var detector: Detector
    private lateinit var cameraExecutor: ExecutorService

    private var lastSpokenTime = 0L
    private val debounceInterval = 5000L // 5 seconds debounce to avoid repeating output
    private var isTtsInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences("AppPreferences", MODE_PRIVATE)

        // Retrieve selected language from intent or SharedPreferences
        val selectedLanguage = intent.getStringExtra("SELECTED_LANGUAGE")
            ?: sharedPreferences.getString("SelectedLanguage", "en") ?: "en"

        Log.d(TAG, "Selected Language: $selectedLanguage")

        // Save selected language for future sessions
        sharedPreferences.edit().putString("SelectedLanguage", selectedLanguage).apply()

        // Initialize TTS with selected language
        initializeTextToSpeech(selectedLanguage)

        // Initialize the detector
        detector = Detector(baseContext, Constants.MODEL_PATH, Constants.LABELS_PATH, this)
        detector.setup()

        // Check camera permissions and start camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun initializeTextToSpeech(languageCode: String) {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = when (languageCode) {
                    "en" -> Locale.US
                    "hi" -> Locale("hi", "IN")
                    "mr" -> Locale("mr", "IN")
                    "kn" -> Locale("kn", "IN")
                    "gu" -> Locale("gu", "IN")
                    else -> Locale.US
                }

                setTtsLanguage(locale)
            } else {
                Log.e(TAG, "TTS: Initialization failed.")
                showToast("TTS initialization failed.")
            }
        }
    }

    private fun setTtsLanguage(locale: Locale) {
        val result = textToSpeech.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "TTS: Language not supported.")
            showToast("Selected language not supported.")
        } else {
            isTtsInitialized = true
            val languageName = locale.displayLanguage
            val message = "Language set to $languageName"
            Log.d(TAG, "TTS Language initialized successfully: $locale")
            speakOut(message)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f)
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it[Manifest.permission.CAMERA] == true) {
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector.clear()
        cameraExecutor.shutdown()
        textToSpeech.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onEmptyDetect() {
        binding.overlay.invalidate()
    }

    private fun translateText(text: String): String {
        val translations = mapOf(
            "en" to mapOf(
                "Detected" to "Detected",
                "10 rupee note" to "10 rupee note",
                "20 rupee note" to "20 rupee note",
                "50 rupee note" to "50 rupee note",
                "100 rupee note" to "100 rupee note",
                "200 rupee note" to "200 rupee note",
                "500 rupee note" to "500 rupee note",
                "2000 rupee note" to "2000 rupee note"
            ),
            "hi" to mapOf( // Hindi Translations
                "Detected" to "पता चला",
                "10 rupee note" to "10 रुपये का नोट",
                "20 rupee note" to "20 रुपये का नोट",
                "50 rupee note" to "50 रुपये का नोट",
                "100 rupee note" to "100 रुपये का नोट",
                "200 rupee note" to "200 रुपये का नोट",
                "500 rupee note" to "500 रुपये का नोट",
                "2000 rupee note" to "2000 रुपये का नोट"
            ),
            "mr" to mapOf( // Marathi Translations
                "Detected" to "आढळले",
                "10 rupee note" to "10 रुपयांची नोट",
                "20 rupee note" to "20 रुपयांची नोट",
                "50 rupee note" to "50 रुपयांची नोट",
                "100 rupee note" to "100 रुपयांची नोट",
                "200 rupee note" to "200 रुपयांची नोट",
                "500 rupee note" to "500 रुपयांची नोट",
                "2000 rupee note" to "2000 रुपयांची नोट"
            ),
            "kn" to mapOf( // Kannada Translations
                "Detected" to "ಗೊತ್ತಾಗಿದೆ",
                "10 rupee note" to "10 ರೂಪಾಯಿ ನೋಟು",
                "20 rupee note" to "20 ರೂಪಾಯಿ ನೋಟು",
                "50 rupee note" to "50 ರೂಪಾಯಿ ನೋಟು",
                "100 rupee note" to "100 ರೂಪಾಯಿ ನೋಟು",
                "200 rupee note" to "200 ರೂಪಾಯಿ ನೋಟು",
                "500 rupee note" to "500 ರೂಪಾಯಿ ನೋಟು",
                "2000 rupee note" to "2000 ರೂಪಾಯಿ ನೋಟು"
            ),
            "gu" to mapOf( // Gujarati Translations
                "Detected" to "શોધાયું",
                "10 rupee note" to "10 રૂપિયાનો નોટ",
                "20 rupee note" to "20 રૂપિયાનો નોટ",
                "50 rupee note" to "50 રૂપિયાનો નોટ",
                "100 rupee note" to "100 રૂપિયાનો નોટ",
                "200 rupee note" to "200 રૂપિયાનો નોટ",
                "500 rupee note" to "500 રૂપિયાનો નોટ",
                "2000 rupee note" to "2000 રૂપિયાનો નોટ"
            )
        )

        val selectedLang = sharedPreferences.getString("SelectedLanguage", "en") ?: "en"
        val langTranslations = translations[selectedLang] ?: translations["en"]!!

        // Translate detected words
        return text.split(", ").joinToString(", ") { word ->
            langTranslations[word] ?: word  // If not found, keep original
        }
    }


    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }

            val detectedNotes = boundingBoxes.joinToString(", ") { it.clsName }

            if (detectedNotes.isNotEmpty()) {
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastSpokenTime >= debounceInterval) {
                    lastSpokenTime = currentTime

                    val message = "$detectedNotes rupees"
                    val translatedMessage = translateText(message)

                    showToast(translatedMessage)
                    speakOut(translatedMessage)
                }
            }
        }
    }


    private fun speakOut(text: String) {
        if (isTtsInitialized) {
            val translatedText = translateText(text)
            textToSpeech.speak(translatedText, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            Log.e(TAG, "TTS: Not initialized yet.")
            showToast("TTS not ready.")
        }
    }


    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
