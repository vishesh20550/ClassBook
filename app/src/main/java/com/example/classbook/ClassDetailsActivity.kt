package com.example.classbook

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.storage.FirebaseStorage
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.regex.Pattern

data class ScriptLine(val type: String, val text: String)

class ClassDetailsActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var scriptTextView: TextView
    private lateinit var startButton: Button
    private lateinit var resetButton: Button
    private lateinit var stopButton: Button
    private lateinit var skipButton: Button
    private lateinit var refreshButton:Button
    private lateinit var classId: String
    // List to store start and end indices of each /teacher word in the Spannable
    private val teacherWordPositions = mutableListOf<Pair<Int, Int>>()

    // Spannable for scriptTextView
    private lateinit var spannable: SpannableStringBuilder
    private lateinit var scriptId: String
    private var isListening = false
    private lateinit var scriptLines: MutableList<ScriptLine>
    private var currentWordIndex = 0
    private var isScriptComplete = false
    private var teacherScript = "" // Only /teacher content
    private var fullScript = "" // Full script for display
    private lateinit var teacherWords: List<String>
    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private lateinit var visibleScriptWords : List<String>
    private lateinit var wordsInScript: List<String>
    private var script = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_class_details)

        classId = intent.getStringExtra("classId") ?: ""
        scriptId = intent.getStringExtra("scriptId") ?: ""

        scriptTextView = findViewById(R.id.scriptTextView)
        resetButton = findViewById(R.id.resetButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        skipButton = findViewById(R.id.skipButton)
        refreshButton = findViewById(R.id.refreshButton)

        resetButton.isEnabled = false
        startButton.isEnabled = false
        stopButton.isEnabled = false
        skipButton.isEnabled = false
        refreshButton.isEnabled = false

        fetchScriptFromFirebase(scriptId)
        checkAudioPermission()

        // Button listeners
        startButton.setOnClickListener { if (!isListening && teacherScript.isNotEmpty()) startListening() }
        stopButton.setOnClickListener { if (isListening) stopListening() }
        resetButton.setOnClickListener { resetTeleprompter() }
        skipButton.setOnClickListener { skipWord() }

        loadModel()
    }

    // Firebase script fetch
    private fun fetchScriptFromFirebase(scriptId: String) {
        val storageRef = FirebaseStorage.getInstance().reference.child("class_scripts/$scriptId.txt")
        val localFile = File.createTempFile("script", "txt")

        storageRef.getFile(localFile).addOnSuccessListener {
            loadScriptFromFile(localFile)
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to fetch script.", Toast.LENGTH_SHORT).show()
        }
    }

    // Parse the script and separate sections based on tags
    private fun loadScriptFromFile(file: File) {
        try {
            val bufferedReader = BufferedReader(FileReader(file))
            val scriptLines = mutableListOf<ScriptLine>()
            var line: String?

            // StringBuilder to accumulate /teacher text for speech comparison
            val teacherScriptBuilder = StringBuilder()

            // Regex pattern to identify tags and their corresponding text
            val tagPattern = Pattern.compile("/(\\w+):([^/]+)")

            while (bufferedReader.readLine().also { line = it } != null) {
                val matcher = tagPattern.matcher(line)
                var lastIndex = 0

                while (matcher.find()) {
                    val tag = matcher.group(1)
                    val text = matcher.group(2).trim()
                    val start = matcher.start()

                    // Text before the current tag
                    if (start > lastIndex) {
                        val beforeText = line!!.substring(lastIndex, start).trim()
                        if (beforeText.isNotEmpty()) {
                            scriptLines.add(ScriptLine("normal", beforeText))
                        }
                    }

                    // Add the tagged text
                    scriptLines.add(ScriptLine(tag.lowercase(), text))

                    // Accumulate /teacher text
                    if (tag.equals("teacher", ignoreCase = true)) {
                        teacherScriptBuilder.append(text).append(" ")
                    }

                    lastIndex = matcher.end()
                }

                // Text after the last tag in the line
                if (lastIndex < line!!.length) {
                    val afterText = line!!.substring(lastIndex).trim()
                    if (afterText.isNotEmpty()) {
                        scriptLines.add(ScriptLine("normal", afterText))
                    }
                }

                // Add a newline for each original line
                scriptLines.add(ScriptLine("newline", "\n"))
            }
            bufferedReader.close()

            // Remove the last newline if present
            if (scriptLines.isNotEmpty() && scriptLines.last().type == "newline") {
                scriptLines.removeAt(scriptLines.size - 1)
            }

            // Set the full script for display
            fullScript = buildFullScript(scriptLines)
            displayFormattedScript(scriptLines)

            // Set the teacher script for speech comparison
            teacherScript = teacherScriptBuilder.toString().trim()
            teacherWords = teacherScript.split(Regex("\\W+"))
            wordsInScript = teacherWords
            resetButton.isEnabled = true
            startButton.isEnabled = true
            skipButton.isEnabled = true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load script", Toast.LENGTH_SHORT).show()
            // Disable buttons if script loading fails
            startButton.isEnabled = false
            resetButton.isEnabled = false
            stopButton.isEnabled = false
            skipButton.isEnabled = false
            refreshButton.isEnabled = true
        }
    }

    // Extract text after a specific tag, handling inline tags
    private fun extractTagText(line: String, tag: String): String {
        val startIndex = line.indexOf(tag)
        return if (startIndex != -1) {
            val afterTag = line.substring(startIndex + tag.length)
            afterTag.substringBefore("/instruction:").trim()
        } else {
            ""
        }
    }

    // Build the full script as a single string
    private fun buildFullScript(scriptLines: List<ScriptLine>): String {
        val stringBuilder = StringBuilder()
        scriptLines.forEach { line ->
            stringBuilder.append(line.text).append("\n")
        }
        return stringBuilder.toString()
    }

    // Display the formatted script with proper styling
    private fun displayFormattedScript(scriptLines: List<ScriptLine>) {
        spannable = android.text.SpannableStringBuilder()
        teacherWordPositions.clear()

        scriptLines.forEach { line ->
            when (line.type) {
                "teacher" -> {
                    val words = line.text.split(" ")
                    words.forEach { word ->
                        val start = spannable.length
                        spannable.append(word).append(" ")
                        val end = spannable.length
                        // Apply red color for /teacher text
                        spannable.setSpan(
                            android.text.style.ForegroundColorSpan(Color.BLACK),
                            start,
                            end,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        // Record positions for highlighting
                        teacherWordPositions.add(Pair(start, end - 1)) // Exclude space
                    }
                    // Remove the extra space and add newline
                    if (spannable.endsWith(" ")) {
                        spannable.delete(spannable.length - 1, spannable.length)
                    }
                    spannable.append("\n")
                }
                "instruction" -> {
                    val start = spannable.length
                    spannable.append(line.text).append("\n")
                    // Apply blue color for /instruction text
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(Color.BLUE),
                        start,
                        spannable.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                "heading" -> {
                    val start = spannable.length
                    spannable.append(line.text).append("\n")
                    // Apply bold and larger size for /heading text
                    spannable.setSpan(
                        android.text.style.StyleSpan(Typeface.BOLD),
                        start,
                        spannable.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        android.text.style.RelativeSizeSpan(1.5f),
                        start,
                        spannable.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                "normal" -> {
                    spannable.append(line.text).append("\n")
                }
                "newline" -> {
                    spannable.append("\n")
                }
            }
        }

        // Assign the formatted spannable to the TextView
        scriptTextView.text = spannable
    }
    // Update highlighted text based on recognized speech
    private fun updateHighlightedText(spokenText: String) {
        Log.d("SpokenText", spokenText)
        val spokenWords = spokenText.split(Regex("\\W+"))

        for (spokenWord in spokenWords) {
            if (currentWordIndex < wordsInScript.size && wordsInScript[currentWordIndex].equals(spokenWord, ignoreCase = true)) {
                currentWordIndex++
                if (currentWordIndex >= wordsInScript.size && !isScriptComplete) {
                    stopListening()
                    isScriptComplete = true
                    skipButton.isEnabled = false
                    Toast.makeText(this, "Script Complete", Toast.LENGTH_SHORT).show()
                }
            }
        }

        highlightScriptUpToCurrentWord()
    }

    // Highlight /teacher text up to the current word index
    private fun highlightScriptUpToCurrentWord() {
        if (::spannable.isInitialized) {
            // Apply background highlights up to currentWordIndex
            for (i in 0 until currentWordIndex) {
                if (i < teacherWordPositions.size) {
                    val (start, end) = teacherWordPositions[i]
                    spannable.setSpan(
                        BackgroundColorSpan(Color.YELLOW),
                        start,
                        end,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            // Update the TextView with the new spans
            scriptTextView.text = spannable
        }
    }

    private fun resetTeleprompter() {
        currentWordIndex = 0
        isScriptComplete = false
        displayFormattedScript(parseFullScript(fullScript))
        startListening()
    }

    // Parse the full script back into a list of ScriptLine objects
    private fun parseFullScript(fullScript: String): List<ScriptLine> {
        val scriptLines = mutableListOf<ScriptLine>()
        val lines = fullScript.split("\n")
        lines.forEach { line ->
            when {
                line.startsWith("/teacher:") -> {
                    val teacherText = extractTagText(line, "/teacher:")
                    scriptLines.add(ScriptLine("teacher", teacherText))
                }
                line.startsWith("/instruction:") -> {
                    val instructionText = extractTagText(line, "/instruction:")
                    scriptLines.add(ScriptLine("instruction", instructionText))
                }
                line.startsWith("/heading:") -> {
                    val headingText = extractTagText(line, "/heading:")
                    scriptLines.add(ScriptLine("heading", headingText))
                }
                else -> {
                    scriptLines.add(ScriptLine("normal", line))
                }
            }
        }
        return scriptLines
    }

    private fun skipWord() {
        if (currentWordIndex < wordsInScript.size) {
            currentWordIndex++
            highlightScriptUpToCurrentWord()
        }
    }




    // Load Vosk model for speech recognition
    private fun loadModel() {
        StorageService.unpack(this, "vosk-model-small-en-in-0.4", "model", { model ->
            this.model = model
            recognizer = Recognizer(model, 16000.0f)
            startButton.isEnabled = true
        }, { e ->
            Log.e("VoskModel", "Failed to load model", e)
        })
    }

    private fun startListening() {
        if (recognizer == null) {
            Toast.makeText(this, "Model not loaded yet", Toast.LENGTH_SHORT).show()
            return
        }
        speechService = SpeechService(recognizer, 16000.0f)
        speechService?.startListening(this)
        isListening = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
    }

    // Stop voice input
    private fun stopListening() {
        speechService?.stop()
        isListening = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
        Log.e("VoskError", "Recognition error", e)
    }

    override fun onTimeout() {
        stopListening()
    }
}
