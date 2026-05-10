package com.luiz.controlekm

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class HistoricoActivity : AppCompatActivity() {

    private val auth by lazy { Firebase.auth }
    private val db by lazy { Firebase.firestore }

    private val fullListaViagens = mutableListOf<Viagem>()
    private lateinit var containerHistorico: LinearLayout
    private lateinit var progressHistorico: ProgressBar
    private lateinit var txtFiltroAtivo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_historico)

        containerHistorico = findViewById(R.id.containerHistorico)
        progressHistorico = findViewById(R.id.progressHistorico)
        txtFiltroAtivo = findViewById(R.id.txtFiltroAtivo)
        val btnVoltar = findViewById<Button>(R.id.btnVoltarHistorico)
        val btnFiltro = findViewById<ImageButton>(R.id.btnFiltro)
        val btnRefresh = findViewById<ImageButton>(R.id.btnRefresh)

        btnVoltar.setOnClickListener { finish() }
        
        btnFiltro.setOnClickListener { mostrarMenuFiltro(it) }

        btnRefresh.setOnClickListener {
            txtFiltroAtivo.visibility = View.GONE
            carregarDadosDaNuvem()
        }

        // 1. CARREGAR HISTÓRICO LOCAL IMEDIATAMENTE
        carregarDadosLocais()

        // 2. CARREGAR DADOS DA NUVEM
        carregarDadosDaNuvem()
    }

    private fun carregarDadosDaNuvem() {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            progressHistorico.visibility = View.VISIBLE
            
            db.collection("viagens")
                .whereEqualTo("tecnicoId", currentUser.uid)
                .get()
                .addOnSuccessListener { documents ->
                    progressHistorico.visibility = View.GONE
                    if (!documents.isEmpty) {
                        fullListaViagens.clear()
                        for (doc in documents) {
                            fullListaViagens.add(Viagem(
                                doc.getString("data") ?: "",
                                doc.getString("condutor") ?: "",
                                doc.getString("destino") ?: "",
                                doc.getString("hSaida") ?: "",
                                doc.getString("hChegada") ?: "",
                                doc.get("kmIni").toString().toIntOrNull() ?: 0,
                                doc.get("kmFin").toString().toIntOrNull() ?: 0,
                                doc.get("custo").toString().toDoubleOrNull() ?: 0.0,
                                doc.getString("observacoes") ?: ""
                            ))
                        }
                        ordenarEPresentar(fullListaViagens)
                        Toast.makeText(this, "Histórico atualizado!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Nenhuma viagem nova na nuvem.", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    progressHistorico.visibility = View.GONE
                    Toast.makeText(this, "Erro ao atualizar: Verifique sua conexão.", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun carregarDadosLocais() {
        val prefs = getSharedPreferences("DadosApp", MODE_PRIVATE)
        val currentUser = auth.currentUser
        val historicoKey = if (currentUser != null) "historico_local_${currentUser.uid}" else "historico_geral_local"
        
        val historicoLocal = prefs.getString(historicoKey, "[]")
        
        try {
            val array = JSONArray(historicoLocal)
            fullListaViagens.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                fullListaViagens.add(Viagem(
                    obj.getString("data"), obj.getString("condutor"), obj.getString("destino"),
                    obj.getString("hSaida"), obj.getString("hChegada"),
                    obj.getInt("kmIni"), obj.getInt("kmFin"), obj.getDouble("custo"),
                    if (obj.has("observacoes")) obj.getString("observacoes") else ""
                ))
            }
            ordenarEPresentar(fullListaViagens)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun ordenarEPresentar(lista: List<Viagem>) {
        val listaOrdenada = lista.sortedByDescending { 
            try { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(it.data) } catch (e: Exception) { Date(0) }
        }
        exibirViagens(listaOrdenada)
    }

    private fun exibirViagens(lista: List<Viagem>) {
        containerHistorico.removeAllViews()
        if (lista.isEmpty()) {
            val emptyTxt = TextView(this)
            emptyTxt.text = "Nenhuma viagem encontrada."
            emptyTxt.setTextColor(Color.GRAY)
            emptyTxt.gravity = android.view.Gravity.CENTER
            containerHistorico.addView(emptyTxt)
            return
        }

        for (viagem in lista) {
            val view = layoutInflater.inflate(android.R.layout.simple_list_item_2, containerHistorico, false)
            val text1 = view.findViewById<TextView>(android.R.id.text1)
            val text2 = view.findViewById<TextView>(android.R.id.text2)

            val kmTotal = viagem.kmFin - viagem.kmIni

            text1.text = "${viagem.destino} (${viagem.data})"
            text1.setTextColor(Color.WHITE)
            text2.text = "KM: $kmTotal (I: ${viagem.kmIni} F: ${viagem.kmFin}) | R$ ${String.format("%.2f", viagem.custo)}"
            text2.setTextColor(Color.LTGRAY)

            view.setPadding(0, 20, 0, 20)
            view.setOnClickListener {
                val detalhes = """
                    📅 Data: ${viagem.data}
                    👤 Condutor: ${viagem.condutor}
                    📍 Destino: ${viagem.destino}
                    🕒 Saída: ${viagem.hSaida} | Chegada: ${viagem.hChegada}
                    🛣️ KM Inicial: ${viagem.kmIni}
                    🏁 KM Final: ${viagem.kmFin}
                    📊 Total: $kmTotal KM
                    💰 Custo: R$ ${String.format("%.2f", viagem.custo)}
                    📝 Obs: ${viagem.observacoes}
                """.trimIndent()

                AlertDialog.Builder(this)
                    .setTitle("Detalhes da Viagem")
                    .setMessage(detalhes)
                    .setPositiveButton("Fechar", null)
                    .show()
            }
            containerHistorico.addView(view)
        }
    }

    private fun mostrarMenuFiltro(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add("Todas as Viagens")
        popup.menu.add("Viagens de Hoje")
        popup.menu.add("Maior Custo (R$)")
        popup.menu.add("Maior Quilometragem")
        popup.menu.add("Limpar Filtros")

        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Todas as Viagens" -> {
                    txtFiltroAtivo.visibility = View.GONE
                    ordenarEPresentar(fullListaViagens)
                }
                "Viagens de Hoje" -> {
                    val hoje = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                    val filtrada = fullListaViagens.filter { it.data == hoje }
                    txtFiltroAtivo.text = "Filtro: Viagens de Hoje ($hoje)"
                    txtFiltroAtivo.visibility = View.VISIBLE
                    exibirViagens(filtrada)
                }
                "Maior Custo (R$)" -> {
                    val ordenada = fullListaViagens.sortedByDescending { it.custo }
                    txtFiltroAtivo.text = "Filtro: Maior Custo"
                    txtFiltroAtivo.visibility = View.VISIBLE
                    exibirViagens(ordenada)
                }
                "Maior Quilometragem" -> {
                    val ordenada = fullListaViagens.sortedByDescending { it.kmFin - it.kmIni }
                    txtFiltroAtivo.text = "Filtro: Maior Quilometragem"
                    txtFiltroAtivo.visibility = View.VISIBLE
                    exibirViagens(ordenada)
                }
                "Limpar Filtros" -> {
                    txtFiltroAtivo.visibility = View.GONE
                    ordenarEPresentar(fullListaViagens)
                }
            }
            true
        }
        popup.show()
    }
}