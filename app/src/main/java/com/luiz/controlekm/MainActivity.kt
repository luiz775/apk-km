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
import android.text.method.DigitsKeyListener
import android.view.View
import android.widget.*
import androidx.activity.result.IntentSenderRequest
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
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

// 1. O "Molde" da Viagem (Data Class)
data class Viagem(
    val data: String,
    val condutor: String,
    val origem: String,
    val destino: String,
    val hSaida: String,
    val hChegada: String,
    val kmIni: Int,
    val kmFin: Int,
    val custo: Double,
    val observacoes: String = ""
)

// 2. O "Molde" da Despesa
data class Despesa(
    val path: String,
    val categoria: String,
    val valor: Double
)

class MainActivity : AppCompatActivity() {

    private val auth by lazy { Firebase.auth }
    private val db by lazy { Firebase.firestore }

    private val listaDeViagens = mutableListOf<Viagem>()
    private lateinit var adapterOrigem: ArrayAdapter<String>
    private lateinit var adapterDestino: ArrayAdapter<String>
    private val historicoOrigens = mutableListOf<String>()
    private val historicoDestinos = mutableListOf<String>()
    private var ultimoArquivoGerado: File? = null
    
    private var fotoIdaPath: String? = null
    private var fotoVoltaPath: String? = null
    private var fotoIdaHora: String? = null
    private var fotoVoltaHora: String? = null
    private var pedindoFotoIda = true
    private var pedindoFotoDespesa = false
    private val listaFotosDespesas = mutableListOf<String>()
    private val listaDadosDespesas = mutableListOf<Despesa>()

    // UI Elements
    private lateinit var editData: EditText
    private lateinit var editCondutor: EditText
    private lateinit var editOrigem: AutoCompleteTextView
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

    private val scannerOptions = GmsDocumentScannerOptions.Builder()
        .setResultFormats(RESULT_FORMAT_JPEG)
        .setScannerMode(SCANNER_MODE_FULL)
        .setGalleryImportAllowed(true)
        .build()

    private val scanner = GmsDocumentScanning.getClient(scannerOptions)

