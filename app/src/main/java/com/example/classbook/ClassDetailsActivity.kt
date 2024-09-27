package com.example.classbook

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.media.AudioManager
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.storage.FirebaseStorage
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import java.io.IOException
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class ClassDetailsActivity : AppCompatActivity(), RecognitionListener{

    private lateinit var scriptTextView: TextView
    private lateinit var startButton: Button
    private lateinit var resetButton: Button
    private lateinit var stopButton: Button
    private lateinit var refreshButton: Button
    private lateinit var skipButton: Button
    private lateinit var classId: String
    private lateinit var scriptId: String
    private var isListening = false
    private var currentWordIndex = 0
    private var isScriptComplete = false
    private var script: String = ""
    private lateinit var audioManager: AudioManager
    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private lateinit var wordsInScript : List<String>
    private lateinit var  visibleScriptWords : List<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set your layout file
        setContentView(R.layout.activity_class_details)

        // Retrieve classId and scriptId from the intent
        classId = intent.getStringExtra("classId") ?: ""
        scriptId = intent.getStringExtra("scriptId") ?: ""

        scriptTextView = findViewById(R.id.scriptTextView)
        resetButton = findViewById(R.id.resetButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        refreshButton = findViewById(R.id.refreshButton)
        skipButton = findViewById(R.id.skipButton)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Fetch the script using scriptId
        fetchScriptFromFirebase(scriptId)

        // Set up the refresh functionality
        refreshButton.setOnClickListener {
            fetchScriptFromFirebase(scriptId)
        }

        checkAudioPermission()

        // Disable buttons until script is loaded
        startButton.isEnabled = false
        stopButton.isEnabled = false
        resetButton.isEnabled = false
        skipButton.isEnabled =false;

        // Set up button click listeners
        startButton.setOnClickListener {
            if (!isListening && script.isNotEmpty()) {
                startListening()
            } else if (script.isEmpty()) {
                Toast.makeText(this, "Script not loaded yet. Please refresh.", Toast.LENGTH_SHORT).show()
            }
        }
        skipButton.setOnClickListener {
            if(!isScriptComplete){
                currentWordIndex++
                updateHighlightedText("")
                if (currentWordIndex >= wordsInScript.size) {
                    stopListening()
                    isScriptComplete = true
                    Toast.makeText(this, "Script Complete", Toast.LENGTH_SHORT).show()
                }
            }
        }

        stopButton.setOnClickListener {
            if (isListening) {
                stopListening()
            }
        }

        resetButton.setOnClickListener {
            if (script.isNotEmpty()) {
                resetTeleprompter()
            } else {
                Toast.makeText(this, "Script not loaded yet. Please refresh.", Toast.LENGTH_SHORT).show()
            }
        }
        loadModel()
    }

    private fun fetchScriptFromFirebase(scriptId: String) {
        val storageRef = FirebaseStorage.getInstance().reference.child("class_scripts/$scriptId.txt")
        val localFile = File.createTempFile("script", "txt")

        storageRef.getFile(localFile).addOnSuccessListener {
            // Successfully downloaded the file
            loadScriptFromFile(localFile)

        }.addOnFailureListener {
            Toast.makeText(this, "Failed to fetch script. Please refresh.", Toast.LENGTH_SHORT).show()
            // Keep buttons disabled
            startButton.isEnabled = false
            resetButton.isEnabled = false
            stopButton.isEnabled=false
            skipButton.isEnabled =false
            refreshButton.isEnabled=true
        }
    }

    private fun loadScriptFromFile(file: File) {
        try {
            val bufferedReader = BufferedReader(FileReader(file))
            val stringBuilder = StringBuilder()
            var line: String?

            while (bufferedReader.readLine().also { line = it } != null) {
                stringBuilder.append(line).append("\n")
            }
            bufferedReader.close()

            script = stringBuilder.toString()
            scriptTextView.text = script
            visibleScriptWords = script.split(" ")
            wordsInScript = script.split(Regex("\\W+"))
            refreshButton.isEnabled=false
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load script", Toast.LENGTH_SHORT).show()
            startButton.isEnabled = false
            resetButton.isEnabled = false
            stopButton.isEnabled=false
            skipButton.isEnabled =false
            refreshButton.isEnabled=true
        }
    }

    private fun loadModel() {
        StorageService.unpack(this, "vosk-model-small-en-in-0.4", "model",
            { model: Model ->
                this.model = model
                Log.i("VoskModel", "Model loaded successfully")
                setupRecognizer(model)
                // Enable the start button
                startButton.isEnabled = true
                stopButton.isEnabled=false
                resetButton.isEnabled = true
                skipButton.isEnabled=false
            },
            { exception: IOException ->
                Log.e("VoskModel", "Failed to unpack the model", exception)
                Toast.makeText(this, "Failed to load model", Toast.LENGTH_LONG).show()
                startButton.isEnabled = false
                resetButton.isEnabled = false
                stopButton.isEnabled=false
                skipButton.isEnabled=false
            })
    }
    private fun setupRecognizer(model: Model) {
        recognizer = Recognizer(model, 16000.0f)
    }

    private fun resetTeleprompter() {
        currentWordIndex = 0
        isScriptComplete = false
        scriptTextView.text = script
        stopListening()
        startListening()
    }

    private fun startListening() {
        if (recognizer == null) {
            Toast.makeText(this, "Model not loaded yet", Toast.LENGTH_SHORT).show()
            return
        }
        startButton.isEnabled = false
        skipButton.isEnabled=true;
        stopButton.isEnabled = true
        resetButton.isEnabled = true
        isListening = true
        speechService = SpeechService(recognizer, 16000.0f)
        speechService?.startListening(this)
    }

    private fun stopListening() {
        startButton.isEnabled = true
        stopButton.isEnabled = false
        skipButton.isEnabled=false;
        resetButton.isEnabled = true
        isListening = false
        speechService?.stop()
    }

    private fun updateHighlightedText(spokenText: String) {
        Log.d("SpokenText", spokenText)
        val spokenWords = spokenText.split(Regex("\\W+"))
        for (spokenWord in spokenWords) {
            if (currentWordIndex < wordsInScript.size && wordsInScript[currentWordIndex].equals(spokenWord, ignoreCase = true)) {
                currentWordIndex++
            }
            if (currentWordIndex >= wordsInScript.size && !isScriptComplete) {
                stopListening()
                isScriptComplete = true
                skipButton.isEnabled=false;
                Toast.makeText(this, "Script Complete", Toast.LENGTH_SHORT).show()
            }
        }
        val highlightedText = visibleScriptWords.mapIndexed { index, word ->
            if (index < currentWordIndex) {
                "<font color='#FF0000'>$word</font>"
            } else {
                word
            }
        }.joinToString(" ")
        scriptTextView.text = android.text.Html.fromHtml(highlightedText, android.text.Html.FROM_HTML_MODE_LEGACY)
    }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        if (!hypothesis.isNullOrEmpty()) {
            try {
                // Parse the hypothesis as a JSON object
                val jsonObject = JSONObject(hypothesis)
                // Extract the 'partial' value if it exists
                val partial = jsonObject.optString("partial")
                if (partial.isNotEmpty()) {
                    updateHighlightedText(partial)
                }
            } catch (e: Exception) {
                Log.e("VoskPartialResultError", "Error parsing partial result", e)
            }
        }
    }

    override fun onResult(hypothesis: String?) {
        if (!hypothesis.isNullOrEmpty()) {
            try {
                // Parse the hypothesis as a JSON object
                val jsonObject = JSONObject(hypothesis)
                // Extract the 'text' value if it exists
                val text = jsonObject.optString("text")
                if (text.isNotEmpty()) {
                    updateHighlightedText(text)
                }
            } catch (e: Exception) {
                Log.e("VoskResultError", "Error parsing result", e)
            }
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        if (!hypothesis.isNullOrEmpty()) {
            try {
                // Parse the hypothesis as a JSON object
                val jsonObject = JSONObject(hypothesis)
                // Extract the 'text' value if it exists
                val text = jsonObject.optString("text")
                if (text.isNotEmpty()) {
                    updateHighlightedText(text)
                }
            } catch (e: Exception) {
                Log.e("VoskFinalResultError", "Error parsing final result", e)
            }
            stopListening()
        }
    }

    override fun onError(e: Exception?) {
        Log.e("VoskError", "Error during recognition", e)
    }

    override fun onTimeout() {
        stopListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
//                Toast.makeText(this, "Please Wait!!",Toast.LENGTH_SHORT).show()
            } else {
                // Permission denied
                Toast.makeText(this, "Microphone permission is required for this app", Toast.LENGTH_SHORT).show()
                checkAudioPermission()
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        speechService?.shutdown()
    }
}