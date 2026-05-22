package com.luiz.controlekm

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

class ConsumoActivity : AppCompatActivity() {

    private lateinit var containerConsumo: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        val themePrefs = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        val savedTheme = themePrefs.getInt("tema_preferido", AppCompatDelegate.MODE_NIGHT_YES)
        AppCompatDelegate.setDefaultNightMode(savedTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consumo)

        containerConsumo = findViewById(R.id.containerConsumo)
        findViewById<ImageButton>(R.id.btnVoltarConsumo).setOnClickListener { finish() }

        calcularEExibirHistorico()
    }

    private fun calcularEExibirHistorico() {
        val prefs = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        val currentUser = Firebase.auth.currentUser
        
        val historicoKey = if (currentUser != null) "historico_local_${currentUser.uid}" else "historico_geral_local"
        val historicoJson = prefs.getString(historicoKey, "[]")

        val fuelKey = if (currentUser != null) "historico_combustivel_local_${currentUser.uid}" else "historico_combustivel_geral"
        val fuelJson = prefs.getString(fuelKey, "[]")

        val viagensPorSemana = mutableMapOf<String, MutableList<Viagem>>()
        val fuelPorSemana = mutableMapOf<String, Double>()
        
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val cal = Calendar.getInstance()

        // 1. Processa Viagens
        try {
            val array = JSONArray(historicoJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val dataStr = obj.getString("data")
                val data = sdf.parse(dataStr)
                if (data != null) {
                    cal.time = data
                    val semanaAno = "${cal.get(Calendar.WEEK_OF_YEAR)}/${cal.get(Calendar.YEAR)}"
                    
                    val v = Viagem(
                        obj.getString("data"), obj.getString("condutor"),
                        if (obj.has("origem")) obj.getString("origem") else "",
                        obj.getString("destino"),
                        obj.getString("hSaida"), obj.getString("hChegada"),
                        obj.getInt("kmIni"), obj.getInt("kmFin"), obj.getDouble("custo"),
                        if (obj.has("observacoes")) obj.getString("observacoes") else "",
                        if (obj.has("isEmpresa")) obj.getBoolean("isEmpresa") else false
                    )
                    
                    if (!viagensPorSemana.containsKey(semanaAno)) {
                        viagensPorSemana[semanaAno] = mutableListOf()
                    }
                    viagensPorSemana[semanaAno]?.add(v)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        // 2. Processa Combustível
        try {
            val arrayFuel = JSONArray(fuelJson)
            for (i in 0 until arrayFuel.length()) {
                val obj = arrayFuel.getJSONObject(i)
                val dataStr = obj.getString("data")
                val data = sdf.parse(dataStr)
                if (data != null) {
                    cal.time = data
                    val semanaAno = "${cal.get(Calendar.WEEK_OF_YEAR)}/${cal.get(Calendar.YEAR)}"
                    val valor = obj.getDouble("valor")
                    fuelPorSemana[semanaAno] = (fuelPorSemana[semanaAno] ?: 0.0) + valor
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        // Pega todas as semanas que têm dados (viagem ou combustível)
        val todasSemanas = (viagensPorSemana.keys + fuelPorSemana.keys).toSet()
        val semanasOrdenadas = todasSemanas.sortedWith(compareByDescending<String> { it.split("/")[1] }.thenByDescending { it.split("/")[0].toInt() })

        containerConsumo.removeAllViews()
        
        if (semanasOrdenadas.isEmpty()) {
            val emptyTxt = TextView(this)
            emptyTxt.text = "Nenhum dado encontrado."
            emptyTxt.setTextColor(Color.GRAY)
            emptyTxt.gravity = Gravity.CENTER
            emptyTxt.setPadding(0, 50, 0, 0)
            containerConsumo.addView(emptyTxt)
            return
        }

        semanasOrdenadas.forEach { semana ->
            val listaCompleta = viagensPorSemana[semana] ?: emptyList()
            // Filtra para contar apenas viagens PARTICULARES no consumo/dashboard semanal
            val listaViagens = listaCompleta.filter { !it.isEmpresa }
            var totalKmSemana = 0
            
            if (listaViagens.isNotEmpty()) {
                val viagensPorDia = listaViagens.groupBy { it.data }
                viagensPorDia.forEach { (_, diaLista) ->
                    val kmi = diaLista.minOf { it.kmIni }
                    val kmf = diaLista.maxOf { it.kmFin }
                    totalKmSemana += (kmf - kmi)
                }
            }
            
            val custoTotalViagens = totalKmSemana * 1.20
            val totalFuelSemana = fuelPorSemana[semana] ?: 0.0

            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(32, 32, 32, 32)
            card.background = ContextCompat.getDrawable(this, R.drawable.bg_card_modern)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 32)
            card.layoutParams = params

            val txtSemana = TextView(this)
            txtSemana.text = "SEMANA $semana"
            txtSemana.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            txtSemana.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
            txtSemana.setTypeface(null, Typeface.BOLD)
            card.addView(txtSemana)

            val txtDados = TextView(this)
            txtDados.text = String.format(Locale.forLanguageTag("pt-BR"), 
                "KM Rodados: %d KM\nReembolso KM: R$ %.2f\nGasto Gasolina: R$ %.2f", 
                totalKmSemana, custoTotalViagens, totalFuelSemana)
            txtDados.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            txtDados.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            txtDados.setPadding(0, 12, 0, 0)
            txtDados.setLineSpacing(8f, 1f)
            card.addView(txtDados)

            containerConsumo.addView(card)
        }
    }
}
