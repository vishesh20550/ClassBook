package com.example.classbook

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.classbook.utils.NetworkUtils
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.ktx.Firebase
import com.google.firebase.auth.ktx.auth

class MainActivity : AppCompatActivity() {
    private var emailEditText: EditText? = null
    private var passwordEditText: EditText? = null
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        FirebaseApp.initializeApp(this)
        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        auth = Firebase.auth

        val currentUser = auth.currentUser
        if (currentUser != null) {
            Toast.makeText(this, "Already logged in", Toast.LENGTH_SHORT).show()
            login()
        }
    }

    fun loginClicked(view: View) {
        if (!NetworkUtils.isInternetAvailable(this)) {
            Toast.makeText(this, "No internet connection. Please check your network.", Toast.LENGTH_SHORT).show()
            return
        }
        val emailText = emailEditText?.text.toString()
        val passwordText = passwordEditText?.text.toString()

        if (emailText.isEmpty() || passwordText.isEmpty()) {
            Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
            return
        }

        auth.signInWithEmailAndPassword(emailText, passwordText)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Toast.makeText(this, "Login successful", Toast.LENGTH_SHORT).show()
                    login()
                } else {
                    val exception = task.exception
                    when (exception) {
                        is FirebaseAuthInvalidUserException -> {
                            Toast.makeText(this, "Email not found. Please sign up.", Toast.LENGTH_SHORT).show()
                            val intent = Intent(this, SignUpActivity::class.java)
                            startActivity(intent)
                        }
                        is FirebaseAuthInvalidCredentialsException -> {
                            Toast.makeText(this, "Incorrect password. Please try again.", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            Toast.makeText(this, "Login failed: ${exception?.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
    }

    fun signUpClicked(view: View) {
        val intent = Intent(this, SignUpActivity::class.java)
        startActivity(intent)
    }

    private fun login() {
        val intent = Intent(this, ClassListActivity::class.java)
        startActivity(intent)
        finish()
    }
}
