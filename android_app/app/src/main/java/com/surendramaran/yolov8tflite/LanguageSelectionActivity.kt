package com.surendramaran.yolov8tflite

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*

class LanguageSelectionActivity : AppCompatActivity() {

    private val REQUEST_RECORD_AUDIO_PERMISSION = 1
    private lateinit var sharedPreferences: SharedPreferences
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var retryCount = 0 // To limit retries

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_selection)

        sharedPreferences = getSharedPreferences("AppPreferences", MODE_PRIVATE)

        // Check if language is already selected
        val savedLanguage = sharedPreferences.getString("SelectedLanguage", null)
        if (savedLanguage != null) {
            startMainActivity(savedLanguage)
            return
        }

        // Request microphone permission
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
        } else {
            initializeTextToSpeech()
        }
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.getDefault()
                speakOutLanguageOptions()
            } else {
                Toast.makeText(this, "Text-to-Speech initialization failed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun speakOutLanguageOptions() {
        val languageOptions = "Please select your preferred language. Options are: English, Hindi, Marathi, Kannada, Gujarati."

        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (utteranceId == "LANGUAGE_OPTIONS") {
                    runOnUiThread { initializeSpeechRecognition() }
                }
            }

            override fun onError(utteranceId: String?) {}
        })

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "LANGUAGE_OPTIONS")
        textToSpeech?.speak(languageOptions, TextToSpeech.QUEUE_FLUSH, params, "LANGUAGE_OPTIONS")
    }

    private fun initializeSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available.", Toast.LENGTH_SHORT).show()
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Toast.makeText(this@LanguageSelectionActivity, "Listening...", Toast.LENGTH_SHORT).show()
                }

                override fun onResults(results: Bundle?) {
                    val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)
                    spokenText?.let { processLanguageInput(it) }
                }

                override fun onError(error: Int) {
                    handleSpeechRecognitionError(error)
                }

                override fun onBeginningOfSpeech() {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onRmsChanged(rmsdB: Float) {}
            })
        }

        startSpeechRecognition()
    }

    private fun startSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your preferred language.")
        }
        speechRecognizer?.startListening(intent)
    }

    private fun processLanguageInput(language: String) {
        val selectedLanguage = when (language.lowercase(Locale.getDefault())) {
            "english" -> Locale.US
            "hindi" -> Locale("hi")
            "marathi" -> Locale("mr")
            "kannada" -> Locale("kn")
            "gujarati" -> Locale("gu")
            else -> null
        }

        if (selectedLanguage != null) {
            sharedPreferences.edit().putString("SelectedLanguage", selectedLanguage.language).apply()

            val confirmationMessage = "You have selected $language."

            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "LANGUAGE_CONFIRMATION") {
                        runOnUiThread { startMainActivity(selectedLanguage.language) }
                    }
                }

                override fun onError(utteranceId: String?) {}
            })

            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "LANGUAGE_CONFIRMATION")
            textToSpeech?.speak(confirmationMessage, TextToSpeech.QUEUE_FLUSH, params, "LANGUAGE_CONFIRMATION")
        } else {
            textToSpeech?.speak("Unsupported language. Please try again.", TextToSpeech.QUEUE_FLUSH, null, null)
            retrySpeechRecognition()
        }
    }

    private fun startMainActivity(languageCode: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("LanguageCode", languageCode)
        }
        startActivity(intent)
        finish()
    }

    private fun handleSpeechRecognitionError(error: Int) {
        val errorMessage = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match found. Please try again."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy. Please wait."
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout. Please try again."
            else -> "Unknown error occurred"
        }

        textToSpeech?.speak(errorMessage, TextToSpeech.QUEUE_FLUSH, null, null)
        retrySpeechRecognition()
    }

    private fun retrySpeechRecognition() {
        if (retryCount < 3) {
            retryCount++
            Handler(Looper.getMainLooper()).postDelayed({
                startSpeechRecognition()
            }, 3000)
        } else {
            textToSpeech?.speak("Maximum retries reached. Please restart the app.", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeTextToSpeech()
            } else {
                Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
    }
}
 