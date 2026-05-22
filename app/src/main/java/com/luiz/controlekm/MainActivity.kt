package com.luiz.controlekm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import android.view.animation.AnimationUtils
import android.view.animation.AccelerateDecelerateInterpolator
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import com.google.android.material.snackbar.Snackbar
import android.view.ViewGroup
import android.view.Gravity
import android.view.LayoutInflater
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.android.gms.location.LocationServices
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.ktx.auth
import com.google.firebase.crashlytics.FirebaseCrashlytics
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
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
    val observacoes: String = "",
    val isEmpresa: Boolean = false
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
    private lateinit var btnFotoIda: View
    private lateinit var btnFotoVolta: View
    private lateinit var btnFotoDespesa: View
    private lateinit var btnReset: Button
    private lateinit var btnConfiguracoes: ImageButton
    private lateinit var btnTrocarTema: ImageButton
    private lateinit var radioGroupVeiculo: RadioGroup
    private lateinit var containerViagens: LinearLayout
    private lateinit var txtOlaUsuario: TextView
    private lateinit var txtViagensCount: TextView
    private lateinit var mainScrollView: ScrollView
    
    // Banner treinamento
    private lateinit var bannerTreinamento: TextView
    
    // Novas referências para feedback visual fotos
    private lateinit var iconIda: TextView
    private lateinit var iconVolta: TextView
    private lateinit var subLblFotoIda: TextView
    private lateinit var subLblFotoVolta: TextView
    
    // Dashboard elements
    private lateinit var txtDashKmTotal: TextView
    private lateinit var txtDashCustoTotal: TextView
    private lateinit var txtDashGastoCombustivel: TextView
    private lateinit var txtDashFaltaOleo: TextView

    private lateinit var txtCountIda: TextView
    private lateinit var txtCountVolta: TextView
    private lateinit var txtCountDespesa: TextView
    
    private lateinit var btnGpsOrigem: ImageButton
    private lateinit var btnGpsDestino: ImageButton
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var isAppForeground = false

    private val crashlytics by lazy { FirebaseCrashlytics.getInstance() }

    private fun reportarErro(e: Exception, contexto: String) {
        try {
            crashlytics.log("Contexto Erro: $contexto")
            crashlytics.recordException(e)
            runOnUiThread {
                Toast.makeText(this, "Erro: $contexto", Toast.LENGTH_SHORT).show()
            }
        } catch (ex: Exception) { e.printStackTrace() }
    }

    private val CHANNEL_ID = "sync_channel_controlekm"

    private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Permissão de notificação negada. O feedback de sincronização será via alertas.", Toast.LENGTH_LONG).show()
        }
    }

    private val scannerOptions = GmsDocumentScannerOptions.Builder()
        .setResultFormats(RESULT_FORMAT_JPEG)
        .setScannerMode(SCANNER_MODE_FULL)
        .setGalleryImportAllowed(true)
        .build()

    private val scanner = GmsDocumentScanning.getClient(scannerOptions)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanningResult?.pages?.get(0)?.imageUri?.let { uri ->
                mostrarDialogoConfirmacaoScanner(uri)
            }
        }
    }

    private fun mostrarDialogoConfirmacaoScanner(uri: Uri, bitmapParaGirar: Bitmap? = null) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_dialog_confirmar_scan, null)
        dialog.setContentView(view)
        
        val imgPreview = view.findViewById<ImageView>(R.id.imgPreviewScan)
        val btnSim = view.findViewById<Button>(R.id.btnConfirmarScan)
        val btnGirar = view.findViewById<Button>(R.id.btnGirarScan)
        val btnRepetir = view.findViewById<Button>(R.id.btnRepetirScan)
        
        val bitmap = bitmapParaGirar ?: try {
            val inputStream = contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) { null }
        
        if (bitmap == null) {
            Toast.makeText(this, "Erro ao carregar imagem", Toast.LENGTH_SHORT).show()
            return
        }
        
        imgPreview.setImageBitmap(bitmap)

        btnSim.setOnClickListener {
            dialog.dismiss()
            val path = saveBitmapToFile(bitmap, "DESPESA")
            if (path != null) mostrarDialogoValorDespesa(path)
        }

        btnGirar.setOnClickListener {
            dialog.dismiss()
            val matrix = Matrix().apply { postRotate(180f) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            mostrarDialogoConfirmacaoScanner(uri, rotated)
        }

        btnRepetir.setOnClickListener {
            dialog.dismiss()
            btnFotoDespesa.performClick()
        }

        dialog.show()
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

    private fun mostrarDialogoRapidoCombustivel() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_dialog_despesa, null)
        dialog.setContentView(view)

        val txtTitulo = view.findViewById<TextView>(R.id.txtTituloDespesa)
        val spinner = view.findViewById<Spinner>(R.id.spinnerCategoria)
        val inputValor = view.findViewById<EditText>(R.id.inputValorDespesa)
        val btnConfirmar = view.findViewById<Button>(R.id.btnConfirmarDespesa)
        val btnCancelar = view.findViewById<Button>(R.id.btnCancelarDespesa)

        // Ajustes para o modo Combustível Rápido
        txtTitulo.text = "REGISTRAR ABASTECIMENTO"
        view.findViewById<View>(R.id.containerSpinnerCategoria)?.visibility = View.GONE
        view.findViewById<TextView>(R.id.txtLabelCategoria)?.visibility = View.GONE
        view.findViewById<TextView>(R.id.txtLabelValor)?.text = "Quanto você abasteceu? (R$)"
        btnConfirmar.text = "SALVAR COMBUSTÍVEL"
        btnConfirmar.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5722"))

        // Máscara de moeda reaproveitada
        inputValor.inputType = InputType.TYPE_CLASS_NUMBER
        inputValor.keyListener = DigitsKeyListener.getInstance("0123456789,")

        inputValor.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                isUpdating = true
                val str = s.toString().replace("""[^0-9]""".toRegex(), "")
                if (str.isNotEmpty()) {
                    try {
                        val doubleValue = str.toDouble() / 100.0
                        val formatted = String.format(Locale("pt", "BR"), "%,.2f", doubleValue)
                        s?.replace(0, s.length, formatted)
                        inputValor.setSelection(inputValor.text.length)
                    } catch (e: Exception) { }
                } else { s?.clear() }
                isUpdating = false
            }
        })

        btnConfirmar.setOnClickListener {
            val valorNovoTexto = inputValor.text.toString()
            if (valorNovoTexto.isNotEmpty()) {
                val prefs = getSharedPreferences("DadosVeiculo", Context.MODE_PRIVATE)
                
                // 1. Pega o valor que já estava salvo
                val valorAntigoTexto = prefs.getString("veiculo_gasto_combustivel", "0,00") ?: "0,00"
                
                // 2. Converte ambos para Double (limpando a máscara)
                val valorAntigo = valorAntigoTexto.replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
                val valorNovo = valorNovoTexto.replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
                
                // 3. Soma os valores
                val totalSoma = valorAntigo + valorNovo
                
                // 4. Formata de volta para a máscara 0,00
                val totalMascarado = String.format(Locale("pt", "BR"), "%,.2f", totalSoma)
                
                prefs.edit().putString("veiculo_gasto_combustivel", totalMascarado).apply()

                // Salva também no histórico datado para o Histórico de Consumo
                val currentUser = auth.currentUser
                val fuelKey = if (currentUser != null) "historico_combustivel_local_${currentUser.uid}" else "historico_combustivel_geral"
                val prefsApp = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
                val historicoComb = prefsApp.getString(fuelKey, "[]")
                val arrayFuel = JSONArray(historicoComb)
                val dataAtual = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                val objFuel = JSONObject().apply {
                    put("data", dataAtual)
                    put("valor", valorNovo)
                }
                arrayFuel.put(objFuel)
                prefsApp.edit().putString(fuelKey, arrayFuel.toString()).apply()

                // NOVO: Sincroniza combustível com a Nuvem
                if (currentUser != null) {
                    val dadosFuel = hashMapOf(
                        "tecnicoId" to currentUser.uid,
                        "data" to dataAtual,
                        "valor" to valorNovo,
                        "timestamp" to FieldValue.serverTimestamp()
                    )
                    db.collection("combustivel").add(dadosFuel)
                    
                    // Atualiza também o valor acumulado no doc do veículo na nuvem
                    db.collection("veiculos").document(currentUser.uid).update("combustivel", totalMascarado)
                }

                atualizarDashboard()
                dialog.dismiss()
                Toast.makeText(this, "Abastecimento somado ao total!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Informe o valor!", Toast.LENGTH_SHORT).show()
            }
        }

        btnCancelar.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun mostrarDialogoValorDespesa(path: String) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_dialog_despesa, null)
        dialog.setContentView(view)

        val spinner = view.findViewById<Spinner>(R.id.spinnerCategoria)
        val inputValor = view.findViewById<EditText>(R.id.inputValorDespesa)
        val btnConfirmar = view.findViewById<Button>(R.id.btnConfirmarDespesa)
        val btnCancelar = view.findViewById<Button>(R.id.btnCancelarDespesa)

        val categorias = arrayOf("Almoço", "Jantar", "Pernoite", "Outros")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categorias)

        // Máscara de moeda corrigida
        inputValor.inputType = InputType.TYPE_CLASS_NUMBER
        inputValor.keyListener = DigitsKeyListener.getInstance("0123456789,")

        inputValor.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                isUpdating = true

                val str = s.toString().replace("""[^0-9]""".toRegex(), "")
                if (str.isNotEmpty()) {
                    try {
                        val doubleValue = str.toDouble() / 100.0
                        val formatted = String.format(Locale("pt", "BR"), "%,.2f", doubleValue)
                        s?.replace(0, s.length, formatted)
                        inputValor.setSelection(inputValor.text.length)
                    } catch (e: Exception) { }
                } else {
                    s?.clear()
                }

                isUpdating = false
            }
        })

        btnConfirmar.setOnClickListener {
            val categoria = spinner.selectedItem.toString()
            val valorTexto = inputValor.text.toString().replace(".", "").replace(",", ".")
            val valor = valorTexto.toDoubleOrNull() ?: 0.0
            
            listaDadosDespesas.add(Despesa(path, categoria, valor))
            listaFotosDespesas.add(path)
            
            btnFotoDespesa.alpha = 1.0f
            
            salvarEstado()
            validarBotoes()
            atualizarDashboard()
            dialog.dismiss()
            Toast.makeText(this, "Despesa salva: R$ $valor", Toast.LENGTH_SHORT).show()
        }

        btnCancelar.setOnClickListener {
            File(path).delete()
            dialog.dismiss()
        }

        dialog.setCancelable(false)
        dialog.show()
    }

    private val cropImage = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val uri = result.uriContent
            val path = saveImageFromUri(uri!!, if (pedindoFotoDespesa) "DESPESA" else (if (pedindoFotoIda) "IDA" else "VOLTA"))
            if (path != null) {
                val horaAtual = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                
                if (pedindoFotoDespesa) {
                    mostrarDialogoValorDespesa(path)
                } else {
                    // Tenta ler o KM automaticamente via OCR
                    processarOcrHodometro(uri, pedindoFotoIda)

                    if (pedindoFotoIda) {
                        fotoIdaPath = path
                        fotoIdaHora = horaAtual
                        btnFotoIda.alpha = 1.0f
                        iconIda.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4DFF5722"))
                        subLblFotoIda.text = "Foto anexada ✅"
                        subLblFotoIda.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
                        btnFotoVolta.isEnabled = true
                    } else {
                        fotoVoltaPath = path
                        fotoVoltaHora = horaAtual
                        btnFotoVolta.alpha = 1.0f
                        iconVolta.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4D3D7FFF"))
                        subLblFotoVolta.text = "Foto anexada ✅"
                        subLblFotoVolta.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
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

    private val requestLocationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            Toast.makeText(this, "Localização liberada! Clique no botão novamente.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permissão de Localização necessária para preencher a cidade!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val themePrefs = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        val savedTheme = themePrefs.getInt("tema_preferido", AppCompatDelegate.MODE_NIGHT_YES)
        AppCompatDelegate.setDefaultNightMode(savedTheme)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        crashlytics.setUserId(auth.currentUser?.uid ?: "anonimo")
        crashlytics.log("MainActivity Iniciada")
        
        criarCanalNotificacao()
        pedirPermissaoNotificacao()

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
        btnTrocarTema = findViewById(R.id.btnTrocarTema)
        radioGroupVeiculo = findViewById(R.id.radioGroupVeiculo)
        containerViagens = findViewById(R.id.containerViagens)
        txtOlaUsuario = findViewById(R.id.txtOlaUsuario)
        txtViagensCount = findViewById(R.id.txtViagensCount)
        mainScrollView = findViewById(R.id.mainScrollView)
        bannerTreinamento = findViewById(R.id.bannerTreinamento)
        
        iconIda = findViewById(R.id.iconIda)
        iconVolta = findViewById(R.id.iconVolta)
        subLblFotoIda = findViewById(R.id.subLblFotoIda)
        subLblFotoVolta = findViewById(R.id.subLblFotoVolta)
        
        txtDashKmTotal = findViewById(R.id.txtDashKmTotal)
        txtDashCustoTotal = findViewById(R.id.txtDashCustoTotal)
        txtDashGastoCombustivel = findViewById(R.id.txtDashGastoCombustivel)
        txtDashFaltaOleo = findViewById(R.id.txtDashFaltaOleo)
        
        txtCountIda = findViewById(R.id.txtCountIda)
        txtCountVolta = findViewById(R.id.txtCountVolta)
        txtCountDespesa = findViewById(R.id.txtCountDespesa)
        
        btnGpsOrigem = findViewById(R.id.btnGpsOrigem)
        btnGpsDestino = findViewById(R.id.btnGpsDestino)

        // --- MELHORIA DE PRECISÃO (TOQUE AMPLO) ---
        fun scrollToView(view: View) {
            mainScrollView.postDelayed({
                val rect = Rect()
                view.getDrawingRect(rect)
                mainScrollView.offsetDescendantRectToMyCoords(view, rect)
                mainScrollView.smoothScrollTo(0, rect.top - 100)
            }, 300)
        }

        fun setupRowFocus(rowId: Int, target: View) {
            val row = findViewById<View>(rowId)
            row.setOnClickListener {
                target.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
                scrollToView(row)
            }
        }

        fun mostrarTimePicker(target: EditText) {
            val cal = Calendar.getInstance()
            val timeSetListener = TimePickerDialog.OnTimeSetListener { _, hour, minute ->
                target.setText(String.format(Locale.getDefault(), "%02d:%02d", hour, minute))
                salvarEstado()
            }
            TimePickerDialog(this, timeSetListener, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }
        
        setupRowFocus(R.id.row_data, editData)
        setupRowFocus(R.id.row_condutor, editCondutor)
        setupRowFocus(R.id.row_hSaida, editHoraSaida)
        setupRowFocus(R.id.row_hChegada, editHoraChegada)
        setupRowFocus(R.id.row_kmIni, editKmInicial)
        setupRowFocus(R.id.row_kmFin, editKmFinal)
        setupRowFocus(R.id.row_origem, editOrigem)
        setupRowFocus(R.id.row_destino, editDestino)
        setupRowFocus(R.id.row_obs, editObservacoes)

        editHoraSaida.setOnClickListener { mostrarTimePicker(editHoraSaida) }
        editHoraChegada.setOnClickListener { mostrarTimePicker(editHoraChegada) }
        
        findViewById<View>(R.id.row_gasto_combustivel).setOnClickListener {
            mostrarDialogoRapidoCombustivel()
        }

        findViewById<View>(R.id.btnClearDash).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Zerar Resumo?")
                .setMessage("Isso apagará o valor de combustível e também o total de KM rodados no mês de forma PERMANENTE (Celular e Nuvem). Deseja continuar?")
                .setPositiveButton("Zerar Tudo") { _, _ ->
                    val currentUser = auth.currentUser
                    
                    // Mostra um progresso para evitar fechar o app antes de terminar
                    val progressDialog = AlertDialog.Builder(this)
                        .setTitle("Limpando Dados...")
                        .setMessage("Sincronizando com a nuvem, por favor aguarde.")
                        .setCancelable(false)
                        .show()

            // 2. Limpa localmente
            try {
                val prefsVeiculo = getSharedPreferences("DadosVeiculo", Context.MODE_PRIVATE)
                prefsVeiculo.edit().remove("veiculo_gasto_combustivel").apply()
                
                val prefsApp = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
                val historicoKey = if (currentUser != null) "historico_local_${currentUser.uid}" else "historico_geral_local"
                val fuelKey = if (currentUser != null) "historico_combustivel_local_${currentUser.uid}" else "historico_combustivel_geral"
                prefsApp.edit().remove(historicoKey).remove(fuelKey).apply()
            } catch (e: Exception) { reportarErro(e, "Reset Local Dashboard") }

            // 2. Limpa na Nuvem (Firebase)
            if (currentUser != null) {
                val batch = db.batch()
                
                // Zera combustível no doc do veículo
                val vehicleRef = db.collection("veiculos").document(currentUser.uid)
                batch.update(vehicleRef, "combustivel", "0,00")
                
                // Busca e deleta viagens
                db.collection("viagens")
                    .whereEqualTo("tecnicoId", currentUser.uid)
                    .get()
                    .addOnSuccessListener { voyages ->
                        for (doc in voyages) batch.delete(doc.reference)
                        
                        // Busca e deleta combustivel
                        db.collection("combustivel")
                            .whereEqualTo("tecnicoId", currentUser.uid)
                            .get()
                            .addOnSuccessListener { fuels ->
                                for (fDoc in fuels) batch.delete(fDoc.reference)
                                
                                // Executa a limpeza pesada
                                batch.commit().addOnCompleteListener { task ->
                                    progressDialog.dismiss()
                                    if (task.isSuccessful) {
                                        atualizarDashboard()
                                        Toast.makeText(this, "Resumo mensal zerado definitivamente!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this, "Erro ao limpar nuvem. Verifique sua internet.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            .addOnFailureListener {
                                progressDialog.dismiss()
                                reportarErro(it as Exception, "Erro deletar combustivel nuvem")
                            }
                    }
                    .addOnFailureListener {
                        progressDialog.dismiss()
                        reportarErro(it as Exception, "Erro buscar viagens nuvem")
                    }
            } else {
                        progressDialog.dismiss()
                        atualizarDashboard()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

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
                scrollToView(view)
            }
            view.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    if (view.text.isEmpty()) view.showDropDown()
                    scrollToView(view)
                }
            }
        }

        // Recupera rascunho e estado salvo
        editData.setText(prefs.getString("rascunho_data", ""))
        editOrigem.setText(prefs.getString("rascunho_origem", ""))
        editDestino.setText(prefs.getString("rascunho_destino", ""))
        
        val horaAtual = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        editHoraSaida.setText(prefs.getString("rascunho_hSaida", horaAtual))
        
        val rascunhoKm = prefs.getString("rascunho_kmIni", "")
        if (rascunhoKm.isNullOrEmpty()) {
            val prefsVeiculo = getSharedPreferences("DadosVeiculo", Context.MODE_PRIVATE)
            editKmInicial.setText(prefsVeiculo.getString("veiculo_km_ini", ""))
        } else {
            editKmInicial.setText(rascunhoKm)
        }

        editObservacoes.setText(prefs.getString("rascunho_obs", ""))

        // --- BUSCA NOME DO TÉCNICO NO FIRESTORE ---
        val currentUser = auth.currentUser
        if (currentUser != null) {
            // Tenta buscar na coleção 'tecnicos'
            db.collection("tecnicos").document(currentUser.uid)
                .get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        // Dentro do addOnSuccessListener do db.collection("tecnicos")...
                        val nomeTecnico = document.getString("nome") ?: "Usuário"
                        editCondutor.setText(nomeTecnico)
                        txtOlaUsuario.text = "Olá, $nomeTecnico"
                        crashlytics.setCustomKey("nome_tecnico", nomeTecnico)

// NOVO: Verifica se mudou de usuário e limpa os dados se sim
                        val condutorSalvo = prefs.getString("rascunho_condutor", "")
                        if (!condutorSalvo.isNullOrEmpty() && condutorSalvo != nomeTecnico) {
                            // Usuário diferente — limpa tudo
                            listaDeViagens.clear()
                            listaFotosDespesas.clear()
                            listaDadosDespesas.clear()
                            fotoIdaPath = null
                            fotoVoltaPath = null
                            fotoIdaHora = null
                            fotoVoltaHora = null
                            prefs.edit()
                                .remove("lista_viagens")
                                .remove("listaFotosDespesas")
                                .remove("listaDadosDespesas")
                                .remove("fotoIdaPath")
                                .remove("fotoVoltaPath")
                                .apply()
                            atualizarListaVisual()
                            validarBotoes()
                        }

                        prefs.edit().putString("rascunho_condutor", nomeTecnico).apply()
                    } else {
                        // Se não achar em 'tecnicos', tenta em 'usuarios' (legado)
                        db.collection("usuarios").document(currentUser.uid).get()
                            .addOnSuccessListener { doc ->
                        if (doc != null && doc.exists()) {
                                    val nomeLegacy = doc.getString("nome") ?: "Usuário"
                                    editCondutor.setText(nomeLegacy)
                                    txtOlaUsuario.text = "Olá, $nomeLegacy"

                                    // Limpeza para usuário legado também
                                    val condutorSalvo = prefs.getString("rascunho_condutor", "")
                                    if (!condutorSalvo.isNullOrEmpty() && condutorSalvo != nomeLegacy) {
                                        listaDeViagens.clear()
                                        listaFotosDespesas.clear()
                                        listaDadosDespesas.clear()
                                        fotoIdaPath = null
                                        fotoVoltaPath = null
                                        fotoIdaHora = null
                                        fotoVoltaHora = null
                                        prefs.edit()
                                            .remove("lista_viagens")
                                            .remove("listaFotosDespesas")
                                            .remove("listaDadosDespesas")
                                            .remove("fotoIdaPath")
                                            .remove("fotoVoltaPath")
                                            .apply()
                                        atualizarListaVisual()
                                        validarBotoes()
                                    }

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
            btnFotoDespesa.alpha = 1.0f
        } else {
            btnFotoDespesa.alpha = 0.5f
        }
        
        if (fotoIdaPath != null) {
            btnFotoIda.alpha = 1.0f
            iconIda.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4DFF5722"))
            subLblFotoIda.text = "Foto anexada ✅"
            subLblFotoIda.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
            btnFotoVolta.isEnabled = true
        } else {
            btnFotoIda.alpha = 0.5f
        }
        if (fotoVoltaPath != null) {
            btnFotoVolta.alpha = 1.0f
            iconVolta.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4D3D7FFF"))
            subLblFotoVolta.text = "Foto anexada ✅"
            subLblFotoVolta.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
        } else {
            btnFotoVolta.alpha = 0.5f
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
                        if (obj.has("observacoes")) obj.getString("observacoes") else "",
                        if (obj.has("isEmpresa")) obj.getBoolean("isEmpresa") else false
                    ))
                }
            } catch (e: Exception) { 
                reportarErro(e, "Carregamento de Viagens")
                prefs.edit().remove("lista_viagens").apply()
            }
            atualizarListaVisual()
        }

        editHoraSaida.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (editHoraSaida.text.isEmpty()) {
                    editHoraSaida.setText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
                    salvarEstado()
                }
                scrollToView(editHoraSaida)
            }
        }

        editHoraChegada.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (editHoraChegada.text.isEmpty()) {
                    editHoraChegada.setText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
                    salvarEstado()
                }
                scrollToView(editHoraChegada)
            }
        }

        editData.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (editData.text.isEmpty()) {
                    editData.setText(SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date()))
                    salvarEstado()
                }
                scrollToView(editData)
            }
        }

        // Preenche ao clicar caso o campo esteja habilitado e vazio
        editData.setOnClickListener {
            if (editData.text.isEmpty()) {
                editData.setText(SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date()))
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

        // Ajuste de scroll automático ao focar nos campos restantes
        listOf(editKmInicial, editKmFinal, editCondutor, editObservacoes).forEach { view ->
            val existing = view.onFocusChangeListener
            view.setOnFocusChangeListener { v, hasFocus ->
                existing?.onFocusChange(v, hasFocus)
                if (hasFocus) scrollToView(v)
            }
        }

        btnGpsOrigem.setOnClickListener { preencherCidadeComGps(editOrigem) }
        btnGpsDestino.setOnClickListener { preencherCidadeComGps(editDestino) }

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

            // Validação de Tamanho
            if (origemTxt.length > 100 || destinoTxt.length > 100) {
                Toast.makeText(this, "Origem/Destino muito longos (máx 100)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (editObservacoes.text.length > 500) {
                Toast.makeText(this, "Observações muito longas (máx 500)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validação de Data
            try {
                val partes = dataTxt.split("/")
                if (partes.size == 3) {
                    val dia = partes[0].toInt()
                    val mes = partes[1].toInt()
                    if (dia > 31 || mes > 12) {
                        editData.error = "Data inválida"
                        return@setOnClickListener
                    }
                }
            } catch (e: Exception) { 
                editData.error = "Formato inválido"
                return@setOnClickListener 
            }

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

            val isEmpresaViagem = findViewById<RadioButton>(R.id.radioEmpresa).isChecked

            val v = Viagem(
                dataTxt, condutorTxt, origemTxt, destinoTxt,
                saidaTxt, chegadaTxt,
                kmI, kmF, kmTotal * 1.20, editObservacoes.text.toString(),
                isEmpresaViagem
            )

            listaDeViagens.add(v)
            
            Toast.makeText(this, "Viagem adicionada!", Toast.LENGTH_SHORT).show()

            editOrigem.text.clear()
            editDestino.text.clear()
            editObservacoes.text.clear()
            editHoraSaida.text.clear()
            editHoraChegada.text.clear()
            editKmInicial.setText(kmFTxt) // Preenche o inicial da próxima com o final desta
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

        btnTrocarTema.setOnClickListener {
            val isDark = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
            val newMode = if (isDark) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
            
            AppCompatDelegate.setDefaultNightMode(newMode)
            getSharedPreferences("DadosApp", Context.MODE_PRIVATE).edit {
                putInt("tema_preferido", newMode)
            }
        }

        btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Limpar tudo / Novo Dia?")
                .setMessage("Isso apagará as fotos e todos os registros da lista para iniciar um novo dia.")
                .setPositiveButton("Sim, Limpar") { _, _ ->
                    salvarCopiaUltimoLote() // SALVA ANTES DE LIMPAR

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
                    
                    // Mantém o KM Inicial baseado no último hodômetro registrado
                    val prefsVeiculo = getSharedPreferences("DadosVeiculo", Context.MODE_PRIVATE)
                    editKmInicial.setText(prefsVeiculo.getString("veiculo_km_ini", ""))
                    editKmFinal.text.clear()
                    
                    findViewById<RadioButton>(R.id.radioParticular).isChecked = true
                    
                    btnFotoIda.apply {
                        alpha = 0.5f
                    }
                    iconIda.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1AFFFFFF"))
                    subLblFotoIda.text = "Toque para capturar"
                    subLblFotoIda.setTextColor(ContextCompat.getColor(this, R.color.text_muted))

                    btnFotoVolta.apply {
                        alpha = 0.5f
                        isEnabled = false
                    }
                    iconVolta.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1AFFFFFF"))
                    subLblFotoVolta.text = "Toque para capturar"
                    subLblFotoVolta.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                    btnFotoDespesa.apply {
                        // text = "📸 ADICIONAR DESPESAS (RECIBOS)"
                        alpha = 0.5f
                        isEnabled = false
                    }
                    
                    salvarEstado()
                    atualizarListaVisual()
                    atualizarDashboard()
                    validarBotoes()
                    Toast.makeText(this, "Tudo limpo!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        
        validarBotoes() // Chama no início
        atualizarDashboard()
        sincronizarDadosUsuario()

        // Verifica se abriu através de uma notificação de erro para tentar reenviar
        if (intent.getBooleanExtra("RETRY_SYNC", false)) {
            reenviarUltimoLote()
        }
    }

    private fun sincronizarDadosUsuario() {
        val currentUser = auth.currentUser ?: return
        
        // 1. Sincroniza Dados do Veículo
        db.collection("veiculos").document(currentUser.uid).get()
            .addOnSuccessListener { doc ->
                if (doc != null && doc.exists()) {
                    val prefsV = getSharedPreferences("DadosVeiculo", Context.MODE_PRIVATE)
                    prefsV.edit().apply {
                        putString("veiculo_carro", doc.getString("carro"))
                        putString("veiculo_placa", doc.getString("placa"))
                        putString("veiculo_km_ini", doc.getString("kmIni"))
                        putString("veiculo_oleo", doc.getString("oleo"))
                        putString("veiculo_outros", doc.getString("outros"))
                        putString("veiculo_gasto_combustivel", doc.getString("combustivel"))
                        putString("veiculo_gasto_manutencao", doc.getString("manutencao"))
                        apply()
                    }
                    atualizarDashboard()
                }
            }

        // 2. Sincroniza Histórico de Viagens (para o Dashboard Mensal)
        db.collection("viagens").whereEqualTo("tecnicoId", currentUser.uid).get()
            .addOnSuccessListener { documents ->
                val array = JSONArray()
                if (!documents.isEmpty) {
                    for (doc in documents) {
                        val obj = JSONObject().apply {
                            put("data", doc.getString("data"))
                            put("condutor", doc.getString("condutor"))
                            put("origem", doc.getString("origem"))
                            put("destino", doc.getString("destino"))
                            put("hSaida", doc.getString("hSaida"))
                            put("hChegada", doc.getString("hChegada"))
                            put("kmIni", doc.getLong("kmIni")?.toInt())
                            put("kmFin", doc.getLong("kmFin")?.toInt())
                            put("custo", doc.getDouble("custo"))
                            put("observacoes", doc.getString("observacoes"))
                            put("isEmpresa", doc.getBoolean("isEmpresa") ?: false)
                        }
                        array.put(obj)
                    }
                }
                // Sempre atualiza (se estiver vazio na nuvem, limpa o local para sincronizar o reset)
                val prefsA = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
                prefsA.edit().putString("historico_local_${currentUser.uid}", array.toString()).apply()
                atualizarDashboard()
            }

        // 3. Sincroniza Histórico de Combustível
        db.collection("combustivel").whereEqualTo("tecnicoId", currentUser.uid).get()
            .addOnSuccessListener { documents ->
                val arrayFuel = JSONArray()
                if (!documents.isEmpty) {
                    for (doc in documents) {
                        val obj = JSONObject().apply {
                            put("data", doc.getString("data"))
                            put("valor", doc.getDouble("valor"))
                        }
                        arrayFuel.put(obj)
                    }
                }
                // Sempre atualiza
                val prefsA = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
                prefsA.edit().putString("historico_combustivel_local_${currentUser.uid}", arrayFuel.toString()).apply()
                atualizarDashboard()
            }
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

    private fun preencherCidadeComGps(target: AutoCompleteTextView) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            
            requestLocationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }

        Toast.makeText(this, "Buscando localização...", Toast.LENGTH_SHORT).show()
        
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                try {
                    val geocoder = Geocoder(this, Locale.getDefault())
                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val cidade = addresses[0].locality ?: addresses[0].subAdminArea ?: "Desconhecida"
                        target.setText(cidade)
                        salvarEstado()
                    } else {
                        Toast.makeText(this, "Não foi possível encontrar a cidade.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "Erro ao obter cidade: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Ative o GPS do seu aparelho!", Toast.LENGTH_LONG).show()
            }
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Falha no GPS: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun atualizarListaVisual() {
        containerViagens.removeAllViews()
        txtViagensCount.text = String.format(Locale.forLanguageTag("pt-BR"), "%d registros", listaDeViagens.size)
        
        listaDeViagens.forEachIndexed { index, viagem ->
            val view = layoutInflater.inflate(android.R.layout.simple_list_item_2, containerViagens, false)
            val text1 = view.findViewById<TextView>(android.R.id.text1)
            val text2 = view.findViewById<TextView>(android.R.id.text2)
            
            text1.text = String.format(Locale.forLanguageTag("pt-BR"), "%s > %s (%s)", viagem.origem, viagem.destino, viagem.data)
            text1.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            
            val kmTotal = viagem.kmFin - viagem.kmIni
            text2.text = String.format(Locale.forLanguageTag("pt-BR"), "Condutor: %s | KM: %d (I: %d F: %d) | R$ %.2f", 
                viagem.condutor, kmTotal, viagem.kmIni, viagem.kmFin, viagem.custo)
            text2.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            
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
        atualizarDashboard()
    }

    override fun onResume() {
        super.onResume()
        isAppForeground = true
        
        // Verifica Modo Treinamento
        val prefsApp = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        val isTreinamento = prefsApp.getBoolean("modo_treinamento", false)
        bannerTreinamento.visibility = if (isTreinamento) View.VISIBLE else View.GONE

        // Atualiza o KM Inicial se estiver vazio (ex: após configurar o veículo ou limpar dados)
        if (editKmInicial.text.isNullOrEmpty()) {
            val prefsVeiculo = getSharedPreferences("DadosVeiculo", Context.MODE_PRIVATE)
            val kmSalvo = prefsVeiculo.getString("veiculo_km_ini", "")
            if (!kmSalvo.isNullOrEmpty()) {
                editKmInicial.setText(kmSalvo)
            }
        }

        atualizarDashboard()
    }

    override fun onPause() {
        super.onPause()
        isAppForeground = false
    }

    private fun mostrarToastCustom(titulo: String, subtitulo: String, tipo: String) {
        if (!isAppForeground) return

        val snackbar = Snackbar.make(findViewById(android.R.id.content), "", 4000)
        val customView = layoutInflater.inflate(R.layout.layout_toast_custom, null)
        
        val snackbarView = snackbar.view
        snackbarView.setBackgroundColor(Color.TRANSPARENT)
        snackbarView.setPadding(0, 0, 0, 0)
        
        val icon = customView.findViewById<TextView>(R.id.toast_icon)
        val txtTitle = customView.findViewById<TextView>(R.id.toast_title)
        val txtSubtitle = customView.findViewById<TextView>(R.id.toast_subtitle)

        txtTitle.text = titulo
        txtSubtitle.text = subtitulo

        when (tipo.lowercase()) {
            "sucesso" -> {
                icon.text = "✅"
                txtTitle.setTextColor(Color.parseColor("#1B5E20"))
            }
            "erro" -> {
                icon.text = "❌"
                txtTitle.setTextColor(Color.parseColor("#B71C1C"))
            }
            "info" -> {
                icon.text = "ℹ️"
                txtTitle.setTextColor(Color.parseColor("#0D47A1"))
            }
        }

        (snackbarView as? ViewGroup)?.addView(customView, 0)

        // Posicionar no topo respeitando a status bar
        val params = snackbarView.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.TOP
        
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
        params.topMargin = statusBarHeight + (16 * resources.displayMetrics.density).toInt()
        
        snackbarView.layoutParams = params

        // Animações customizadas
        customView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.toast_slide_down_in))
        
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                // Animação de saída antes de remover (opcional, Snackbar tem a própria)
            }
        })

        customView.setOnClickListener { snackbar.dismiss() }
        
        snackbar.show()
    }

    private fun atualizarDashboard() {
        var totalKm = 0
        
        val prefs = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        val currentUser = auth.currentUser
        val historicoKey = if (currentUser != null) "historico_local_${currentUser.uid}" else "historico_geral_local"
        val historicoJson = prefs.getString(historicoKey, "[]")
        val currentMonthYear = SimpleDateFormat("MM/yyyy", Locale.getDefault()).format(Date())

        // Dados do Veículo (Dashboard)
        val prefsVeiculo = getSharedPreferences("DadosVeiculo", Context.MODE_PRIVATE)
        var kmAtualOdometer = prefsVeiculo.getString("veiculo_km_ini", "0")?.toIntOrNull() ?: 0

        try {
            val array = JSONArray(historicoJson)
            val viagensPorData = mutableMapOf<String, MutableList<JSONObject>>()
            
            // 1. Processa o histórico
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val data = obj.getString("data")
                val kmf = obj.getInt("kmFin")
                if (kmf > kmAtualOdometer) kmAtualOdometer = kmf

                val isEmpresaViagem = if (obj.has("isEmpresa")) obj.getBoolean("isEmpresa") else false
                if (data.endsWith(currentMonthYear) && !isEmpresaViagem) {
                    if (!viagensPorData.containsKey(data)) viagensPorData[data] = mutableListOf()
                    viagensPorData[data]?.add(obj)
                }
            }
            
            // 2. Adiciona as viagens que estão na lista da tela (evitando duplicar se já estiverem no histórico)
            listaDeViagens.forEach { v ->
                if (v.kmFin > kmAtualOdometer) kmAtualOdometer = v.kmFin
                if (v.data.endsWith(currentMonthYear) && !v.isEmpresa) {
                    // Verifica se essa viagem específica já não foi processada no histórico (pela data/km)
                    // Para simplificar e garantir precisão, o span diário cuidará disso
                    val obj = JSONObject().apply {
                        put("kmIni", v.kmIni); put("kmFin", v.kmFin)
                    }
                    if (!viagensPorData.containsKey(v.data)) viagensPorData[v.data] = mutableListOf()
                    viagensPorData[v.data]?.add(obj)
                }
            }

            // 3. Calcula KM total do mês usando span diário (Inclui Urbano)
            viagensPorData.forEach { (_, lista) ->
                val kmiPrimeiro = lista.minOf { it.getInt("kmIni") }
                val kmfUltimo = lista.maxOf { it.getInt("kmFin") }
                totalKm += (kmfUltimo - kmiPrimeiro)
            }
            
        } catch (e: Exception) { e.printStackTrace() }
        
        txtDashKmTotal.text = totalKm.toString()
        txtDashCustoTotal.text = String.format(Locale.forLanguageTag("pt-BR"), "R$ %.2f", totalKm * 1.20)

        // Sincroniza o KM Atual no banco do veículo (SÓ SE FOR MAIOR QUE O SALVO)
        val kmSalvoAtual = prefsVeiculo.getString("veiculo_km_ini", "0")?.toIntOrNull() ?: 0
        if (kmAtualOdometer > kmSalvoAtual) {
            prefsVeiculo.edit().putString("veiculo_km_ini", kmAtualOdometer.toString()).apply()
        }

        val gastoComb = prefsVeiculo.getString("veiculo_gasto_combustivel", "0,00")
        val kmTrocaOleo = prefsVeiculo.getString("veiculo_oleo", "0")?.toIntOrNull() ?: 0

        txtDashGastoCombustivel.text = if (gastoComb.isNullOrEmpty()) "R$ 0,00" else "R$ $gastoComb"
        
        // Cálculo do óleo: Diferença entre a meta e o KM mais alto registrado
        val faltaOleo = kmTrocaOleo - kmAtualOdometer
        txtDashFaltaOleo.text = if (faltaOleo > 0) "$faltaOleo KM" else "TROCAR!"
        
        // Cores de alerta
        if (faltaOleo <= 500) txtDashFaltaOleo.setTextColor(Color.RED) 
        else txtDashFaltaOleo.setTextColor(ContextCompat.getColor(this, R.color.accent_purple))

        // --- ATUALIZAÇÃO DOS CONTADORES DE FOTOS ---
        txtCountIda.apply {
            if (fotoIdaPath != null) {
                text = "(1)"
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        txtCountVolta.apply {
            if (fotoVoltaPath != null) {
                text = "(1)"
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        txtCountDespesa.apply {
            if (listaFotosDespesas.isNotEmpty()) {
                text = "(${listaFotosDespesas.size})"
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
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
                    put("isEmpresa", v.isEmpresa)
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

        // Aplicando Alertas Visuais
        if (erroKmAtual) {
            editKmFinal.error = "KM Final deve ser maior que o Inicial"
        } else {
            editKmFinal.error = null
        }

        if (erroKmHistorico) {
            val kmUltimo = ultimaViagem?.kmFin ?: 0
            editKmInicial.error = "KM Inicial não pode ser menor que o último registro ($kmUltimo)"
        } else {
            editKmInicial.error = null
        }

        val temErro = erroKmAtual || erroKmHistorico
        val temFotoIda = fotoIdaPath != null
        val temOrigem = editOrigem.text.toString().trim().isNotEmpty()
        val temDestino = editDestino.text.toString().trim().isNotEmpty()

        // PRIORIDADE: Só libera os campos se tiver foto de ida
        val layoutCampos = listOf(editData, editCondutor, editOrigem, editDestino, editHoraSaida, editHoraChegada, editKmInicial, editKmFinal, editObservacoes)
        layoutCampos.forEach { it.isEnabled = temFotoIda }
        
        btnFotoDespesa.isEnabled = temFotoIda
        btnAdicionar.isEnabled = !temErro && temFotoIda && temOrigem && temDestino

        // NOVA: Borda vermelha se tiver conteúdo (isSelected via XML Selector)
        btnFotoIda.isSelected = fotoIdaPath != null
        btnFotoVolta.isSelected = fotoVoltaPath != null
        btnFotoDespesa.isSelected = listaFotosDespesas.isNotEmpty()
        
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
        val dialog = BottomSheetDialog(this, R.style.CustomBottomSheetDialog)
        val menuView = layoutInflater.inflate(R.layout.layout_bottom_sheet_settings, null)
        dialog.setContentView(menuView)

        // Atualizar nome no menu
        val txtOla = menuView.findViewById<TextView>(R.id.txtOlaMenu)
        val nomeCondutor = editCondutor.text.toString().trim()
        if (nomeCondutor.isNotEmpty()) {
            txtOla.text = "Olá, $nomeCondutor"
        }

        val prefsApp = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        val isTreinamento = prefsApp.getBoolean("modo_treinamento", false)

        val itemTreinamento = menuView.findViewById<View>(R.id.itemMenuTreinamento)
        val titleT = menuView.findViewById<TextView>(R.id.titleTreinamento)
        
        if (isTreinamento) {
            titleT.text = "Modo Treinamento (ATIVO)"
            titleT.setTextColor(Color.parseColor("#FFC107"))
        }

        itemTreinamento.setOnClickListener {
            dialog.dismiss()
            ativarDesativarTreinamento(!isTreinamento)
        }

        // --- CLIQUES DOS ITENS ---

        // Dados do Veículo
        menuView.findViewById<View>(R.id.itemMenuVeiculo).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, VeiculoActivity::class.java))
        }
        menuView.findViewById<View>(R.id.itemMenuConsumo).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, ConsumoActivity::class.java))
        }

        // Viagens
        menuView.findViewById<View>(R.id.itemMenuViagens).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, HistoricoActivity::class.java))
        }
        menuView.findViewById<View>(R.id.itemMenuSinc).setOnClickListener {
            dialog.dismiss()
            sincronizarViagensComFirestore()
        }

        // ITEM NOVO: Ver Histórico de Sincronizações
        menuView.findViewById<View>(R.id.itemMenuLogs).setOnClickListener {
            dialog.dismiss()
            mostrarHistoricoLogs()
        }

        // ITEM NOVO: Exportar para PDF
        menuView.findViewById<View>(R.id.itemMenuExportarPdf).setOnClickListener {
            dialog.dismiss()
            
            val currentUser = auth.currentUser
            if (currentUser == null) {
                Toast.makeText(this, "Faça login para exportar o histórico!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Buscando histórico na nuvem...", Toast.LENGTH_SHORT).show()
                
                val query = db.collection("viagens").whereEqualTo("tecnicoId", currentUser.uid)

                query.get()
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
                                    doc.getString("observacoes") ?: "",
                                    doc.getBoolean("isEmpresa") ?: false
                                ))
                            }
                            gerarRelatorioCompleto(viagensHistorico, isExportacaoHistorico = true)
                        }
                    }
                    .addOnFailureListener { e ->
                        reportarErro(e, "Erro ao buscar histórico para PDF")
                    }
            }
        }

        // App
        menuView.findViewById<View>(R.id.itemMenuReenviar).setOnClickListener {
            dialog.dismiss()
            reenviarUltimoLote()
        }
        menuView.findViewById<View>(R.id.itemMenuSair).setOnClickListener {
            dialog.dismiss()
            // Lógica de logout já existente (copiada do antigo popup)
            listaDeViagens.clear()
            listaFotosDespesas.clear()
            listaDadosDespesas.clear()
            fotoIdaPath = null
            fotoVoltaPath = null
            fotoIdaHora = null
            fotoVoltaHora = null
            
            val prefs = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
            prefs.edit()
                .remove("lista_viagens")
                .remove("listaFotosDespesas")
                .remove("listaDadosDespesas")
                .remove("fotoIdaPath")
                .remove("fotoVoltaPath")
                .remove("rascunho_data")
                .remove("rascunho_origem")
                .remove("rascunho_destino")
                .remove("rascunho_kmIni")
                .remove("rascunho_obs")
                .remove("rascunho_condutor")
                .apply()
            
            val prefsVeiculo = getSharedPreferences("DadosVeiculo", Context.MODE_PRIVATE)
            prefsVeiculo.edit().clear().apply()

            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        // --- BLOQUEIOS MODO TREINAMENTO ---
        if (isTreinamento) {
            menuView.findViewById<View>(R.id.itemMenuReenviar).apply {
                isEnabled = false
                alpha = 0.5f
            }
        }

        dialog.show()
    }

    private fun ativarDesativarTreinamento(ativar: Boolean) {
        val titulo = if (ativar) "Ativar Modo Treinamento?" else "Desativar Modo Treinamento?"
        val msg = if (ativar) "Neste modo, os dados NÃO serão salvos na nuvem ou na planilha. Ideal para testes." 
                  else "Deseja voltar ao modo normal e salvar os dados na nuvem?"

        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(msg)
            .setPositiveButton(if (ativar) "Ativar" else "Desativar") { _, _ ->
                val prefs = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("modo_treinamento", ativar).apply()
                bannerTreinamento.visibility = if (ativar) View.VISIBLE else View.GONE
                
                if (!ativar) {
                    // Pergunta se quer limpar dados ao desativar
                    AlertDialog.Builder(this)
                        .setTitle("Limpar Dados de Teste?")
                        .setMessage("Deseja apagar os registros feitos durante o treinamento?")
                        .setPositiveButton("Sim, Limpar") { _, _ ->
                            listaDeViagens.clear()
                            listaFotosDespesas.clear()
                            listaDadosDespesas.clear()
                            fotoIdaPath = null
                            fotoVoltaPath = null
                            atualizarListaVisual()
                            salvarEstado()
                        }
                        .setNegativeButton("Manter", null)
                        .show()
                }
                
                Toast.makeText(this, if (ativar) "Modo Treinamento Ativado" else "Modo Normal Ativado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun sincronizarViagensComFirestore() {
        val prefsApp = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        val isTreinamento = prefsApp.getBoolean("modo_treinamento", false)

        if (isTreinamento) {
            Toast.makeText(this, "🎓 Modo treinamento - sincronização simulada", Toast.LENGTH_SHORT).show()
            salvarLog("Sucesso (TREINAMENTO)", "Sincronização simulada com sucesso", listaDeViagens.size, "TRAINING_MODE")
            return
        }

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
        
        // LOG INICIAL (PENDENTE)
        val condutor = if (copiaParaEnvio.isNotEmpty()) copiaParaEnvio[0].condutor.take(4).uppercase().replace(" ", "") else "USER"
        val loteId = "${condutor}_${System.currentTimeMillis()}"
        salvarLog("Pendente", "Iniciando sincronização...", copiaParaEnvio.size, loteId)
        
        salvarNaPlanilhaGoogle(copiaParaEnvio, copiaDespesas, loteId)

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
                "isEmpresa" to v.isEmpresa,
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

    private fun salvarNaPlanilhaGoogle(viagens: List<Viagem>, despesas: List<Despesa> = emptyList(), loteIdExterno: String? = null) {
        val prefsApp = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        val isTreinamento = prefsApp.getBoolean("modo_treinamento", false)

        if (isTreinamento) {
            Handler(Looper.getMainLooper()).postDelayed({
                mostrarNotificacaoStatus("Sincronização Simulada", "Dados de treinamento processados", true)
                mostrarToastCustom("Sucesso (TREINAMENTO)", "Os dados foram simulados com sucesso.", "OK")
            }, 1000)
            return
        }

        val scriptUrl = "https://script.google.com/macros/s/AKfycbz-vPT7DHjux2zBzc2PAo6a3O99rb4aE70xjdWVtcnNIbR00S1045Fa15lwe-J58Yhs/exec"
        
        if (scriptUrl.isEmpty() || scriptUrl.contains("SUA_URL")) return

        runOnUiThread { Toast.makeText(this, "📤 Enviando para a nuvem em segundo plano...", Toast.LENGTH_SHORT).show() }

        Thread {
            var sucesso = false
            var tentativa = 1
            val maxTentativas = 3
            val delays = listOf(3000L, 7000L, 15000L)
            
            val loteId = loteIdExterno ?: if (viagens.isNotEmpty()) {
                val condutor = viagens[0].condutor.take(4).uppercase().replace(" ", "")
                "${condutor}_${System.currentTimeMillis()}"
            } else System.currentTimeMillis().toString()

            while (tentativa <= maxTentativas && !sucesso) {
                // 3. SUPRESSÃO DE FALSO POSITIVO: Verifica se este lote já teve sucesso em tentativa anterior
                if (jaFoiSincronizado(loteId)) {
                    sucesso = true
                    break
                }

                val startTime = System.currentTimeMillis()
                var responseCode = -1

                try {
                    if (tentativa > 1) {
                        mostrarNotificacaoStatus("⏳ Tentativa $tentativa/$maxTentativas", "Reconectando ao servidor...", false)
                        Thread.sleep(delays[tentativa - 2])
                    }

                    val url = URL(scriptUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    
                    // 1. TIMEOUTS AJUSTADOS
                    conn.connectTimeout = 15000 
                    conn.readTimeout = 60000    

                    val jsonEnvio = JSONObject()
                    jsonEnvio.put("loteId", loteId)
                    
                    val jsonViagens = JSONArray()
                    viagens.forEach { v ->
                        val obj = JSONObject().apply {
                            put("data", v.data); put("condutor", v.condutor); put("origem", v.origem)
                            put("destino", v.destino); put("saida", v.hSaida); put("chegada", v.hChegada)
                            put("kmIni", v.kmIni); put("kmFin", v.kmFin); put("custo", v.custo)
                            put("obs", v.observacoes); put("isEmpresa", v.isEmpresa)
                        }
                        jsonViagens.put(obj)
                    }
                    jsonEnvio.put("viagens", jsonViagens)

                    val jsonDespesas = JSONArray()
                    despesas.forEach { d ->
                        val obj = JSONObject().apply { put("categoria", d.categoria); put("valor", d.valor) }
                        jsonDespesas.put(obj)
                    }
                    jsonEnvio.put("despesas", jsonDespesas)

                    conn.outputStream.use { os -> os.write(jsonEnvio.toString().toByteArray()) }

                    responseCode = conn.responseCode
                    val inputContent = if (responseCode in 200..399) {
                        conn.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Sem resposta"
                    }

                    val duration = System.currentTimeMillis() - startTime

                    // 2. PARSER FLEXÍVEL DE RESPOSTA (Case Insensitive)
                    val isSuccessContent = inputContent.contains("Sucesso", ignoreCase = true) || 
                                         inputContent.contains("OK", ignoreCase = true) ||
                                         inputContent.contains("success", ignoreCase = true)
                    
                    val isExplicitError = inputContent.contains("Erro:", ignoreCase = true) ||
                                        inputContent.contains("Error", ignoreCase = true) ||
                                        inputContent.contains("Exception", ignoreCase = true)

                    if (responseCode in 200..299 && isSuccessContent) {
                        sucesso = true
                        salvarLog("Sucesso", "Code: $responseCode | Time: ${duration}ms | Resp: $inputContent", viagens.size, loteId)
                        
                        if (isAppForeground) {
                            runOnUiThread {
                                mostrarToastCustom("Sucesso!", "${viagens.size} viagens enviadas", "sucesso")
                            }
                        } else {
                            mostrarNotificacaoStatus("✅ Sincronização concluída", "${viagens.size} viagens enviadas para a planilha", false)
                        }
                    } else {
                        tentativa++
                        if (tentativa > maxTentativas) {
                            salvarLog("Erro", "Code: $responseCode | Resp: $inputContent", viagens.size, loteId)
                            
                            if (isAppForeground) {
                                runOnUiThread {
                                    mostrarToastCustom("Falha!", "Não foi possível confirmar o envio", "erro")
                                }
                            } else {
                                mostrarNotificacaoStatus("❌ Falha na sincronização", "Não foi possível confirmar o envio. Toque para reenviar.", true)
                            }
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - startTime
                    val errorMsg = e.message ?: "Erro desconhecido"
                    
                    tentativa++
                    if (tentativa > maxTentativas) {
                        // 4. NOTIFICAÇÃO DE ERRO DE REDE TEMPORÁRIO (Possível parcial)
                        val isTimeout = e is SocketTimeoutException
                        val statusTitle = if (isTimeout) "⚠️ Sincronização incerta" else "❌ Falha na sincronização"
                        val statusDesc = if (isTimeout) "Verifique a planilha — possível sincronização parcial" else "Erro de conexão. Toque para reenviar."
                        
                        salvarLog("Erro", "Net Error: $errorMsg | Time: ${duration}ms", viagens.size, loteId)
                        
                        if (isAppForeground) {
                            runOnUiThread {
                                mostrarToastCustom(statusTitle, statusDesc, if (isTimeout) "info" else "erro")
                            }
                        } else {
                            mostrarNotificacaoStatus(statusTitle, statusDesc, true)
                        }
                    }
                }
            }
        }.start()
    }

    private fun jaFoiSincronizado(loteId: String): Boolean {
        return try {
            val logsJson = getSharedPreferences("DadosApp", Context.MODE_PRIVATE).getString("log_sincronizacoes", "[]")
            val array = JSONArray(logsJson)
            for (i in 0 until array.length()) {
                val log = array.getJSONObject(i)
                if (log.getString("loteId") == loteId && log.getString("status") == "Sucesso") {
                    return true
                }
            }
            false
        } catch (e: Exception) { false }
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
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_bottom_sheet_km, null)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.btnBsCamera).setOnClickListener {
            dialog.dismiss()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                pedindoFotoIda = isIda
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            } else {
                iniciarCapturaComRecorte(isIda, true)
            }
        }

        view.findViewById<View>(R.id.btnBsGaleria).setOnClickListener {
            dialog.dismiss()
            iniciarCapturaComRecorte(isIda, false)
        }

        view.findViewById<View>(R.id.btnBsCancelar).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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

    private fun criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Sincronização ControleKM"
            val descriptionText = "Status dos envios para a planilha do Google"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun pedirPermissaoNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun mostrarNotificacaoStatus(titulo: String, texto: String, isErro: Boolean) {
        // Se não tiver permissão e for Android 13+, não mostra nada (ou mantém AlertDialog legado)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            
            if (!isErro) {
                runOnUiThread { Toast.makeText(this, "✅ $titulo: $texto", Toast.LENGTH_LONG).show() }
            } else {
                runOnUiThread {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(titulo)
                        .setMessage(texto)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (isErro) putExtra("RETRY_SYNC", true)
        }
        
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(!isErro)
            .setOngoing(isErro)

        with(NotificationManagerCompat.from(this)) {
            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                notify(if (isErro) 1001 else 1002, builder.build())
            }
        }
    }

    private fun salvarLog(status: String, mensagem: String, qtd: Int, loteId: String) {
        val prefs = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        val logsJson = prefs.getString("log_sincronizacoes", "[]")
        try {
            val array = JSONArray(logsJson)
            val log = JSONObject().apply {
                put("timestamp", SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()))
                put("status", status)
                put("mensagem", mensagem)
                put("quantidadeViagens", qtd)
                put("loteId", loteId)
            }
            
            // Adiciona no início (mais recente)
            val novoArray = JSONArray()
            novoArray.put(log)
            for (i in 0 until minOf(array.length(), 19)) {
                novoArray.put(array.get(i))
            }
            
            prefs.edit().putString("log_sincronizacoes", novoArray.toString()).apply()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun salvarCopiaUltimoLote() {
        if (listaDeViagens.isEmpty()) return
        val prefs = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        
        val arrayV = JSONArray()
        listaDeViagens.forEach { v ->
            val obj = JSONObject().apply {
                put("data", v.data); put("condutor", v.condutor); put("origem", v.origem); put("destino", v.destino)
                put("hSaida", v.hSaida); put("hChegada", v.hChegada)
                put("kmIni", v.kmIni); put("kmFin", v.kmFin); put("custo", v.custo)
                put("observacoes", v.observacoes); put("isEmpresa", v.isEmpresa)
            }
            arrayV.put(obj)
        }
        
        val arrayD = JSONArray()
        listaDadosDespesas.forEach { d ->
            val obj = JSONObject().apply {
                put("path", d.path); put("categoria", d.categoria); put("valor", d.valor)
            }
            arrayD.put(obj)
        }
        
        prefs.edit()
            .putString("ultimo_lote_enviado", arrayV.toString())
            .putString("ultimo_despesas_lote", arrayD.toString())
            .apply()
    }

    private fun reenviarUltimoLote() {
        try {
            val prefs = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
            val viagensJson = prefs.getString("ultimo_lote_enviado", null)
            val despesasJson = prefs.getString("ultimo_despesas_lote", "[]")
            
            if (viagensJson == null) {
                Toast.makeText(this, "Nenhum lote anterior encontrado", Toast.LENGTH_SHORT).show()
                return
            }
            
            val viagens = mutableListOf<Viagem>()
            val arrayV = JSONArray(viagensJson)
            for (i in 0 until arrayV.length()) {
                val obj = arrayV.getJSONObject(i)
                viagens.add(Viagem(
                    obj.getString("data"), obj.getString("condutor"), obj.getString("origem"), obj.getString("destino"),
                    obj.getString("hSaida"), obj.getString("hChegada"), obj.getInt("kmIni"), obj.getInt("kmFin"),
                    obj.getDouble("custo"), obj.getString("observacoes"), obj.getBoolean("isEmpresa")
                ))
            }
            
            val despesas = mutableListOf<Despesa>()
            val arrayD = JSONArray(despesasJson)
            for (i in 0 until arrayD.length()) {
                val obj = arrayD.getJSONObject(i)
                despesas.add(Despesa(obj.getString("path"), obj.getString("categoria"), obj.getDouble("valor")))
            }
            
            Toast.makeText(this, "Reenviando lote anterior...", Toast.LENGTH_SHORT).show()
            salvarNaPlanilhaGoogle(viagens, despesas)
        } catch (e: Exception) {
            reportarErro(e, "Erro ao processar lote salvo")
        }
    }

    private fun mostrarHistoricoLogs() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_bottom_sheet_logs, null)
        dialog.setContentView(view)
        
        val container = view.findViewById<LinearLayout>(R.id.containerLogs)
        val btnLimpar = view.findViewById<ImageButton>(R.id.btnLimparLogs)
        
        btnLimpar.setOnClickListener {
            getSharedPreferences("DadosApp", Context.MODE_PRIVATE).edit().remove("log_sincronizacoes").apply()
            container.removeAllViews()
            Toast.makeText(this, "Logs apagados", Toast.LENGTH_SHORT).show()
        }
        
        val logsJson = getSharedPreferences("DadosApp", Context.MODE_PRIVATE).getString("log_sincronizacoes", "[]")
        val array = JSONArray(logsJson)
        
        for (i in 0 until array.length()) {
            val log = array.getJSONObject(i)
            val logView = layoutInflater.inflate(android.R.layout.simple_list_item_2, container, false)
            val text1 = logView.findViewById<TextView>(android.R.id.text1)
            val text2 = logView.findViewById<TextView>(android.R.id.text2)
            
            val status = log.getString("status")
            val icon = when(status) {
                "Sucesso" -> "✅"
                "Erro" -> "❌"
                else -> "⏳"
            }
            
            text1.text = "$icon ${log.getString("timestamp")} · ${log.getInt("quantidadeViagens")} viagens"
            text1.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            
            val msg = log.getString("mensagem")
            text2.text = if (msg.length > 50) msg.take(50) + "..." else msg
            text2.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            
            logView.setOnLongClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Sync Log", msg)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Mensagem copiada!", Toast.LENGTH_SHORT).show()
                true
            }
            
            container.addView(logView)
        }
        
        dialog.show()
    }

    private fun processarOcrHodometro(uri: Uri, isIda: Boolean) {
        try {
            val image = InputImage.fromFilePath(this, uri)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // Filtra apenas números com 3 ou mais dígitos (provável KM)
                    val numerosEncontrados = visionText.textBlocks
                        .flatMap { it.lines }
                        .flatMap { it.elements }
                        .map { it.text.replace(".", "").replace(",", "").replace(" ", "").trim() }
                        .filter { it.length >= 3 && it.all { char -> char.isDigit() } }
                    
                    if (numerosEncontrados.isNotEmpty()) {
                        // Pega o maior número encontrado (geralmente o KM total é o maior e mais isolado)
                        val kmLido = numerosEncontrados.maxOf { it.toInt() }
                        runOnUiThread {
                            if (isIda) editKmInicial.setText(kmLido.toString())
                            else editKmFinal.setText(kmLido.toString())
                            Toast.makeText(this, "KM Identificado: $kmLido", Toast.LENGTH_SHORT).show()
                            salvarEstado()
                        }
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
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

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private fun gerarRelatorioCompleto(viagens: List<Viagem>, isExportacaoHistorico: Boolean = false) {
        val document = PdfDocument()
        val paint = Paint()

        try {
            // Variáveis de controle para múltiplas páginas
            var currentPageNumber = 1
            var pageInfo = PdfDocument.PageInfo.Builder(595, 842, currentPageNumber).create()
            var page = document.startPage(pageInfo)
            var canvas = page.canvas

            // Função interna para desenhar o cabeçalho das colunas de forma limpa
            fun drawTableHeaders(canv: Canvas, y: Float) {
                paint.isFakeBoldText = true
                paint.textSize = 7.5f 
                paint.textAlign = Paint.Align.CENTER
                paint.color = Color.BLACK
                
                // Fundo cinza claro para o cabeçalho para destacar
                val bgPaint = Paint().apply { color = Color.LTGRAY; alpha = 30 }
                canv.drawRect(20f, y - 10f, 575f, y + 5f, bgPaint)

                canv.drawText("DATA", 35f, y, paint)
                canv.drawText("CONDUTOR", 75f, y, paint)
                canv.drawText("ORIGEM", 125f, y, paint)
                canv.drawText("DESTINO", 185f, y, paint)
                canv.drawText("OBS.", 255f, y, paint)
                canv.drawText("SAÍDA", 340f, y, paint)
                canv.drawText("CHEG.", 380f, y, paint)
                canv.drawText("KMI", 415f, y, paint)
                canv.drawText("KMF", 450f, y, paint)
                canv.drawText("URB.", 485f, y, paint)
                canv.drawText("TOTAL", 520f, y, paint)
                canv.drawText("CUSTO", 560f, y, paint)
                
                canv.drawLine(20f, y + 8f, 575f, y + 8f, paint)
            }

            // --- PÁGINA 1: Título e Fotos ---
            paint.isFakeBoldText = true
            paint.textSize = 16f
            paint.textAlign = Paint.Align.CENTER
            paint.color = Color.BLACK
            val tituloRelatorio = if (isExportacaoHistorico) "HISTÓRICO GERAL DE VIAGENS" else "RELATÓRIO GERAL DE VIAGENS"
            canvas.drawText(tituloRelatorio, 297f, 50f, paint)
            
            var yPos = 80f // Início das fotos

            if (!isExportacaoHistorico) {
                fun desenharFotoFinal(path: String?, x: Float, y: Float, label: String, hora: String?) {
                    path?.let {
                        try {
                            val bitmap = BitmapFactory.decodeFile(it) ?: return@let
                            
                            // Hora da foto
                            paint.textSize = 9f
                            paint.textAlign = Paint.Align.LEFT
                            paint.isFakeBoldText = false
                            canvas.drawText("Hora: ${hora ?: "--:--"}", x, y + 10f, paint)
                            
                            val drawWidth = 220f
                            val drawHeight = 73f 
                            canvas.drawBitmap(bitmap, null, RectF(x, y + 15f, x + drawWidth, y + 15f + drawHeight), Paint(Paint.FILTER_BITMAP_FLAG))
                            
                            // Legenda da foto
                            paint.textSize = 9f
                            paint.textAlign = Paint.Align.CENTER
                            paint.isFakeBoldText = true
                            canvas.drawText(label, x + (drawWidth / 2), y + drawHeight + 30f, paint)
                            bitmap.recycle()
                        } catch (e: Exception) { reportarErro(e, "Desenhar foto no PDF") }
                    }
                }

                desenharFotoFinal(fotoIdaPath, 60f, yPos, "KM INICIAL (IDA)", fotoIdaHora)
                desenharFotoFinal(fotoVoltaPath, 315f, yPos, "KM FINAL (VOLTA)", fotoVoltaHora)
                yPos += 140f // Aumentado para garantir espaço para a legenda e não bater no cabeçalho
            }

            // Agora desenha o cabeçalho na primeira página abaixo das fotos
            drawTableHeaders(canvas, yPos)
            yPos += 30f // Início da lista de viagens

            var kmSomaViagens = 0
            var kmSomaCidade = 0
            
            paint.isFakeBoldText = false
            paint.textSize = 7f

            for (index in viagens.indices) {
                val v = viagens[index]
                
                // VERIFICAÇÃO DE NOVA PÁGINA (Antes de desenhar a linha)
                if (yPos > 780f) {
                    document.finishPage(page)
                    currentPageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(595, 842, currentPageNumber).create()
                    page = document.startPage(pageInfo)
                    canvas = page.canvas
                    
                    // Cabeçalho de continuação
                    paint.isFakeBoldText = true
                    paint.textSize = 12f
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText(tituloRelatorio + " (Cont.)", 297f, 40f, paint)
                    
                    yPos = 70f
                    drawTableHeaders(canvas, yPos)
                    yPos += 30f
                    
                    paint.isFakeBoldText = false
                    paint.textSize = 7f
                }

                val nomeAbreviado = try {
                    val partes = v.condutor.trim().split(" ")
                    if (partes.size > 1) "${partes[0]} ${partes[1].take(1)}." else partes[0]
                } catch (e: Exception) { v.condutor }

                var kmUrbano = 0
                if (index < viagens.size - 1) {
                    val vProx = viagens[index + 1]
                    if (vProx.kmIni > v.kmFin) {
                        kmUrbano = vProx.kmIni - v.kmFin
                    }
                }

                canvas.drawText(v.data, 35f, yPos, paint)
                canvas.drawText(nomeAbreviado.take(12), 75f, yPos, paint)
                val offsetOrigem = desenharTextoComQuebra(canvas, v.origem, 125f, yPos, paint, 14)
                val offsetDestino = desenharTextoComQuebra(canvas, v.destino, 185f, yPos, paint, 14)
                val offsetObs = desenharTextoComQuebra(canvas, v.observacoes, 255f, yPos, paint, 16)
                
                canvas.drawText(v.hSaida, 340f, yPos, paint)
                canvas.drawText(v.hChegada, 380f, yPos, paint)
                canvas.drawText(v.kmIni.toString(), 415f, yPos, paint)
                canvas.drawText(v.kmFin.toString(), 450f, yPos, paint)
                canvas.drawText(kmUrbano.toString(), 485f, yPos, paint)
                
                val totalViagem = (v.kmFin - v.kmIni) + kmUrbano
                canvas.drawText(totalViagem.toString(), 520f, yPos, paint)
                
                // Se for particular (não empresa), desenha o custo
                if (!v.isEmpresa) {
                    val custoCalculado = totalViagem * 1.20
                    canvas.drawText(String.format(Locale.forLanguageTag("pt-BR"), "%.2f", custoCalculado), 560f, yPos, paint)
                    
                    kmSomaViagens += (v.kmFin - v.kmIni)
                    kmSomaCidade += kmUrbano
                }
                
                val saltoLinha = Math.max(offsetOrigem, Math.max(offsetDestino, offsetObs))
                yPos += 25f + saltoLinha
            }

            // Rodapé de Totais
            if (yPos > 750f) {
                document.finishPage(page)
                currentPageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(595, 842, currentPageNumber).create()
                page = document.startPage(pageInfo)
                canvas = page.canvas
                yPos = 60f
            }

            canvas.drawLine(20f, yPos, 575f, yPos, paint)
            yPos += 25f
            paint.isFakeBoldText = true
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = 10f
            
            var kmGeral = 0
            if (viagens.isNotEmpty()) {
                kmGeral = viagens.last().kmFin - viagens.first().kmIni
            }
            val custoGeral = kmGeral * 1.20

            val temParticular = viagens.any { !it.isEmpresa }
            val resumoTotal = if (temParticular) {
                "KM ESTRADA: $kmSomaViagens | KM CIDADE: $kmSomaCidade | TOTAL: $kmGeral KM | REEMBOLSO: R$ ${String.format(Locale.forLanguageTag("pt-BR"), "%.2f", kmGeral * 1.20)}"
            } else {
                "KM ESTRADA: $kmSomaViagens | KM CIDADE: $kmSomaCidade | TOTAL: $kmGeral KM"
            }
            canvas.drawText(resumoTotal, 570f, yPos, paint)

            // SEÇÃO DE DESPESAS (Recibos)
            if (listaFotosDespesas.isNotEmpty() && !isExportacaoHistorico) {
                yPos += 50f
                if (yPos > 600f) {
                    document.finishPage(page)
                    currentPageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(595, 842, currentPageNumber).create()
                    page = document.startPage(pageInfo)
                    canvas = page.canvas
                    yPos = 60f
                }

                paint.textAlign = Paint.Align.LEFT
                paint.textSize = 10f
                paint.isFakeBoldText = true
                canvas.drawText("COMPROVANTES DE DESPESAS:", 40f, yPos, paint)
                
                yPos += 20f
                var xPosRecibo = 40f
                val reciboWidth = 230f
                val reciboHeight = 230f
                
                for (path in listaFotosDespesas) {
                    if (yPos + reciboHeight > 800f) {
                        document.finishPage(page)
                        currentPageNumber++
                        pageInfo = PdfDocument.PageInfo.Builder(595, 842, currentPageNumber).create()
                        page = document.startPage(pageInfo)
                        canvas = page.canvas
                        yPos = 60f
                        xPosRecibo = 40f
                    }

                    try {
                        val bitmap = BitmapFactory.decodeFile(path) ?: continue
                        canvas.drawBitmap(bitmap, null, RectF(xPosRecibo, yPos, xPosRecibo + reciboWidth, yPos + reciboHeight), Paint(Paint.FILTER_BITMAP_FLAG))
                        bitmap.recycle()
                    } catch (e: Exception) { reportarErro(e, "Anexar recibo ao PDF") }
                    
                    xPosRecibo += reciboWidth + 20f
                    if (xPosRecibo + reciboWidth > 580f) {
                        xPosRecibo = 40f
                        yPos += reciboHeight + 20f
                    }
                }
            }

            // Assinatura final
            paint.textSize = 8f
            paint.isFakeBoldText = false
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("Aplicativo criado por Luiz Gustavo", 575f, 825f, paint)

            // Marca d'água treinamento
            val isTreinamento = getSharedPreferences("DadosApp", Context.MODE_PRIVATE).getBoolean("modo_treinamento", false)
            if (isTreinamento) {
                paint.color = Color.RED
                paint.textSize = 14f
                paint.alpha = 150
                paint.textAlign = Paint.Align.CENTER
                paint.isFakeBoldText = true
                canvas.drawText("VERSÃO DE TREINAMENTO", 300f, 825f, paint)
            }

            document.finishPage(page)

            val pasta = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (!pasta.exists()) pasta.mkdirs()
            val timestamp = SimpleDateFormat("ddMMyyyy_HHmm", Locale.getDefault()).format(Date())
            val arquivo = File(pasta, "Relatorio_KM_$timestamp.pdf")

            try {
                document.writeTo(FileOutputStream(arquivo))
                if (!isExportacaoHistorico) {
                    // Adiciona ao Histórico Permanente (que alimenta o Consumo Semanal)
                    val prefsLocal = getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
                    val currentUser = auth.currentUser
                    val historicoKey = if (currentUser != null) "historico_local_${currentUser.uid}" else "historico_geral_local"
                    val historicoAtual = prefsLocal.getString(historicoKey, "[]")
                    val arrayHistorico = JSONArray(historicoAtual)
                    
                    listaDeViagens.forEach { v ->
                        val obj = JSONObject().apply {
                            put("data", v.data); put("condutor", v.condutor); put("origem", v.origem); put("destino", v.destino)
                            put("hSaida", v.hSaida); put("hChegada", v.hChegada)
                            put("kmIni", v.kmIni); put("kmFin", v.kmFin); put("custo", v.custo)
                            put("observacoes", v.observacoes)
                        }
                        arrayHistorico.put(obj)
                    }
                    prefsLocal.edit().putString(historicoKey, arrayHistorico.toString()).apply()

                    salvarCopiaUltimoLote() // SALVA ANTES DE LIMPAR

                    listaDeViagens.clear()
                    fotoIdaPath = null
                    fotoVoltaPath = null
                    listaFotosDespesas.clear()
                }
                ultimoArquivoGerado = arquivo
                btnCompartilhar.visibility = View.VISIBLE
                validarBotoes()
                salvarEstado()
                compartilharArquivo(arquivo)
            } catch (e: Exception) {
                reportarErro(e, "Erro ao gravar PDF: ${e.message}")
            }
        } catch (e: Exception) {
            reportarErro(e, "Erro geral geração PDF: ${e.message}")
        } finally {
            try { document.close() } catch (e: Exception) { }
        }
    }
}
