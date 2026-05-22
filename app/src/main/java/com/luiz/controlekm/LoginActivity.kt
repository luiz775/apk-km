package com.luiz.controlekm

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class LoginActivity : AppCompatActivity() {

    private val auth by lazy { Firebase.auth }

    override fun onCreate(savedInstanceState: Bundle?) {
        val themePrefs = getSharedPreferences("DadosApp", MODE_PRIVATE)
        val savedTheme = themePrefs.getInt("tema_preferido", AppCompatDelegate.MODE_NIGHT_YES)
        AppCompatDelegate.setDefaultNightMode(savedTheme)
        super.onCreate(savedInstanceState)

        // Se já estiver logado, vai direto para a MainActivity
        if (auth.currentUser != null) {
            abrirApp()
        }

        setContentView(R.layout.activity_login)

        val editEmail = findViewById<EditText>(R.id.editEmailLogin)
        val editSenha = findViewById<EditText>(R.id.editSenhaLogin)
        val btnEntrar = findViewById<Button>(R.id.btnLogin)
        val txtCriarConta = findViewById<TextView>(R.id.txtCriarConta)
        val txtRedefinirSenha = findViewById<TextView>(R.id.txtRedefinirSenha)

        btnEntrar.setOnClickListener {
            val email = editEmail.text.toString().trim()
            val senha = editSenha.text.toString().trim()

            if (email.isEmpty() || senha.isEmpty()) {
                Toast.makeText(this, "Preencha e-mail e senha!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.signInWithEmailAndPassword(email, senha)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        abrirApp()
                    } else {
                        Toast.makeText(this, "Erro ao entrar: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }

        txtCriarConta.setOnClickListener {
            startActivity(Intent(this, CadastroActivity::class.java))
        }

        txtRedefinirSenha.setOnClickListener {
            val email = editEmail.text.toString().trim()
            if (email.isEmpty()) {
                Toast.makeText(this, "Digite seu e-mail no campo acima para redefinir a senha!", Toast.LENGTH_LONG).show()
            } else {
                auth.sendPasswordResetEmail(email)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Toast.makeText(this, "E-mail de redefinição enviado para $email", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "Erro ao enviar e-mail: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        }
    }

    private fun abrirApp() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}