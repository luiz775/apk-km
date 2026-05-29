package com.luiz.controlekm

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class CadastroActivity : AppCompatActivity() {

    private val auth by lazy { Firebase.auth }
    private val db by lazy { Firebase.firestore }

    override fun onCreate(savedInstanceState: Bundle?) {
        val themePrefs = getSharedPreferences("DadosApp", MODE_PRIVATE)
        val savedTheme = themePrefs.getInt("tema_preferido", AppCompatDelegate.MODE_NIGHT_YES)
        AppCompatDelegate.setDefaultNightMode(savedTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cadastro)

        val editNome = findViewById<EditText>(R.id.editNomeCadastro)
        val editEmail = findViewById<EditText>(R.id.editEmailCadastro)
        val editSenha = findViewById<EditText>(R.id.editSenhaCadastro)
        val btnCadastrar = findViewById<Button>(R.id.btnFinalizarCadastro)
        val txtVoltar = findViewById<TextView>(R.id.txtVoltarLogin)

        btnCadastrar.setOnClickListener {
            val nome = editNome.text.toString().trim()
            val email = editEmail.text.toString().trim()
            val senha = editSenha.text.toString().trim()

            if (nome.isEmpty() || email.isEmpty() || senha.isEmpty()) {
                Toast.makeText(this, "Preencha todos os campos!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (senha.length < 6) {
                Toast.makeText(this, "A senha deve ter pelo menos 6 caracteres!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.createUserWithEmailAndPassword(email, senha)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        val userId = auth.currentUser?.uid
                        val userProfile = hashMapOf(
                            "uid" to userId,
                            "nome" to nome,
                            "email" to email,
                            "data_cadastro" to FieldValue.serverTimestamp(),
                        )

                        userId?.let {
                            db.collection("tecnicos").document(it)
                                .set(userProfile)
                                .addOnSuccessListener {
                                    Toast.makeText(this, "Cadastro realizado com sucesso!", Toast.LENGTH_SHORT).show()
                                    finish() // Fecha a tela de cadastro e volta para a anterior
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(this, "Erro ao salvar perfil: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                        }
                    } else {
                        Toast.makeText(this, "Erro no cadastro: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }

        txtVoltar.setOnClickListener {
            finish()
        }
    }
}