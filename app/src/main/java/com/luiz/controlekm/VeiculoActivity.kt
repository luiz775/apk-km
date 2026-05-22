package com.luiz.controlekm

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class VeiculoActivity : AppCompatActivity() {

    private val auth by lazy { Firebase.auth }
    private val db by lazy { Firebase.firestore }

    private lateinit var editCarro: EditText
    private lateinit var editPlaca: EditText
    private lateinit var editKmIni: EditText
    private lateinit var editOleo: EditText
    private lateinit var editOutros: EditText
    private lateinit var editCombustivel: EditText
    private lateinit var editManutencao: EditText
    private lateinit var btnSalvar: Button
    private lateinit var btnVoltar: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        val themePrefs = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        val savedTheme = themePrefs.getInt("tema_preferido", AppCompatDelegate.MODE_NIGHT_YES)
        AppCompatDelegate.setDefaultNightMode(savedTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_veiculo)

        editCarro = findViewById(R.id.editVeiculoCarro)
        editPlaca = findViewById(R.id.editVeiculoPlaca)
        editKmIni = findViewById(R.id.editVeiculoKmIni)
        editOleo = findViewById(R.id.editVeiculoOleo)
        editOutros = findViewById(R.id.editVeiculoOutros)
        editCombustivel = findViewById(R.id.editVeiculoGastoCombustivel)
        editManutencao = findViewById(R.id.editVeiculoGastoManutencao)
        btnSalvar = findViewById(R.id.btnSalvarVeiculo)
        btnVoltar = findViewById(R.id.btnVoltarVeiculo)

        btnVoltar.setOnClickListener { finish() }

        // Carregar dados salvos
        val prefs = getSharedPreferences("DadosVeiculo", Context.MODE_PRIVATE)
        editCarro.setText(prefs.getString("veiculo_carro", ""))
        editPlaca.setText(prefs.getString("veiculo_placa", ""))
        editKmIni.setText(prefs.getString("veiculo_km_ini", ""))
        editOleo.setText(prefs.getString("veiculo_oleo", ""))
        editOutros.setText(prefs.getString("veiculo_outros", ""))
        editCombustivel.setText(prefs.getString("veiculo_gasto_combustivel", ""))
        editManutencao.setText(prefs.getString("veiculo_gasto_manutencao", ""))

        btnSalvar.setOnClickListener {
            val carro = editCarro.text.toString().trim()
            val placa = editPlaca.text.toString().trim()
            val kmIni = editKmIni.text.toString().trim()
            val oleo = editOleo.text.toString().trim()
            val outros = editOutros.text.toString().trim()
            val combustivel = editCombustivel.text.toString().trim()
            val manutencao = editManutencao.text.toString().trim()

            if (carro.isEmpty()) {
                Toast.makeText(this, "Informe pelo menos o modelo do carro!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val editor = prefs.edit()
            editor.putString("veiculo_carro", carro)
            editor.putString("veiculo_placa", placa)
            editor.putString("veiculo_km_ini", kmIni)
            editor.putString("veiculo_oleo", oleo)
            editor.putString("veiculo_outros", outros)
            editor.putString("veiculo_gasto_combustivel", combustivel)
            editor.putString("veiculo_gasto_manutencao", manutencao)
            editor.apply()

            // Salvar na Nuvem (Firestore) para persistência entre logins
            auth.currentUser?.let { user ->
                val dadosVeiculo = hashMapOf(
                    "carro" to carro,
                    "placa" to placa,
                    "kmIni" to kmIni,
                    "oleo" to oleo,
                    "outros" to outros,
                    "combustivel" to combustivel,
                    "manutencao" to manutencao
                )
                db.collection("veiculos").document(user.uid).set(dadosVeiculo)
            }

            Toast.makeText(this, "Informações do veículo salvas na nuvem!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
