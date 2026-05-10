package com.luiz.controlekm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

// 1. O "Molde" da Viagem (Data Class)
data class Viagem(
    val data: String,
    val condutor: String,
    val destino: String,
    val hSaida: String,
    val hChegada: String,
    val kmIni: Int,
    val kmFin: Int,
    val custo: Double,
    val observacoes: String = ""
)

class MainActivity : AppCompatActivity() {

    private val auth by lazy { Firebase.auth }
    private val db by lazy { Firebase.firestore }

    private val listaDeViagens = mutableListOf<Viagem>()
    private lateinit var adapterDestino: ArrayAdapter<String>
    private val historicoDestinos = mutableListOf<String>()
    private var ultimoArquivoGerado: File? = null
    
    private var fotoIdaPath: String? = null
    private var fotoVoltaPath: String? = null
    private var fotoIdaHora: String? = null
    private var fotoVoltaHora: String? = null
    private var pedindoFotoIda = true
    private var pedindoFotoDespesa = false
    private val listaFotosDespesas = mutableListOf<String>()

    // UI Elements
    private lateinit var editData: EditText
    private lateinit var editCondutor: EditText
    private lateinit var editDestino: AutoCompleteTextView
    private lateinit var editHoraSaida: EditText
    private lateinit var editHoraChegada: EditText
    private lateinit var editKmInicial: EditText
    private lateinit var editKmFinal: EditText
    private lateinit var editObservacoes: EditText
    private lateinit var btnAdicionar: Button
    private lateinit var btnGerarPdf: Button
    private lateinit var btnCompartilhar: Button
    private lateinit var btnFotoIda: Button
    private lateinit var btnFotoVolta: Button
    private lateinit var btnFotoDespesa: Button
    private lateinit var btnReset: Button
    private lateinit var btnConfiguracoes: ImageButton
    private lateinit var radioGroupVeiculo: RadioGroup
    private lateinit var containerViagens: LinearLayout
    private lateinit var txtOlaUsuario: TextView