    private val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanningResult?.pages?.get(0)?.imageUri?.let { uri ->
                mostrarDialogoConfirmacaoScanner(uri)
            }
        }
    }

    private fun mostrarDialogoConfirmacaoScanner(uri: Uri, bitmapParaGirar: Bitmap? = null) {
        val imageView = ImageView(this)
        val padding = (20 * resources.displayMetrics.density).toInt()
        imageView.setPadding(padding, padding, padding, padding)
        
        val bitmap = bitmapParaGirar ?: try {
            val inputStream = contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) { null }
        
        if (bitmap == null) {
            Toast.makeText(this, "Erro ao carregar imagem digitalizada", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Ajusta o tamanho da pré-visualização para não travar o app
        val scale = 800f / Math.max(bitmap.width, bitmap.height)
        val previewBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        
        imageView.setImageBitmap(previewBitmap)

        AlertDialog.Builder(this)
            .setTitle("Confirmar Recibo")
            .setMessage("A imagem está na posição correta?")
            .setView(imageView)
            .setPositiveButton("Sim, Salvar") { _, _ ->
                val path = saveBitmapToFile(bitmap, "DESPESA")
                if (path != null) {
                    mostrarDialogoValorDespesa(path)
                }
            }
            .setNeutralButton("Girar 180°") { _, _ ->
                val matrix = Matrix().apply { postRotate(180f) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                mostrarDialogoConfirmacaoScanner(uri, rotated)
            }
            .setNegativeButton("Tentar Novamente") { _, _ ->
                btnFotoDespesa.performClick()
            }
            .show()
    }

    private fun saveBitmapToFile(bitmap: Bitmap, prefix: String): String? {
        return try {
            val pasta = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val arquivo = File.createTempFile("REGISTRO_${prefix}_", ".jpg", pasta)
            val outputStream = FileOutputStream(arquivo)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            outputStream.flush()
            outputStream.close()
            arquivo.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun mostrarDialogoValorDespesa(path: String) {
        // Na verdade vamos criar um layout customizado rápido via código para não precisar mexer em XML agora
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 20, 50, 20)

        val txtCategoria = TextView(this)
        txtCategoria.text = "Selecione a Categoria:"
        layout.addView(txtCategoria)

        val spinner = Spinner(this)
        val categorias = arrayOf("Almoço", "Jantar", "Pernoite", "Outros")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categorias)
        layout.addView(spinner)

        val txtValor = TextView(this)
        txtValor.text = "\nValor (R$):"
        layout.addView(txtValor)

        val inputValor = EditText(this)
        inputValor.inputType = InputType.TYPE_CLASS_NUMBER
        inputValor.keyListener = DigitsKeyListener.getInstance("0123456789,") // Permite explicitamente a vírgula
        inputValor.hint = "0,00"
        
        inputValor.addTextChangedListener(object : TextWatcher {
            private var current = ""
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s.toString() != current) {
                    inputValor.removeTextChangedListener(this)
                    val cleanString = s.toString().replace("""[R$,.\s]""".toRegex(), "")
                    if (cleanString.isNotEmpty()) {
                        try {
                            val parsed = cleanString.toDouble()
                            val formatted = NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(parsed / 100)
                            current = formatted.replace("R$", "").replace(" ", "").trim()
                            s?.replace(0, s.length, current)
                        } catch (e: Exception) { e.printStackTrace() }
                    } else {
                        current = ""
                        s?.clear()
                    }
                    inputValor.addTextChangedListener(this)
                }
            }
        })
        layout.addView(inputValor)

        AlertDialog.Builder(this)
            .setTitle("Dados da Despesa")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Confirmar") { _, _ ->
                val categoria = spinner.selectedItem.toString()
                // Correção: Primeiro removemos os pontos de milhar, depois trocamos a vírgula decimal por ponto
                val valorTexto = inputValor.text.toString().replace(".", "").replace(",", ".")
                val valor = valorTexto.toDoubleOrNull() ?: 0.0
                
                listaDadosDespesas.add(Despesa(path, categoria, valor))
                listaFotosDespesas.add(path)
                
                btnFotoDespesa.apply {
                    text = "✅ RECIBO ADICIONADO (${listaFotosDespesas.size})"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#9C27B0"))
                }
                salvarEstado()
                validarBotoes()
                Toast.makeText(this, "Despesa salva: R$ $valor", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar") { _, _ -> 
                File(path).delete() // Remove a foto se cancelar
            }
            .show()
    }

    private val cropImage = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val uri = result.uriContent
            val path = saveImageFromUri(uri!!, if (pedindoFotoDespesa) "DESPESA" else (if (pedindoFotoIda) "IDA" else "VOLTA"))
            if (path != null) {
                val horaAtual = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                
                if (pedindoFotoDespesa) {
                    mostrarDialogoValorDespesa(path)
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
        editOrigem = findViewById(R.id.editOrigem)
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

        // LIMPEZA DO HISTÓRICO ANTIGO (Roda uma vez para limpar as cidades salvas)
        prefs.edit().remove("historico_origens").remove("historico_destinos").apply()

        val salvosOrigem = prefs.getStringSet("historico_origens", emptySet())
        historicoOrigens.clear()
        historicoOrigens.addAll(salvosOrigem!!)
        adapterOrigem = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, historicoOrigens)
        editOrigem.setAdapter(adapterOrigem)

        val salvosDestino = prefs.getStringSet("historico_destinos", emptySet())
        historicoDestinos.clear()
        historicoDestinos.addAll(salvosDestino!!)
        adapterDestino = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, historicoDestinos)
        editDestino.setAdapter(adapterDestino)
        
        // Mostrar atalhos ao clicar no campo
        listOf(editOrigem, editDestino).forEach { view ->
            view.setOnClickListener {
                if (view.text.isEmpty()) view.showDropDown()
            }
            view.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && view.text.isEmpty()) view.showDropDown()
            }
        }

        // Recupera rascunho e estado salvo
        editData.setText(prefs.getString("rascunho_data", ""))
        editOrigem.setText(prefs.getString("rascunho_origem", ""))
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
        }
        
        val dadosDespesasSalvas = prefs.getString("listaDadosDespesas", "[]")
        if (!dadosDespesasSalvas.isNullOrEmpty()) {
            try {
                val array = JSONArray(dadosDespesasSalvas)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    listaDadosDespesas.add(Despesa(
                        obj.getString("path"), obj.getString("categoria"), obj.getDouble("valor")
                    ))
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        if (listaFotosDespesas.isNotEmpty()) {
            btnFotoDespesa.text = "✅ RECIBO ADICIONADO (${listaFotosDespesas.size})"
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
                        obj.getString("data"), obj.getString("condutor"), 
                        if (obj.has("origem")) obj.getString("origem") else "",
                        obj.getString("destino"),
                        obj.getString("hSaida"), obj.getString("hChegada"),
                        obj.getInt("kmIni"), obj.getInt("kmFin"), obj.getDouble("custo"),
                        if (obj.has("observacoes")) obj.getString("observacoes") else ""
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
        editOrigem.addTextChangedListener(watcherBotoes)
        editDestino.addTextChangedListener(watcherBotoes)

        btnFotoIda.setOnClickListener { 
            pedindoFotoIda = true
            pedindoFotoDespesa = false
            mostrarDialogoSelecaoImagem(true) 
        }
        btnFotoVolta.setOnClickListener { 
            pedindoFotoIda = false
            pedindoFotoDespesa = false
            mostrarDialogoSelecaoImagem(false) 
        }
        btnFotoDespesa.setOnClickListener {
            pedindoFotoDespesa = true
            scanner.getStartScanIntent(this)
                .addOnSuccessListener { intentSender ->
                    scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Erro ao abrir scanner: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }

        radioGroupVeiculo.setOnCheckedChangeListener { _, _ -> salvarEstado() }

        btnAdicionar.setOnClickListener {
            val dataTxt = editData.text.toString().trim()
            val condutorTxt = editCondutor.text.toString().trim()
            val origemTxt = editOrigem.text.toString().trim()
            val destinoTxt = editDestino.text.toString().trim()
            val saidaTxt = editHoraSaida.text.toString().trim()
            val chegadaTxt = editHoraChegada.text.toString().trim()
            val kmITxt = editKmInicial.text.toString().trim()
            val kmFTxt = editKmFinal.text.toString().trim()

            // Verificação de campos obrigatórios
            val camposFaltando = mutableListOf<String>()
            if (dataTxt.isEmpty()) camposFaltando.add("Data")
            if (condutorTxt.isEmpty()) camposFaltando.add("Condutor")
            if (origemTxt.isEmpty()) camposFaltando.add("Origem")
            if (destinoTxt.isEmpty()) camposFaltando.add("Destino")
            if (saidaTxt.isEmpty()) camposFaltando.add("Hora de Saída")
            if (chegadaTxt.isEmpty()) camposFaltando.add("Hora de Chegada")
            if (kmITxt.isEmpty()) camposFaltando.add("KM Inicial")
            if (kmFTxt.isEmpty()) camposFaltando.add("KM Final")

            if (camposFaltando.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Campos Obrigatórios")
                    .setMessage("Por favor, preencha os seguintes campos antes de adicionar:\n\n• ${camposFaltando.joinToString("\n• ")}")
                    .setPositiveButton("Entendido", null)
                    .show()
                return@setOnClickListener
            }

            val kmI = kmITxt.toIntOrNull() ?: 0
            val kmF = kmFTxt.toIntOrNull() ?: 0

            if (kmI > kmF) {
                AlertDialog.Builder(this)
                    .setTitle("Quilometragem Inválida")
                    .setMessage("O KM inicial não pode ser maior que o KM final!")
                    .setPositiveButton("Corrigir", null)
                    .show()
                return@setOnClickListener
            }

            val kmTotal = kmF - kmI

            // Salva no histórico de origens
            if (origemTxt.isNotEmpty() && !historicoOrigens.any { it.equals(origemTxt, ignoreCase = true) }) {
                historicoOrigens.add(origemTxt)
                historicoOrigens.sort()
                adapterOrigem.notifyDataSetChanged()
            }

            // Salva no histórico de destinos
            if (destinoTxt.isNotEmpty() && !historicoDestinos.any { it.equals(destinoTxt, ignoreCase = true) }) {
                historicoDestinos.add(destinoTxt)
                historicoDestinos.sort()
                adapterDestino.notifyDataSetChanged()
            }

            val v = Viagem(
                dataTxt, condutorTxt, origemTxt, destinoTxt,
                saidaTxt, chegadaTxt,
                kmI, kmF, kmTotal * 1.20, editObservacoes.text.toString()
            )

            listaDeViagens.add(v)
            
            // Salva no histórico permanente local
            val currentUser = auth.currentUser
            val historicoKey = if (currentUser != null) "historico_local_${currentUser.uid}" else "historico_geral_local"
            val historicoGeral = prefs.getString(historicoKey, "[]")
            val arrayHistorico = JSONArray(historicoGeral)
            val obj = JSONObject().apply {
                put("data", v.data); put("condutor", v.condutor); put("origem", v.origem); put("destino", v.destino)
                put("hSaida", v.hSaida); put("hChegada", v.hChegada)
                put("kmIni", v.kmIni); put("kmFin", v.kmFin); put("custo", v.custo)
                put("observacoes", v.observacoes)
            }
            arrayHistorico.put(obj)
            prefs.edit().putString(historicoKey, arrayHistorico.toString()).apply()

            Toast.makeText(this, "Viagem adicionada!", Toast.LENGTH_SHORT).show()

            editOrigem.text.clear()
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
            if (listaDeViagens.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Relatório Vazio")
                    .setMessage("Adicione pelo menos uma viagem à lista antes de gerar o PDF.")
                    .setPositiveButton("OK", null)
                    .show()
            }
            else if (fotoVoltaPath == null) {
                AlertDialog.Builder(this)
                    .setTitle("Foto Pendente")
                    .setMessage("Você esqueceu de tirar a FOTO DA VOLTA. Ela é necessária para finalizar o relatório.")
                    .setPositiveButton("Tirar Foto") { _, _ -> btnFotoVolta.performClick() }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
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
                    listaDadosDespesas.clear()
                    listaDeViagens.clear()
                    
                    editData.text.clear()
                    editOrigem.text.clear()
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
            
            text1.text = "${viagem.origem} > ${viagem.destino} (${viagem.data})"
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
            putString("rascunho_origem", editOrigem.text.toString())
            putString("rascunho_destino", editDestino.text.toString())
            putString("rascunho_hSaida", editHoraSaida.text.toString())
            putString("rascunho_kmIni", editKmInicial.text.toString())
            putString("rascunho_obs", editObservacoes.text.toString())
            putStringSet("historico_origens", historicoOrigens.toSet())
            putStringSet("historico_destinos", historicoDestinos.toSet())
            putBoolean("veiculo_empresa", findViewById<RadioButton>(R.id.radioEmpresa).isChecked)
            
            putString("fotoIdaPath", fotoIdaPath)
            putString("fotoVoltaPath", fotoVoltaPath)
            putString("fotoIdaHora", fotoIdaHora)
            putString("fotoVoltaHora", fotoVoltaHora)
            putString("listaFotosDespesas", listaFotosDespesas.joinToString("|"))
            
            val arrayDadosDesp = JSONArray()
            listaDadosDespesas.forEach { d ->
                val obj = JSONObject().apply {
                    put("path", d.path); put("categoria", d.categoria); put("valor", d.valor)
                }
                arrayDadosDesp.put(obj)
            }
            putString("listaDadosDespesas", arrayDadosDesp.toString())
            
            val array = JSONArray()
            listaDeViagens.forEach { v ->
                val obj = JSONObject().apply {
                    put("data", v.data); put("condutor", v.condutor); put("origem", v.origem); put("destino", v.destino)
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
        val temOrigem = editOrigem.text.toString().trim().isNotEmpty()
        val temDestino = editDestino.text.toString().trim().isNotEmpty()

        // PRIORIDADE: Só libera os campos se tiver foto de ida
        val layoutCampos = listOf(editData, editCondutor, editOrigem, editDestino, editHoraSaida, editHoraChegada, editKmInicial, editKmFinal, editObservacoes)
        layoutCampos.forEach { it.isEnabled = temFotoIda }
        
        btnFotoDespesa.isEnabled = temFotoIda
        btnAdicionar.isEnabled = !temErro && temFotoIda && temOrigem && temDestino
        
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
                                            doc.getString("origem") ?: "",
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
        
        // CORREÇÃO: Criamos uma CÓPIA da lista (.toList()) para o envio
        val copiaParaEnvio = listaDeViagens.toList()
        val copiaDespesas = listaDadosDespesas.toList()
        salvarNaPlanilhaGoogle(copiaParaEnvio, copiaDespesas)

        // Envia para o Firestore individualmente
        var sucessoCount = 0
        copiaParaEnvio.forEach { v ->
            val dadosViagem = hashMapOf(
                "tecnicoId" to currentUser.uid,
                "data" to v.data,
                "condutor" to v.condutor,
                "origem" to v.origem,
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
                    if (sucessoCount == copiaParaEnvio.size) {
                        Toast.makeText(this, "Sincronização concluída!", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun salvarNaPlanilhaGoogle(viagens: List<Viagem>, despesas: List<Despesa> = emptyList()) {
        val scriptUrl = "https://script.google.com/macros/s/AKfycbz-vPT7DHjux2zBzc2PAo6a3O99rb4aE70xjdWVtcnNIbR00S1045Fa15lwe-J58Yhs/exec"
        
        if (scriptUrl.isEmpty() || scriptUrl.contains("SUA_URL")) return

        Thread {
            try {
                val url = URL(scriptUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")

                // Geramos um ID único baseado no horário da primeira viagem da lista
                val loteId = if (viagens.isNotEmpty()) viagens[0].hSaida.replace(":", "") else "0000"

                val jsonEnvio = JSONObject()
                jsonEnvio.put("loteId", loteId)
                
                val jsonViagens = JSONArray()
                viagens.forEach { v ->
                    val obj = JSONObject().apply {
                        put("data", v.data)
                        put("condutor", v.condutor)
                        put("origem", v.origem)
                        put("destino", v.destino)
                        put("saida", v.hSaida)
                        put("chegada", v.hChegada)
                        put("kmIni", v.kmIni)
                        put("kmFin", v.kmFin)
                        put("custo", v.custo)
                        put("obs", v.observacoes)
                    }
                    jsonViagens.put(obj)
                }
                jsonEnvio.put("viagens", jsonViagens)

                val jsonDespesas = JSONArray()
                despesas.forEach { d ->
                    val obj = JSONObject().apply {
                        put("categoria", d.categoria)
                        put("valor", d.valor)
                    }
                    jsonDespesas.put(obj)
                }
                jsonEnvio.put("despesas", jsonDespesas)

                conn.outputStream.use { os ->
                    os.write(jsonEnvio.toString().toByteArray())
                }

                // O Google Apps Script exige que leiamos a resposta para processar
                val responseCode = conn.responseCode
                if (responseCode in 200..399) {
                    val inputContent = conn.inputStream.bufferedReader().use { it.readText() }
                    println("Google Success: $inputContent")
                } else {
                    val errorContent = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    println("Google Error: $errorContent")
                }
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun iniciarCapturaComRecorte(isIda: Boolean, daCamera: Boolean) {
        pedindoFotoIda = isIda
        
        val options = CropImageOptions(
            guidelines = CropImageView.Guidelines.ON,
            activityTitle = if (pedindoFotoDespesa) "Recortar Recibo" else "Ajustar Foto de KM",
            fixAspectRatio = !pedindoFotoDespesa,
            aspectRatioX = if (pedindoFotoDespesa) 1 else 3,
            aspectRatioY = 1,
            initialCropWindowPaddingRatio = 0.2f, 
            imageSourceIncludeCamera = daCamera,
            imageSourceIncludeGallery = !daCamera,
            activityMenuIconColor = Color.WHITE,
            allowRotation = true,
            allowFlipping = true,
            outputRequestWidth = 1280, // ALTA QUALIDADE: Força 1280px de largura
            outputRequestHeight = 0,    // Mantém a proporção original
            outputCompressFormat = Bitmap.CompressFormat.JPEG,
            outputCompressQuality = 90 // ALTA QUALIDADE: 90%
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

    private fun desenharTextoComQuebra(canvas: Canvas, texto: String, x: Float, y: Float, paint: Paint, larguraMax: Int): Float {
        // Divide o texto priorizando espaços, mas mantendo o símbolo '>' como ponto de quebra
        val palavras = texto.replace(">", " > ").split(" ").filter { it.isNotEmpty() }
        var linhaAtual = ""
        var yOffset = 0f
        var maiorYOffset = 0f

        for (palavra in palavras) {
            val testeLinha = if (linhaAtual.isEmpty()) palavra else "$linhaAtual $palavra"
            
            if (testeLinha.length <= larguraMax) {
                linhaAtual = testeLinha
            } else {
                if (linhaAtual.isNotEmpty()) {
                    canvas.drawText(linhaAtual, x, y + yOffset, paint)
                    yOffset += 11f // Altura da linha
                    linhaAtual = palavra
                    maiorYOffset = yOffset
                } else {
                    // Se a palavra sozinha for maior que o limite (raro), quebra ela
                    canvas.drawText(palavra.take(larguraMax), x, y + yOffset, paint)
                    yOffset += 11f
                    linhaAtual = palavra.drop(larguraMax)
                    maiorYOffset = yOffset
                }
            }
        }
        if (linhaAtual.isNotEmpty()) {
            canvas.drawText(linhaAtual, x, y + yOffset, paint)
        }
        return maiorYOffset
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

        // --- TABELA DE DADOS (SUBIU PARA DIMINUIR O ESPAÇO EM BRANCO) ---
        val isParticular = findViewById<RadioButton>(R.id.radioParticular).isChecked
        
        paint.textSize = 7f 
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.CENTER
        val yHeader = 230f 
        canvas1.drawText("DATA", 30f, yHeader, paint)
        canvas1.drawText("COND.", 65f, yHeader, paint)
        canvas1.drawText("ORIGEM", 105f, yHeader, paint)
        canvas1.drawText("DESTINO", 165f, yHeader, paint)
        canvas1.drawText("OBS.", 235f, yHeader, paint)
        canvas1.drawText("SAÍDA", 330f, yHeader, paint)
        canvas1.drawText("CHEG.", 370f, yHeader, paint)
        canvas1.drawText("KMI", 405f, yHeader, paint)
        canvas1.drawText("KMF", 440f, yHeader, paint)
        canvas1.drawText("URB.", 475f, yHeader, paint) // Nova coluna compacta
        canvas1.drawText("TOTAL", 515f, yHeader, paint)
        
        if (isParticular) {
            canvas1.drawText("CUSTO", 560f, yHeader, paint)
        }

        canvas1.drawLine(20f, yHeader + 10f, 575f, yHeader + 10f, paint)

        var kmSomaViagens = 0
        var kmSomaCidade = 0
        var custoGeral = 0.0
        paint.isFakeBoldText = false
        var yPos = yHeader + 35f

        for (index in viagens.indices) {
            val v = viagens[index]
            
            val nomeAbreviado = try {
                val partes = v.condutor.trim().split(" ")
                if (partes.size > 1) "${partes[0]} ${partes[1].take(1)}." else partes[0]
            } catch (e: Exception) { v.condutor }

            // LÓGICA DE OLHAR PARA FRENTE: O KM Urbano vai para a viagem que chegou no destino
            var kmUrbano = 0
            if (index < viagens.size - 1) {
                val vProx = viagens[index + 1]
                if (vProx.kmIni > v.kmFin) {
                    kmUrbano = vProx.kmIni - v.kmFin
                }
            }

            canvas1.drawText(v.data, 30f, yPos, paint)
            canvas1.drawText(nomeAbreviado.take(12), 65f, yPos, paint)
            val offsetOrigem = desenharTextoComQuebra(canvas1, v.origem, 105f, yPos, paint, 14)
            val offsetDestino = desenharTextoComQuebra(canvas1, v.destino, 165f, yPos, paint, 14)
            val offsetObs = desenharTextoComQuebra(canvas1, v.observacoes, 235f, yPos, paint, 16)
            
            canvas1.drawText(v.hSaida, 330f, yPos, paint)
            canvas1.drawText(v.hChegada, 370f, yPos, paint)
            canvas1.drawText(v.kmIni.toString(), 405f, yPos, paint)
            canvas1.drawText(v.kmFin.toString(), 440f, yPos, paint)
            canvas1.drawText(kmUrbano.toString(), 475f, yPos, paint)
            
            val totalViagem = (v.kmFin - v.kmIni) + kmUrbano
            canvas1.drawText(totalViagem.toString(), 515f, yPos, paint)
            
            if (isParticular) {
                val custoCalculado = totalViagem * 1.20
                canvas1.drawText(String.format("%.2f", custoCalculado), 560f, yPos, paint)
            }
            
            kmSomaViagens += (v.kmFin - v.kmIni)
            kmSomaCidade += kmUrbano
            
            val saltoLinha = Math.max(offsetOrigem, Math.max(offsetDestino, offsetObs))
            yPos += 30f + saltoLinha

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

        // --- SEÇÃO DE COMPROVANTES DE DESPESAS (FOTOS MAIORES E MELHOR QUALIDADE) ---
        if (listaFotosDespesas.isNotEmpty()) {
            yPos += 70f // Aumentado de 40f para 70f para descer a seção
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 10f
            paint.isFakeBoldText = true
            canvas1.drawText("COMPROVANTES DE DESPESAS:", 40f, yPos, paint)
            
            yPos += 15f
            var xPosRecibo = 40f
            val reciboWidth = 230f // Aumentado de 120f para 230f
            val reciboHeight = 230f // Aumentado de 120f para 230f
            
            val paintQualidade = Paint().apply {
                isFilterBitmap = true
                isAntiAlias = true
                isDither = true
            }
            
            for (path in listaFotosDespesas) {
                val bitmap = BitmapFactory.decodeFile(path) ?: continue
                
                val destRect = RectF(xPosRecibo, yPos, xPosRecibo + reciboWidth, yPos + reciboHeight)
                canvas1.drawBitmap(bitmap, null, destRect, paintQualidade)
                
                bitmap.recycle()
                
                xPosRecibo += reciboWidth + 20f
                // Se chegar no fim da linha, pula para a de baixo
                if (xPosRecibo + reciboWidth > 580f) {
                    xPosRecibo = 40f
                    yPos += reciboHeight + 20f
                }
                
                // Evita desenhar fora da página
                if (yPos + reciboHeight > 800f) break
            }
        }

        paint.textSize = 8f // Reduzido de 10f para 8f
        paint.isFakeBoldText = false
        paint.textAlign = Paint.Align.RIGHT // Alinhado à direita
        canvas1.drawText("Aplicativo criado por Luiz Gustavo", 575f, 825f, paint) // Movido para o canto inferior direito
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
            salvarEstado() // Salva o estado limpo ou updated
            
            // Abre automaticamente a janela de compartilhamento/salvamento
            compartilharArquivo(arquivo)
        } catch (e: Exception) {
            Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        document.close()
    }
}