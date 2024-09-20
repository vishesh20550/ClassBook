package com.example.classbook

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import androidx.activity.addCallback
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.classbook.utils.NetworkUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

class ClassListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var classAdapter: ClassAdapter
    private lateinit var addButton: Button

    private val databaseRef = FirebaseDatabase.getInstance().reference.child("classes")
    private val currentUser: String = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private var className: String = ""
    private var selectedPdfUri: Uri? = null
    private var selectedPdfName: String = ""
    private lateinit var dialog: AlertDialog
    private lateinit var addClassButton: Button
    private lateinit var pdfNameTextView: TextView
    private lateinit var progressBar: ProgressBar

    private var resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { pdfUri ->
                selectedPdfUri = pdfUri
                selectedPdfName = getFileNameFromUri(pdfUri)
                addClassButton.isEnabled = true
                pdfNameTextView.text = selectedPdfName
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set your layout file
        setContentView(R.layout.activity_class_list)

        recyclerView = findViewById(R.id.recyclerView)
        addButton = findViewById(R.id.addClassButton)

        recyclerView.layoutManager = LinearLayoutManager(this)
        classAdapter = ClassAdapter(mutableListOf()) { classModel ->
            // Check if scriptId is empty or null
            if (classModel.scriptId.isEmpty()) {
                Toast.makeText(this, "Script is not ready yet. Trying to generate script again.", Toast.LENGTH_SHORT).show()
                // Try to generate script again
                if (!NetworkUtils.isInternetAvailable(this)) {
                    Toast.makeText(this, "No internet connection. Please check your network.", Toast.LENGTH_SHORT).show()
                    return@ClassAdapter
                }
                regenerateScript(classModel)
            } else {
                val intent = Intent(this, ClassDetailsActivity::class.java)
                intent.putExtra("classId", classModel.id)
                intent.putExtra("scriptId", classModel.scriptId)
                startActivity(intent)
            }
        }
        recyclerView.adapter = classAdapter

        fetchClasses()

        addButton.setOnClickListener {
            if (!NetworkUtils.isInternetAvailable(this)) {
                Toast.makeText(this, "No internet connection. Please check your network.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showAddClassDialog()
        }

        onBackPressedDispatcher.addCallback(this) {
            val back = AlertDialog.Builder(this@ClassListActivity)
            back.setTitle("Logout")
            back.setMessage("Are you sure?")
            back.setPositiveButton("Yes") { _, _ ->
                FirebaseAuth.getInstance().signOut()
                finish()
            }
            back.setNegativeButton("Cancel", null)
            val alertDialog: AlertDialog = back.create()
            alertDialog.setCancelable(false)
            alertDialog.show()
        }
    }

    private fun fetchClasses() {
        databaseRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                val classList = mutableListOf<ClassModel>()
                for (classSnapshot in dataSnapshot.children) {
                    val classModel = classSnapshot.getValue(ClassModel::class.java)
                    if (classModel != null && classModel.users.contains(currentUser)) {
                        classList.add(classModel)
                    }
                }
                classAdapter.updateClasses(classList)
            }

            override fun onCancelled(databaseError: DatabaseError) {
                Log.e("Database Error", databaseError.message)
            }
        })
    }

    private fun showAddClassDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_class, null)
        val classNameEditText = dialogView.findViewById<EditText>(R.id.classNameEditText)
        val choosePdfButton = dialogView.findViewById<Button>(R.id.choosePdfButton)
        pdfNameTextView = dialogView.findViewById<TextView>(R.id.pdfNameTextView)
        addClassButton = dialogView.findViewById<Button>(R.id.addClassDialogButton)
        progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)

        addClassButton.isEnabled = false

        choosePdfButton.setOnClickListener {
            getPdf()
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Add New Class")
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("Cancel", null)
            .create()

        addClassButton.setOnClickListener {
            if (!NetworkUtils.isInternetAvailable(this)) {
                Toast.makeText(this, "No internet connection. Please check your network.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            className = classNameEditText.text.toString()

            if (className.isNotBlank() && selectedPdfUri != null) {
                progressBar.visibility = ProgressBar.VISIBLE
                uploadPdfAndProcess(selectedPdfUri!!)
            } else {
                Toast.makeText(this, "Please provide a class name and select a PDF.", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun getPdf() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/pdf"
        }
        resultLauncher.launch(intent)
    }

    private fun uploadPdfAndProcess(pdfUri: Uri) {
        // Generate a unique pdfId
        val pdfId = UUID.randomUUID().toString()
        val storageRef = FirebaseStorage.getInstance().reference.child("class_pdfs/$pdfId")
        val uploadTask = storageRef.putFile(pdfUri)

        uploadTask.addOnSuccessListener {
            // PDF uploaded successfully
            // Call the API with the pdfId
            processPdfThroughApi(pdfId) { scriptId ->
                // Add new class with pdfId and scriptId (could be null)
                addNewClass(pdfId, scriptId ?: "")
            }
        }.addOnFailureListener {
            progressBar.visibility = View.INVISIBLE
            Toast.makeText(this, "Failed to upload PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processPdfThroughApi(pdfId: String, callback: (String?) -> Unit) {
        // Use Kotlin coroutines to make the network call on a background thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scriptId = callApi(pdfId)
                withContext(Dispatchers.Main) {
                    callback(scriptId)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("API Error", e.toString())
                    callback(null)
                }
            }
        }
    }

    private suspend fun callApi(pdfId: String): String? {
        return withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)    // Default connect timeout
                .writeTimeout(10, TimeUnit.SECONDS)      // Default write timeout
                .readTimeout(10, TimeUnit.MINUTES)       // Increase read timeout to 10 minutes
                .build()

            val json = JSONObject()
            json.put("pdfId", pdfId)
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = json.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("https://convert-pdf-jwbsn5qtsa-el.a.run.app/") // Replace with your API endpoint
                .post(requestBody)
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val responseJson = responseBody?.let { JSONObject(it) }
                    return@withContext responseJson?.getString("script_id")
                } else {
                    Log.e("API Error", "Response code: ${response.code}")
                    return@withContext null
                }
            } catch (e: IOException) {
                Log.e("API Error", e.toString())
                return@withContext null
            }
        }
    }

    private fun addNewClass(pdfId: String, scriptId: String) {
        val classId = databaseRef.push().key!!
        val newClass = ClassModel(
            id = classId,
            name = className,
            pdfId = pdfId,
            scriptId = scriptId,
            users = mutableListOf(currentUser)
        )
        databaseRef.child(classId).setValue(newClass).addOnCompleteListener {
            progressBar.visibility = View.INVISIBLE
            if (it.isSuccessful) {
                Toast.makeText(this, "Class added successfully", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                fetchClasses()
            } else {
                Toast.makeText(this, "Failed to add class: ${it.exception?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun regenerateScript(classModel: ClassModel) {
        // Show a progress dialog or some indication to the user
        val progressDialog = ProgressDialog(this)
        progressDialog.setMessage("Regenerating script, please wait...")
        progressDialog.setCancelable(false)
        progressDialog.show()

        processPdfThroughApi(classModel.pdfId) { scriptId ->
            progressDialog.dismiss()
            if (!scriptId.isNullOrEmpty()) {
                // Update the classModel and Firebase
                classModel.scriptId = scriptId
                databaseRef.child(classModel.id).setValue(classModel).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(this, "Script generated successfully.", Toast.LENGTH_SHORT).show()
                        // Start the ClassDetailsActivity
                        val intent = Intent(this, ClassDetailsActivity::class.java)
                        intent.putExtra("classId", classModel.id)
                        intent.putExtra("scriptId", classModel.scriptId)
                        startActivity(intent)
                    } else {
                        Toast.makeText(this, "Failed to update class with new script.", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Script could not be generated, please try again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return if (cursor != null && cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.getString(nameIndex) ?: "Unknown"
        } else {
            "Unknown"
        }.also {
            cursor?.close()
        }
    }
}