    private val cropImage = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val uri = result.uriContent
            val path = saveImageFromUri(uri!!, if (pedindoFotoDespesa) "DESPESA" else (if (pedindoFotoIda) "IDA" else "VOLTA"))
            if (path != null) {
                val horaAtual = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                
                if (pedindoFotoDespesa) {
                    listaFotosDespesas.add(path)
                    btnFotoDespesa.apply {
                        text = "✅ RECIBO ADICIONADO (${listaFotosDespesas.size})"
                        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#9C27B0"))
                    }
                } else if (pedindoFotoIda) {
                    fotoIdaPath = path
                    fotoIdaHora = horaAtual
                    btnFotoIda.apply {
                        text = "✅ FOTO IDA"
                        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.GREEN)
                    }
                    btnFotoVolta.isEnabled = true
                } else {
                    fotoVoltaPath = path
                    fotoVoltaHora = horaAtual
                    btnFotoVolta.apply {
                        text = "✅ FOTO VOLTA"
                        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.GREEN)
                    }
                }
                salvarEstado()
                validarBotoes()
            }
        } else {
            val exception = result.error
            exception?.printStackTrace()
        }
    }

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) iniciarCapturaComRecorte(pedindoFotoIda, true)
        else Toast.makeText(this, "Permissão de Câmera necessária para as fotos de KM!", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        verificarBloqueio()
        
        // --- SISTEMA DE SEGURANÇA (ATIVAÇÃO) ---
        val ativacaoPrefs = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        val isAtivado = ativacaoPrefs.getBoolean("is_ativado", false)
        
        if (!isAtivado) {
            verificarAtivacao()
            // Não damos return aqui para que o layout carregue, 
            // mas o AlertDialog de ativação bloqueará a interação.
        }

        editData = findViewById(R.id.editData)
        editCondutor = findViewById(R.id.editCondutor)
        editDestino = findViewById(R.id.editDestino)
        editHoraSaida = findViewById(R.id.editHoraSaida)
        editHoraChegada = findViewById(R.id.editHoraChegada)
        editKmInicial = findViewById(R.id.editKmInicial)
        editKmFinal = findViewById(R.id.editKmFinal)
        editObservacoes = findViewById(R.id.editObservacoes)
        btnAdicionar = findViewById(R.id.btnAdicionar)
        btnGerarPdf = findViewById(R.id.btnGerarPdf)
        btnCompartilhar = findViewById(R.id.btnCompartilhar)
        btnFotoIda = findViewById(R.id.btnFotoIda)
        btnFotoVolta = findViewById(R.id.btnFotoVolta)
        btnFotoDespesa = findViewById(R.id.btnFotoDespesa)
        btnReset = findViewById(R.id.btnReset)
        btnConfiguracoes = findViewById(R.id.btnConfiguracoes)
        radioGroupVeiculo = findViewById(R.id.radioGroupVeiculo)
        containerViagens = findViewById(R.id.containerViagens)
        txtOlaUsuario = findViewById(R.id.txtOlaUsuario)

        // --- PERSISTÊNCIA E HISTÓRICO ---
        val prefs = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)

        val salvos = prefs.getStringSet("historico_destinos", setOf("Dourados > Jardim", "Jardim > Dourados"))
        historicoDestinos.addAll(salvos!!)
        adapterDestino = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, historicoDestinos)
        editDestino.setAdapter(adapterDestino)
        
        // Mostrar atalhos ao clicar no campo
        editDestino.setOnClickListener {
            if (editDestino.text.isEmpty()) {
                editDestino.showDropDown()
            }
        }
        
        editDestino.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && editDestino.text.isEmpty()) {
                editDestino.showDropDown()
            }
        }

        // Recupera rascunho e estado salvo
        val dataAtual = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        editData.setText(prefs.getString("rascunho_data", dataAtual))
        
        editDestino.setText(prefs.getString("rascunho_destino", ""))
        
        val horaAtual = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        editHoraSaida.setText(prefs.getString("rascunho_hSaida", horaAtual))
        
        editKmInicial.setText(prefs.getString("rascunho_kmIni", ""))
        editObservacoes.setText(prefs.getString("rascunho_obs", ""))

        // --- BUSCA NOME DO TÉCNICO NO FIRESTORE ---
        val currentUser = auth.currentUser
        if (currentUser != null) {
            // Tenta buscar na coleção 'tecnicos'
            db.collection("tecnicos").document(currentUser.uid)
                .get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        val nomeTecnico = document.getString("nome") ?: "Usuário"
                        editCondutor.setText(nomeTecnico)
                        txtOlaUsuario.text = "Olá, $nomeTecnico"
                        prefs.edit().putString("rascunho_condutor", nomeTecnico).apply()
                    } else {
                        // Se não achar em 'tecnicos', tenta em 'usuarios' (legado)
                        db.collection("usuarios").document(currentUser.uid).get()
                            .addOnSuccessListener { doc ->
                                if (doc != null && doc.exists()) {
                                    val nomeLegacy = doc.getString("nome") ?: "Usuário"
                                    editCondutor.setText(nomeLegacy)
                                    txtOlaUsuario.text = "Olá, $nomeLegacy"
                                    prefs.edit().putString("rascunho_condutor", nomeLegacy).apply()
                                } else {
                                    txtOlaUsuario.text = "Olá (Nome não encontrado)"
                                }
                            }
                    }
                }
                .addOnFailureListener {
                    txtOlaUsuario.text = "Olá (Erro de conexão)"
                }
        } else {
            editCondutor.setText(prefs.getString("rascunho_condutor", "Luiz"))
            txtOlaUsuario.text = "Olá, Luiz (Modo Visitante)"
        }
        // editCondutor.isEnabled = false // Removido para permitir alteração em caso de erro


        val isEmpresa = prefs.getBoolean("veiculo_empresa", false)
        if (isEmpresa) {
            findViewById<RadioButton>(R.id.radioEmpresa).isChecked = true
        } else {
            findViewById<RadioButton>(R.id.radioParticular).isChecked = true
        }

        fotoIdaPath = prefs.getString("fotoIdaPath", null)
        fotoVoltaPath = prefs.getString("fotoVoltaPath", null)
        fotoIdaHora = prefs.getString("fotoIdaHora", null)
        fotoVoltaHora = prefs.getString("fotoVoltaHora", null)
        
        val despesasSalvas = prefs.getString("listaFotosDespesas", "")
        if (!despesasSalvas.isNullOrEmpty()) {
            listaFotosDespesas.addAll(despesasSalvas.split("|").filter { it.isNotEmpty() })
            if (listaFotosDespesas.isNotEmpty()) {
                btnFotoDespesa.text = "✅ RECIBO ADICIONADO (${listaFotosDespesas.size})"
            }
        }
        
        if (fotoIdaPath != null) {
            btnFotoIda.apply {
                text = "✅ FOTO IDA"
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.GREEN)
            }
            btnFotoVolta.isEnabled = true
        }
        if (fotoVoltaPath != null) {
            btnFotoVolta.apply {
                text = "✅ FOTO VOLTA"
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.GREEN)
            }
        }

        val jsonViagens = prefs.getString("lista_viagens", null)
        if (!jsonViagens.isNullOrEmpty()) {
            try {
                val array = JSONArray(jsonViagens)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    listaDeViagens.add(Viagem(
                        obj.getString("data"), obj.getString("condutor"), obj.getString("destino"),
                        obj.getString("hSaida"), obj.getString("hChegada"),
                        obj.getInt("kmIni"), obj.getInt("kmFin"), obj.getDouble("custo")
                    ))
                }
            } catch (e: Exception) { e.printStackTrace() }
            atualizarListaVisual()
        }

        editHoraSaida.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && editHoraSaida.text.isEmpty()) {
                editHoraSaida.setText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
                salvarEstado()
            }
        }

        editHoraChegada.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && editHoraChegada.text.isEmpty()) {
                editHoraChegada.setText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
                salvarEstado()
            }
        }

        editData.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && editData.text.isEmpty()) {
                editData.setText(SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date()))
                salvarEstado()
            }
        }

        // Preenche ao clicar caso o campo esteja habilitado e vazio
        editData.setOnClickListener {
            if (editData.text.isEmpty()) {
                editData.setText(SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date()))
                salvarEstado()
            }
        }

        editHoraSaida.setOnClickListener {
            if (editHoraSaida.text.isEmpty()) {
                editHoraSaida.setText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
                salvarEstado()
            }
        }

        editHoraChegada.setOnClickListener {
            if (editHoraChegada.text.isEmpty()) {
                editHoraChegada.setText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
                salvarEstado()
            }
        }

        editData.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false
            private var isDeleting = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { isDeleting = after < count }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating || isDeleting) return
                val str = s.toString().replace("/", "")
                if (str.length >= 2) {
                    isUpdating = true
                    val formatted = when {
                        str.length >= 4 -> "${str.substring(0, 2)}/${str.substring(2, 4)}/${str.substring(4).take(4)}"
                        else -> "${str.substring(0, 2)}/${str.substring(2)}"
                    }
                    s?.replace(0, s.length, formatted)
                    isUpdating = false
                }
                salvarEstado()
            }
        })

        val watcherBotoes = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { 
                validarBotoes() 
                salvarEstado()
            }
        }

        editKmInicial.addTextChangedListener(watcherBotoes)
        editKmFinal.addTextChangedListener(watcherBotoes)
        editCondutor.addTextChangedListener(watcherBotoes)
        editDestino.addTextChangedListener(watcherBotoes)

        btnFotoIda.setOnClickListener { 
            pedindoFotoDespesa = false
            mostrarDialogoSelecaoImagem(true) 
        }
        btnFotoVolta.setOnClickListener { 
            pedindoFotoDespesa = false
            mostrarDialogoSelecaoImagem(false) 
        }
        btnFotoDespesa.setOnClickListener {
            pedindoFotoDespesa = true
            mostrarDialogoSelecaoImagem(false) // O boolean isIda não importa aqui pois pedindoFotoDespesa manda
        }

        radioGroupVeiculo.setOnCheckedChangeListener { _, _ -> salvarEstado() }

        btnAdicionar.setOnClickListener {
            val destinoTxt = editDestino.text.toString()
            val condutorTxt = editCondutor.text.toString()
            val kmI = editKmInicial.text.toString().toIntOrNull() ?: 0
            val kmF = editKmFinal.text.toString().toIntOrNull() ?: 0

            if (kmI > kmF) {
                Toast.makeText(this, "Erro: KM inicial não pode ser maior que o final!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val kmTotal = kmF - kmI

            // Salva no histórico de destinos (atalhos) - Case Insensitive
            val destinoExistente = historicoDestinos.any { it.equals(destinoTxt, ignoreCase = true) }
            if (destinoTxt.isNotEmpty() && !destinoExistente) {
                historicoDestinos.add(destinoTxt)
                // Ordena para ficar organizado
                historicoDestinos.sort()
                adapterDestino.notifyDataSetChanged()
            }

            val v = Viagem(
                editData.text.toString(), editCondutor.text.toString(), destinoTxt,
                editHoraSaida.text.toString(), editHoraChegada.text.toString(),
                kmI, kmF, kmTotal * 1.20, editObservacoes.text.toString()
            )

            listaDeViagens.add(v)
            
            // Salva no histórico permanente local também (vinculado ao UID do usuário)
            val currentUser = auth.currentUser
            val historicoKey = if (currentUser != null) "historico_local_${currentUser.uid}" else "historico_geral_local"
            
            val historicoGeral = prefs.getString(historicoKey, "[]")
            val arrayHistorico = JSONArray(historicoGeral)
            val obj = JSONObject().apply {
                put("data", v.data); put("condutor", v.condutor); put("destino", v.destino)
                put("hSaida", v.hSaida); put("hChegada", v.hChegada)
                put("kmIni", v.kmIni); put("kmFin", v.kmFin); put("custo", v.custo)
                put("observacoes", v.observacoes)
            }
            arrayHistorico.put(obj)
            prefs.edit().putString(historicoKey, arrayHistorico.toString()).apply()

            Toast.makeText(this, "Viagem adicionada!", Toast.LENGTH_SHORT).show()

            editDestino.text.clear()
            editObservacoes.text.clear()
            editHoraSaida.text.clear()
            editHoraChegada.text.clear()
            editKmInicial.text.clear()
            editKmFinal.text.clear()

            atualizarListaVisual()
            salvarEstado()
            validarBotoes()
        }

        btnGerarPdf.setOnClickListener {
            if (listaDeViagens.isEmpty()) Toast.makeText(this, "Lista vazia!", Toast.LENGTH_SHORT).show()
            else if (fotoVoltaPath == null) Toast.makeText(this, "Tire a foto da volta primeiro!", Toast.LENGTH_SHORT).show()
            else {
                sincronizarViagensComFirestore()
                gerarRelatorioCompleto(listaDeViagens)
            }
        }

        btnCompartilhar.setOnClickListener {
            ultimoArquivoGerado?.let { arquivo ->
                compartilharArquivo(arquivo)
            }
        }

        btnConfiguracoes.setOnClickListener {
            mostrarMenuConfiguracoes(it)
        }

        btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Limpar tudo / Novo Dia?")
                .setMessage("Isso apagará as fotos e todos os registros da lista para iniciar um novo dia.")
                .setPositiveButton("Sim, Limpar") { _, _ ->
                    fotoIdaPath?.let { File(it).delete() }
                    fotoVoltaPath?.let { File(it).delete() }
                    
                    fotoIdaPath = null
                    fotoVoltaPath = null
                    fotoIdaHora = null
                    fotoVoltaHora = null
                    listaFotosDespesas.clear()
                    listaDeViagens.clear()
                    
                    editDestino.text.clear()
                    editObservacoes.text.clear()
                    editHoraSaida.text.clear()
                    editHoraChegada.text.clear()
                    editKmInicial.text.clear()
                    editKmFinal.text.clear()
                    
                    findViewById<RadioButton>(R.id.radioParticular).isChecked = true
                    
                    btnFotoIda.apply {
                        text = "📸 FOTO IDA"
                        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5722"))
                    }
                    btnFotoVolta.apply {
                        text = "📸 FOTO VOLTA"
                        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#607D8B"))
                        isEnabled = false
                    }
                    btnFotoDespesa.apply {
                        text = "📸 ADICIONAR DESPESAS (RECIBOS)"
                        isEnabled = false
                    }
                    
                    salvarEstado()
                    atualizarListaVisual()
                    validarBotoes()
                    Toast.makeText(this, "Tudo limpo!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        
        validarBotoes() // Chama no início
    }

    private fun verificarBloqueio() {
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = 14400 // 24 horas em segundos
        }
        remoteConfig.setConfigSettingsAsync(configSettings)
        remoteConfig.setDefaultsAsync(mapOf("app_status_ativo" to true))

        // 1. Verificação Imediata (Cache): Se já foi bloqueado antes, trava na hora, mesmo offline.
        if (!remoteConfig.getBoolean("app_status_ativo")) {
            exibirTelaDeBloqueio()
            return
        }

        // 2. Verificação Online (Segundo Plano): Tenta atualizar o status se houver internet.
        remoteConfig.fetchAndActivate()
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val statusAtivo = remoteConfig.getBoolean("app_status_ativo")
                    if (!statusAtivo) {
                        exibirTelaDeBloqueio()
                    }
                }
            }
    }

    private fun exibirTelaDeBloqueio() {
        AlertDialog.Builder(this)
            .setTitle("Licença de Uso")
            .setMessage("KM Controller: Sua licença de uso expirou ou foi revogada. Entre em contato com o administrador para renovar o acesso.")
            .setCancelable(false)
            .setPositiveButton("Sair") { _, _ -> finish() }
            .show()
    }

    private fun verificarAtivacao() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "Digite a Chave de Ativação"
        
        // Estilizando o campo de entrada
        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(60, 20, 60, 20)
        input.layoutParams = params
        container.addView(input)
        
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).takeLast(4).uppercase()
        val deviceMsg = "ID do Dispositivo: $androidId"

        AlertDialog.Builder(this)
            .setTitle("Ativação do Aplicativo")
            .setMessage("Este aplicativo é protegido. Entre em contato com Luiz Gustavo para obter sua chave.\n\n$deviceMsg")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Ativar") { _, _ ->
                val chaveDigitada = input.text.toString().trim().uppercase()
                // A chave mestre para você usar é: KM-LUIZ-[ULTIMOS 4 DO ID]
                val chaveCorreta = "KM-LUIZ-$androidId"
                
                if (chaveDigitada == chaveCorreta || chaveDigitada == "LUIZ2026") {
                    getSharedPreferences("DadosApp", Context.MODE_PRIVATE).edit().putBoolean("is_ativado", true).apply()
                    Toast.makeText(this, "Aplicativo Ativado com Sucesso!", Toast.LENGTH_SHORT).show()
                    recreate() // Recarrega a tela para abrir o app
                } else {
                    Toast.makeText(this, "Chave Incorreta! O app será fechado.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            .setNeutralButton("Criar Conta") { _, _ ->
                startActivity(Intent(this, CadastroActivity::class.java))
            }
            .setNegativeButton("Sair") { _, _ -> finish() }
            .show()
    }

    private fun atualizarListaVisual() {
        containerViagens.removeAllViews()
        
        listaDeViagens.forEachIndexed { index, viagem ->
            val view = layoutInflater.inflate(android.R.layout.simple_list_item_2, containerViagens, false)
            val text1 = view.findViewById<TextView>(android.R.id.text1)
            val text2 = view.findViewById<TextView>(android.R.id.text2)
            
            text1.text = "${viagem.destino} (${viagem.data})"
            text1.setTextColor(Color.WHITE)
            
            val kmTotal = viagem.kmFin - viagem.kmIni
            text2.text = "Condutor: ${viagem.condutor} | KM: $kmTotal (I: ${viagem.kmIni} F: ${viagem.kmFin}) | R$ ${String.format("%.2f", viagem.custo)}"
            text2.setTextColor(Color.LTGRAY)
            
            view.setPadding(0, 20, 0, 20)
            
            // Clique longo para excluir
            view.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Excluir Viagem?")
                    .setMessage("Deseja remover esta viagem da lista?")
                    .setPositiveButton("Excluir") { _, _ ->
                        listaDeViagens.removeAt(index)
                        atualizarListaVisual()
                        salvarEstado()
                        validarBotoes()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                true
            }
            
            containerViagens.addView(view)
        }
    }

    private fun salvarEstado() {
        val prefs = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("rascunho_data", editData.text.toString())
            putString("rascunho_condutor", editCondutor.text.toString())
            putString("rascunho_destino", editDestino.text.toString())
            putString("rascunho_hSaida", editHoraSaida.text.toString())
            putString("rascunho_kmIni", editKmInicial.text.toString())
            putString("rascunho_obs", editObservacoes.text.toString())
            putStringSet("historico_destinos", historicoDestinos.toSet())
            putBoolean("veiculo_empresa", findViewById<RadioButton>(R.id.radioEmpresa).isChecked)
            
            putString("fotoIdaPath", fotoIdaPath)
            putString("fotoVoltaPath", fotoVoltaPath)
            putString("fotoIdaHora", fotoIdaHora)
            putString("fotoVoltaHora", fotoVoltaHora)
            putString("listaFotosDespesas", listaFotosDespesas.joinToString("|"))
            
            val array = JSONArray()
            listaDeViagens.forEach { v ->
                val obj = JSONObject().apply {
                    put("data", v.data); put("condutor", v.condutor); put("destino", v.destino)
                    put("hSaida", v.hSaida); put("hChegada", v.hChegada)
                    put("kmIni", v.kmIni); put("kmFin", v.kmFin); put("custo", v.custo)
                    put("observacoes", v.observacoes)
                }
                array.put(obj)
            }
            putString("lista_viagens", array.toString())
            apply()
        }
    }

    private fun validarBotoes() {
        val condutorTxt = editCondutor.text.toString()
        val kmI = editKmInicial.text.toString().toIntOrNull() ?: 0
        val kmF = editKmFinal.text.toString().toIntOrNull() ?: 0

        val erroKmAtual = kmI > kmF && editKmFinal.text.isNotEmpty()
        val ultimaViagem = listaDeViagens.lastOrNull { it.condutor.equals(condutorTxt, ignoreCase = true) }
        val erroKmHistorico = ultimaViagem != null && kmI < ultimaViagem.kmFin && editKmInicial.text.isNotEmpty()

        val temErro = erroKmAtual || erroKmHistorico
        val temFotoIda = fotoIdaPath != null
        val temDestino = editDestino.text.toString().trim().isNotEmpty()

        // PRIORIDADE: Só libera os campos se tiver foto de ida
        val layoutCampos = listOf(editData, editCondutor, editDestino, editHoraSaida, editHoraChegada, editKmInicial, editKmFinal, editObservacoes)
        layoutCampos.forEach { it.isEnabled = temFotoIda }
        
        btnFotoDespesa.isEnabled = temFotoIda
        btnAdicionar.isEnabled = !temErro && temFotoIda && temDestino
        
        val canGerar = listaDeViagens.isNotEmpty() && fotoVoltaPath != null && !temErro
        btnGerarPdf.isEnabled = canGerar
        
        val canCompartilhar = ultimoArquivoGerado != null && !temErro
        btnCompartilhar.isEnabled = canCompartilhar

        // Ajuste de opacidade para feedback visual
        btnAdicionar.alpha = if (btnAdicionar.isEnabled) 1.0f else 0.5f
        btnGerarPdf.alpha = if (btnGerarPdf.isEnabled) 1.0f else 0.5f
        btnCompartilhar.alpha = if (btnCompartilhar.isEnabled) 1.0f else 0.5f
    }

    private fun mostrarMenuConfiguracoes(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add("Criar Usuário")
        popup.menu.add("Histórico de Viagens")
        popup.menu.add("Sincronizar Dados")
        popup.menu.add("Exportar dados para PDF")
        popup.menu.add("Sair da Conta")

        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Criar Usuário" -> {
                    startActivity(Intent(this, CadastroActivity::class.java))
                }
                "Histórico de Viagens" -> {
                    startActivity(Intent(this, HistoricoActivity::class.java))
                }
                "Sincronizar Dados" -> {
                    sincronizarViagensComFirestore()
                }
                "Exportar dados para PDF" -> {
                    val currentUser = auth.currentUser
                    if (currentUser == null) {
                        Toast.makeText(this, "Faça login para exportar o histórico!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Buscando histórico na nuvem...", Toast.LENGTH_SHORT).show()
                        db.collection("viagens")
                            .whereEqualTo("tecnicoId", currentUser.uid)
                            .get()
                            .addOnSuccessListener { documents ->
                                if (documents.isEmpty) {
                                    Toast.makeText(this, "Nenhuma viagem encontrada no histórico.", Toast.LENGTH_SHORT).show()
                                } else {
                                    val viagensHistorico = mutableListOf<Viagem>()
                                    for (doc in documents) {
                                        viagensHistorico.add(Viagem(
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
                                    // Ordena por data (opcional, já que o PDF organiza na ordem da lista)
                                    gerarRelatorioCompleto(viagensHistorico, isExportacaoHistorico = true)
                                }
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(this, "Erro ao buscar histórico: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    }
                }
                "Sair da Conta" -> {
                    auth.signOut()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
            }
            true
        }
        popup.show()
    }

    private fun sincronizarViagensComFirestore() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(this, "Faça login para sincronizar os dados!", Toast.LENGTH_SHORT).show()
            return
        }

        if (listaDeViagens.isEmpty()) {
            Toast.makeText(this, "Nenhuma viagem nova para sincronizar.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Sincronizando...", Toast.LENGTH_SHORT).show()
        var sucessoCount = 0

        listaDeViagens.forEach { v ->
            val dadosViagem = hashMapOf(
                "tecnicoId" to currentUser.uid,
                "data" to v.data,
                "condutor" to v.condutor,
                "destino" to v.destino,
                "hSaida" to v.hSaida,
                "hChegada" to v.hChegada,
                "kmIni" to v.kmIni,
                "kmFin" to v.kmFin,
                "custo" to v.custo,
                "observacoes" to v.observacoes,
                "data_registro" to FieldValue.serverTimestamp()
            )

            db.collection("viagens")
                .add(dadosViagem)
                .addOnSuccessListener {
                    sucessoCount++
                    if (sucessoCount == listaDeViagens.size) {
                        Toast.makeText(this, "Todas as viagens foram sincronizadas!", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun iniciarCapturaComRecorte(isIda: Boolean, daCamera: Boolean) {
        pedindoFotoIda = isIda
        
        val options = CropImageOptions(
            guidelines = CropImageView.Guidelines.ON,
            activityTitle = if (pedindoFotoDespesa) "Recortar Recibo" else "Ajustar Foto de KM",
            fixAspectRatio = !pedindoFotoDespesa,
            aspectRatioX = 3,      // Formato mais "comprido" (3:1)
            aspectRatioY = 1,      // Foca melhor nos números
            initialCropWindowPaddingRatio = 0.2f, // Começa menor na tela
            imageSourceIncludeCamera = daCamera,
            imageSourceIncludeGallery = !daCamera,
            activityMenuIconColor = Color.WHITE,
            allowRotation = true,
            allowFlipping = true
        )
        
        cropImage.launch(CropImageContractOptions(uri = null, cropImageOptions = options))
    }

    private fun mostrarDialogoSelecaoImagem(isIda: Boolean) {
        val options = arrayOf("Tirar Foto", "Escolher da Galeria")
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Selecione a Prova de KM")
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        pedindoFotoIda = isIda
                        requestCameraPermission.launch(Manifest.permission.CAMERA)
                    } else {
                        iniciarCapturaComRecorte(isIda, true)
                    }
                }
                1 -> iniciarCapturaComRecorte(isIda, false)
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun saveImageFromUri(uri: Uri, prefix: String): String? {
        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val pasta = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val arquivo = File.createTempFile("REGISTRO_${prefix}_", ".jpg", pasta)
            val outputStream = FileOutputStream(arquivo)
            
            inputStream?.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            arquivo.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun compartilharArquivo(arquivo: File) {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            arquivo
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartilhar Relatório"))
    }

    private fun desenharTextoComQuebra(canvas: Canvas, texto: String, x: Float, y: Float, paint: Paint, larguraMax: Int) {
        // Divide o texto priorizando espaços, mas mantendo o símbolo '>' como ponto de quebra
        val palavras = texto.replace(">", " > ").split(" ").filter { it.isNotEmpty() }
        var linhaAtual = ""
        var yOffset = 0f

        for (palavra in palavras) {
            val testeLinha = if (linhaAtual.isEmpty()) palavra else "$linhaAtual $palavra"
            
            if (testeLinha.length <= larguraMax) {
                linhaAtual = testeLinha
            } else {
                if (linhaAtual.isNotEmpty()) {
                    canvas.drawText(linhaAtual, x, y + yOffset, paint)
                    yOffset += 11f // Altura da linha
                    linhaAtual = palavra
                } else {
                    // Se a palavra sozinha for maior que o limite (raro), quebra ela
                    canvas.drawText(palavra.take(larguraMax), x, y + yOffset, paint)
                    yOffset += 11f
                    linhaAtual = palavra.drop(larguraMax)
                }
            }
        }
        if (linhaAtual.isNotEmpty()) {
            canvas.drawText(linhaAtual, x, y + yOffset, paint)
        }
    }

    private fun gerarRelatorioCompleto(viagens: List<Viagem>, isExportacaoHistorico: Boolean = false) {
        val document = PdfDocument()
        
        // --- PÁGINA 1: DADOS (MODO RETRATO 595x842) ---
        val pageInfo1 = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page1 = document.startPage(pageInfo1)
        val canvas1 = page1.canvas
        val paint = Paint()

        paint.isFakeBoldText = true
        paint.textSize = 16f
        paint.textAlign = Paint.Align.CENTER
        
        val titulo = if (isExportacaoHistorico) "HISTÓRICO GERAL DE VIAGENS" else "RELATÓRIO GERAL DE VIAGENS"
        canvas1.drawText(titulo, 297f, 50f, paint)

        // --- SEÇÃO DE FOTOS (NO TOPO AGORA) ---
        val yFotos = 110f

        fun desenharFotoFinal(path: String?, x: Float, y: Float, label: String, hora: String?) {
            if (isExportacaoHistorico) return // Não desenha fotos do dia no histórico geral
            path?.let {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeFile(it, options) ?: return@let
                
                // Desenha a hora da foto acima da imagem
                hora?.let { h ->
                    paint.textSize = 10f
                    paint.textAlign = Paint.Align.LEFT
                    paint.isFakeBoldText = false
                    canvas1.drawText("Hora: $h", x, y - 6f, paint)
                }

                // Tamanho fixo padronizado e compacto para as fotos no PDF (Proporção 3:1)
                val drawWidth = 220f
                val drawHeight = 73f 
                
                val destRect = RectF(x, y, x + drawWidth, y + drawHeight)
                
                val paintFoto = Paint().apply { 
                    isFilterBitmap = true
                    isAntiAlias = true
                    isDither = true
                }
                
                canvas1.drawBitmap(bitmap, null, destRect, paintFoto)
                
                paint.textSize = 9f
                paint.textAlign = Paint.Align.CENTER
                paint.isFakeBoldText = true
                canvas1.drawText(label, x + (drawWidth / 2), y + drawHeight + 12f, paint)
                
                bitmap.recycle()
            }
        }

        // Desenha as fotos lado a lado no topo (Mais compactas)
        desenharFotoFinal(fotoIdaPath, 60f, yFotos, "KM INICIAL (IDA)", fotoIdaHora)
        desenharFotoFinal(fotoVoltaPath, 315f, yFotos, "KM FINAL (VOLTA)", fotoVoltaHora)

        // --- TABELA DE DADOS (ABAIXO DAS FOTOS COM ESPAÇO DE 5CM) ---
        val isParticular = findViewById<RadioButton>(R.id.radioParticular).isChecked
        
        paint.textSize = 8f
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.CENTER
        val yHeader = 360f // Aumentado de 220f para 360f para criar o espaço de ~5cm
        canvas1.drawText("DATA", 35f, yHeader, paint)
        canvas1.drawText("COND.", 80f, yHeader, paint)
        canvas1.drawText("DESTINO", 160f, yHeader, paint)
        canvas1.drawText("OBS.", 250f, yHeader, paint)
        canvas1.drawText("SAÍDA", 330f, yHeader, paint)
        canvas1.drawText("CHEG.", 375f, yHeader, paint)
        canvas1.drawText("KMI", 420f, yHeader, paint)
        canvas1.drawText("KMF", 460f, yHeader, paint)
        canvas1.drawText("TOTAL", 505f, yHeader, paint)
        
        if (isParticular) {
            canvas1.drawText("CUSTO", 560f, yHeader, paint)
        }

        canvas1.drawLine(20f, yHeader + 10f, 575f, yHeader + 10f, paint)

        var kmSomaViagens = 0
        var kmSomaCidade = 0
        var custoGeral = 0.0
        paint.isFakeBoldText = false
        var yPos = yHeader + 35f
        var ultimoKmFinal = -1

        for (v in viagens) {
            // DETECÇÃO DE KM EM CIDADE
            if (ultimoKmFinal != -1 && v.kmIni > ultimoKmFinal) {
                val kmCidadeTrecho = v.kmIni - ultimoKmFinal
                
                paint.color = Color.GRAY // Cor cinza para diferenciar
                canvas1.drawText(v.data, 35f, yPos, paint)
                canvas1.drawText("-", 80f, yPos, paint)
                canvas1.drawText("Deslocamento Urbano", 160f, yPos, paint)
                canvas1.drawText("-", 250f, yPos, paint)
                canvas1.drawText("-", 330f, yPos, paint)
                canvas1.drawText("-", 375f, yPos, paint)
                canvas1.drawText(ultimoKmFinal.toString(), 420f, yPos, paint)
                canvas1.drawText(v.kmIni.toString(), 460f, yPos, paint)
                canvas1.drawText(kmCidadeTrecho.toString(), 505f, yPos, paint)
                
                if (isParticular) {
                    canvas1.drawText(String.format("%.2f", kmCidadeTrecho * 1.20), 560f, yPos, paint)
                }
                
                kmSomaCidade += kmCidadeTrecho
                paint.color = Color.BLACK
                yPos += 25f 
            }

            canvas1.drawText(v.data, 35f, yPos, paint)
            canvas1.drawText(v.condutor.take(8), 80f, yPos, paint)
            desenharTextoComQuebra(canvas1, v.destino, 160f, yPos, paint, 18)
            desenharTextoComQuebra(canvas1, v.observacoes, 250f, yPos, paint, 18)
            canvas1.drawText(v.hSaida, 330f, yPos, paint)
            canvas1.drawText(v.hChegada, 375f, yPos, paint)
            canvas1.drawText(v.kmIni.toString(), 420f, yPos, paint)
            canvas1.drawText(v.kmFin.toString(), 460f, yPos, paint)
            val totalViagem = v.kmFin - v.kmIni
            canvas1.drawText("$totalViagem", 505f, yPos, paint)
            
            if (isParticular) {
                canvas1.drawText(String.format("%.2f", v.custo), 560f, yPos, paint)
            }
            
            kmSomaViagens += totalViagem
            ultimoKmFinal = v.kmFin
            yPos += 30f
            if (yPos > 780f) break 
        }

        canvas1.drawLine(20f, yPos, 575f, yPos, paint)
        yPos += 25f
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = 10f
        
        var kmGeral = 0
        // Calcula o total real (último KM Final - primeiro KM Inicial) 
        if (viagens.isNotEmpty()) {
            kmGeral = viagens.last().kmFin - viagens.first().kmIni
            custoGeral = kmGeral * 1.20
        }

        val resumoTotal = if (isParticular) {
            "KM ESTRADA: $kmSomaViagens | KM CIDADE: $kmSomaCidade | TOTAL: $kmGeral KM | VALOR: R$ ${String.format("%.2f", custoGeral)}"
        } else {
            "KM ESTRADA: $kmSomaViagens | KM CIDADE: $kmSomaCidade | TOTAL: $kmGeral KM"
        }
        canvas1.drawText(resumoTotal, 570f, yPos, paint)

        // --- SEÇÃO DE COMPROVANTES DE DESPESAS (ÁREA MARCADA) ---
        if (listaFotosDespesas.isNotEmpty()) {
            yPos += 40f
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 10f
            paint.isFakeBoldText = true
            canvas1.drawText("COMPROVANTES DE DESPESAS:", 40f, yPos, paint)
            
            yPos += 15f
            var xPosRecibo = 40f
            val reciboWidth = 120f
            val reciboHeight = 120f // Quadrado para recibos costuma ser melhor
            
            for (path in listaFotosDespesas) {
                val bitmap = BitmapFactory.decodeFile(path) ?: continue
                
                val destRect = RectF(xPosRecibo, yPos, xPosRecibo + reciboWidth, yPos + reciboHeight)
                canvas1.drawBitmap(bitmap, null, destRect, null)
                
                bitmap.recycle()
                
                xPosRecibo += reciboWidth + 10f
                // Se chegar no fim da linha, pula para a de baixo
                if (xPosRecibo > 500f) {
                    xPosRecibo = 40f
                    yPos += reciboHeight + 10f
                }
                
                // Evita desenhar fora da página
                if (yPos > 750f) break 
            }
        }

        paint.textSize = 10f
        paint.isFakeBoldText = false
        paint.textAlign = Paint.Align.CENTER
        canvas1.drawText("Aplicativo criado por Luiz Gustavo", 297f, 820f, paint)
        document.finishPage(page1)

        val pasta = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (!pasta.exists()) pasta.mkdirs()
        val timestamp = SimpleDateFormat("ddMMyyyy_HHmm", Locale.getDefault()).format(Date())
        val arquivo = File(pasta, "Relatorio_KM_$timestamp.pdf")

        try {
            document.writeTo(FileOutputStream(arquivo))
            val msgSucesso = if (isExportacaoHistorico) "Histórico Exportado com Sucesso!" else "Relatório com Fotos Gerado!"
            Toast.makeText(this, msgSucesso, Toast.LENGTH_LONG).show()
            
            if (!isExportacaoHistorico) {
                listaDeViagens.clear()
                // Limpa fotos após gerar relatório diário
                fotoIdaPath = null
                fotoVoltaPath = null
                listaFotosDespesas.clear()
                btnFotoDespesa.text = "📸 ADICIONAR DESPESAS (RECIBOS)"
            }
            
            ultimoArquivoGerado = arquivo
            btnCompartilhar.visibility = View.VISIBLE
            validarBotoes() // Atualiza o estado habilitado do botão
            salvarEstado() // Salva o estado limpo ou atualizado
            
            // Abre automaticamente a janela de compartilhamento/salvamento
            compartilharArquivo(arquivo)
        } catch (e: Exception) {
            Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        document.close()
    }
}