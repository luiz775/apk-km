package com.luiz.controlekm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.delay

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private val SCRIPT_URL_PRINCIPAL = BuildConfig.SCRIPT_URL_PRINCIPAL
        private const val CHANNEL_ID = "sync_channel_background"
        private val lock = Any()
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        
        // 1. Evita conflito com adição recente na fila
        val ultimaAdicao = prefs.getLong("ultima_adicao_fila", 0)
        if (System.currentTimeMillis() - ultimaAdicao < 10000) {
            delay(5000)
        }

        synchronized(lock) {
            val filaJson = prefs.getString("fila_pendente", "[]") ?: "[]"
            val filaArray = JSONArray(filaJson)

            if (filaArray.length() == 0) {
                return Result.success()
            }

            salvarLogExterno("Info", "Worker iniciado - ${filaArray.length()} itens na fila", "SYSTEM")

            val novaFila = JSONArray()
            var sucessos = 0

            for (i in 0 until filaArray.length()) {
                val item = filaArray.getJSONObject(i)
                val loteId = item.getString("loteId")
                
                // 2. Verifica se o envio principal já cuidou desse lote
                if (foiEnviadoRecentemente(loteId)) {
                    salvarLogExterno("Info", "Lote $loteId já enviado anteriormente - removendo", loteId)
                    cancelarNotificacaoEspecifica(loteId)
                    continue
                }

                val viagensJson = item.getString("viagens")
                val despesasJson = item.getString("despesas")
                var tentativas = item.optInt("tentativas", 0)

                // ID fixo 1002 para "Em espera"
                mostrarNotificacaoStatus(1002, "⏳ Sincronização em espera", "Processando lote pendente...", false)

                salvarLogExterno("Info", "Tentando enviar lote $loteId", loteId)
                val sucesso = enviarRequest(SCRIPT_URL_PRINCIPAL, loteId, viagensJson, despesasJson)

                if (sucesso) {
                    sucessos++
                    
                    // Salva cópia para "Reenviar Último Lote" no app principal
                    prefs.edit()
                        .putString("ultimo_lote_enviado", viagensJson)
                        .putString("ultimo_despesas_lote", despesasJson)
                        .apply()
                        
                    registrarEnvioNoSet(loteId)
                    cancelarNotificacaoEspecifica(loteId)
                    cancelarNotificacaoEspecifica("1002")
                    
                    val qtdViagens = try { JSONArray(viagensJson).length() } catch (e: Exception) { 0 }
                    salvarLogExterno("Sucesso", "Lote $loteId enviado com sucesso", loteId, qtdViagens)
                } else {
                    tentativas++
                    if (tentativas < 20) {
                        item.put("tentativas", tentativas)
                        novaFila.put(item)
                    } else {
                        salvarLogExterno("Falha Definitiva", "Lote $loteId removido após 20 tentativas", loteId)
                        mostrarNotificacaoStatus(loteId.hashCode(), "❌ Falha na sincronização", "Não foi possível enviar o lote $loteId.", true)
                    }
                }
            }

            prefs.edit().putString("fila_pendente", novaFila.toString()).apply()
            salvarLogExterno("Info", "Worker finalizado - ${novaFila.length()} restantes", "SYSTEM")

            if (sucessos > 0) {
                mostrarNotificacaoFimGlobal("✅ Sincronização Concluída", "$sucessos lote(s) enviados.")
            }

            // Notifica UI
            val intent = android.content.Intent("FILA_ATUALIZADA")
            applicationContext.sendBroadcast(intent)
        }

        return Result.success()
    }

    private fun foiEnviadoRecentemente(loteId: String): Boolean {
        val prefs = applicationContext.getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        val enviados = prefs.getStringSet("lotes_ja_enviados", emptySet()) ?: emptySet()
        return enviados.contains(loteId)
    }

    private fun registrarEnvioNoSet(loteId: String) {
        val prefs = applicationContext.getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        val enviados = (prefs.getStringSet("lotes_ja_enviados", emptySet()) ?: emptySet()).toMutableSet()
        enviados.add(loteId)
        if (enviados.size > 100) {
            val list = enviados.toList().takeLast(100)
            prefs.edit().putStringSet("lotes_ja_enviados", list.toSet()).apply()
        } else {
            prefs.edit().putStringSet("lotes_ja_enviados", enviados).apply()
        }
    }

    private fun cancelarNotificacaoEspecifica(loteId: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            val id = loteId.toIntOrNull() ?: loteId.hashCode()
            notificationManager.cancel(id)
        } catch (e: Exception) { }
    }

    private fun enviarRequest(scriptUrl: String, loteId: String, viagensJson: String, despesasJson: String): Boolean {
        return try {
            val url = URL(scriptUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", "application/json")
            
            val jsonEnvio = JSONObject().apply {
                put("loteId", loteId)
                put("viagens", JSONArray(viagensJson))
                put("despesas", JSONArray(despesasJson))
                put("apiSecret", BuildConfig.SYNC_API_SECRET)
            }

            conn.outputStream.use { it.write(jsonEnvio.toString().toByteArray()) }
            val responseCode = conn.responseCode
            responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
        } catch (e: Exception) { false }
    }

    private fun salvarLogExterno(status: String, mensagem: String, loteId: String, qtd: Int = 0) {
        val prefs = applicationContext.getSharedPreferences("DadosApp", Context.MODE_PRIVATE)
        val logsJson = prefs.getString("log_sincronizacoes", "[]") ?: "[]"
        try {
            val array = JSONArray(logsJson)
            val log = JSONObject().apply {
                put("timestamp", SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()))
                put("status", status)
                put("mensagem", mensagem)
                put("quantidadeViagens", qtd)
                put("loteId", loteId)
                put("destino", "BACKGROUND")
            }
            val novoArray = JSONArray().apply {
                put(log)
                for (i in 0 until minOf(array.length(), 19)) put(array.get(i))
            }
            prefs.edit().putString("log_sincronizacoes", novoArray.toString()).apply()
        } catch (e: Exception) { }
    }

    private fun mostrarNotificacaoStatus(id: Int, titulo: String, texto: String, isErro: Boolean) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Status de Sincronização", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(if (isErro) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_upload)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (!isErro) builder.setTimeoutAfter(30000)
        notificationManager.notify(id, builder.build())
    }

    private fun mostrarNotificacaoFimGlobal(titulo: String, texto: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setAutoCancel(true)
        notificationManager.notify(1004, builder.build())
    }
}
