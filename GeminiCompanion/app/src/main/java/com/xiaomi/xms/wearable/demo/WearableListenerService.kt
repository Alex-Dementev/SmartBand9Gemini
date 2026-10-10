package com.xiaomi.xms.wearable.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.NodeApi
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class WearableListenerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val modelVersions = listOf(
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash-lite"
    )

    private var nodeApi: NodeApi? = null
    private var messageApi: MessageApi? = null
    private var curNode: Node? = null

    private val messageListener = OnMessageReceivedListener { _, message ->
        val incomingText = String(message).trim()
        val incomingSizeKb = message.size / 1024.0

        var textToCheck = incomingText
        try {
            val json = JSONObject(incomingText)
            if (json.has("text")) {
                textToCheck = json.getString("text").trim()
            }
        } catch (_: Exception) {}

        if (textToCheck.startsWith("T9_SUGGEST:")) {
            val currentPhrase = textToCheck.removePrefix("T9_SUGGEST:")

            serviceScope.launch {
                val (suggestion1, suggestion2) = T9Engine.getTwoSuggestions(applicationContext, currentPhrase)
                val responseText = "{suggestion}{$currentPhrase}$suggestion1,$suggestion2"

                curNode?.let { node ->
                    val responsePayload = JSONObject().put("text", responseText).toString()
                    val payloadBytes = responsePayload.toByteArray()
                    messageApi?.sendMessage(node.id, payloadBytes)
                }
            }
            return@OnMessageReceivedListener
        }

        ResponseStorage.appendLog(
            applicationContext,
            "Входящая команда/сообщение с часов: \"$textToCheck\" (объем: %.2f КБ)".format(incomingSizeKb)
        )

        if (textToCheck == "GET_LAST_RESPONSE") {
            val savedText = ResponseStorage.getLastResponse(applicationContext)
            curNode?.let { node ->
                val responsePayload = JSONObject().put("text", savedText).toString()
                val payloadBytes = responsePayload.toByteArray()
                messageApi?.sendMessage(node.id, payloadBytes)
                ResponseStorage.appendLog(
                    applicationContext,
                    "Отправлен последний ответ на часы (объем: %.2f КБ)".format(payloadBytes.size / 1024.0)
                )
            }
            return@OnMessageReceivedListener
        }

        if (textToCheck.startsWith("GET_GALLERY:")) {
            val rawNames = textToCheck.substringAfter("GET_GALLERY:").trim()
            val knownNames = rawNames.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val imageData = getNextCompressedImageBase64(knownNames)
            val savedText = if (imageData != null) {
                "GET_GALLERY: ${imageData.first};${imageData.second}"
            } else {
                "GET_GALLERY:"
            }

            curNode?.let { node ->
                val responsePayload = JSONObject().put("text", savedText).toString()
                val payloadBytes = responsePayload.toByteArray(Charsets.UTF_8)
                messageApi?.sendMessage(node.id, payloadBytes)

                val photoInfo = if (imageData != null) "фото \"${imageData.first}\"" else "нет новых фото"
                ResponseStorage.appendLog(
                    applicationContext,
                    "Отправка фото на часы ($photoInfo, объем пакета: %.2f КБ)".format(payloadBytes.size / 1024.0)
                )
            }
            return@OnMessageReceivedListener
        }

        if (textToCheck.contains("{")) {
            val photoNames = ResponseStorage.extractPhotoNames(textToCheck)
            for (photoName in photoNames) {
                val bitmap = findBitmapInMediaStore(photoName)
                if (bitmap != null) {
                    ResponseStorage.savePhotoWithLimit(applicationContext, photoName, bitmap)
                } else {
                    ResponseStorage.appendLog(applicationContext, "Не найдено фото в MediaStore для тега: $photoName")
                }
            }
        }

        ResponseStorage.appendMessage(applicationContext, "Часы", textToCheck)

        serviceScope.launch {
            val key1 = ResponseStorage.getApiKey(applicationContext)
            val key2 = ResponseStorage.getApiKey2(applicationContext)

            val keysList = mutableListOf<Pair<String, String>>()
            if (key1.isNotBlank()) keysList.add(Pair("Ключ 1", key1))
            if (key2.isNotBlank()) keysList.add(Pair("Ключ 2", key2))

            if (keysList.isEmpty()) {
                val errText = "[Ошибка: API-ключи не заданы в SharedPreferences]"
                ResponseStorage.appendLog(applicationContext, errText)

                curNode?.let { node ->
                    val responsePayload = JSONObject().put("text", errText).toString()
                    messageApi?.sendMessage(node.id, responsePayload.toByteArray())
                }
                return@launch
            }

            var geminiResponse: String? = null
            val errorsList = mutableListOf<String>()

            val customPrompt = ResponseStorage.getCustomPrompt(applicationContext)
            val history = ResponseStorage.getChatHistory(applicationContext)

            modelLoop@ for ((index, model) in modelVersions.withIndex()) {
                for ((keyName, currentKey) in keysList) {
                    try {
                        geminiResponse = callGeminiApi(
                            historyText = history,
                            promptText = customPrompt,
                            userMessage = textToCheck,
                            modelVersion = model,
                            apiKey = currentKey
                        )
                        break@modelLoop
                    } catch (e: Exception) {
                        val errorDetails = "[$keyName] Модель $model: ${e.message}"
                        errorsList.add(errorDetails)
                        ResponseStorage.appendLog(applicationContext, "Ошибка запроса в Gemini: $errorDetails")
                    }
                }

                if (index < modelVersions.lastIndex) {
                    delay(3000)
                }
            }

            if (geminiResponse != null) {
                ResponseStorage.appendMessage(applicationContext, "Gemini", geminiResponse)
                ResponseStorage.saveLastResponse(applicationContext, geminiResponse)

                curNode?.let { node ->
                    val responsePayload = JSONObject().put("text", geminiResponse).toString()
                    val payloadBytes = responsePayload.toByteArray()
                    messageApi?.sendMessage(node.id, payloadBytes)
                    ResponseStorage.appendLog(
                        applicationContext,
                        "Ответ от Gemini успешно доставлен на часы (объем: %.2f КБ)".format(payloadBytes.size / 1024.0)
                    )
                }
            } else {
                val failureReport = "[Сбой Gemini (все модели и ключи недоступны)]:\n" + errorsList.joinToString("\n")
                ResponseStorage.appendMessage(applicationContext, "Система", failureReport)
                ResponseStorage.saveLastResponse(applicationContext, failureReport)

                curNode?.let { node ->
                    val responsePayload = JSONObject().put("text", failureReport).toString()
                    messageApi?.sendMessage(node.id, responsePayload.toByteArray())
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        nodeApi = Wearable.getNodeApi(applicationContext)
        messageApi = Wearable.getMessageApi(applicationContext)

        T9Engine.init(applicationContext)

        startForeground(NOTIFICATION_ID, createNotification())
        initAndRegisterListener()
        ResponseStorage.appendLog(applicationContext, "Сервис WearableListenerService успешно запущен.")
    }

    private fun initAndRegisterListener() {
        serviceScope.launch {
            while (isActive) {
                try {
                    nodeApi?.connectedNodes?.addOnSuccessListener { nodes ->
                        if (nodes.isNotEmpty()) {
                            val activeNode = nodes[0]
                            if (curNode?.id != activeNode.id) {
                                curNode?.let { oldNode ->
                                    messageApi?.removeListener(oldNode.id)
                                }
                                curNode = activeNode
                                messageApi?.addListener(activeNode.id, messageListener)
                                ResponseStorage.appendLog(applicationContext, "Подключено устройство: ${activeNode.name} (${activeNode.id})")
                            }
                        } else {
                            if (curNode != null) {
                                ResponseStorage.appendLog(applicationContext, "Устройство отключено.")
                            }
                            curNode?.let { oldNode ->
                                messageApi?.removeListener(oldNode.id)
                            }
                            curNode = null
                        }
                    }
                } catch (_: Exception) {}

                delay(5000)
            }
        }
    }

    private fun findBitmapInMediaStore(targetPhotoName: String): android.graphics.Bitmap? {
        val projection = arrayOf(
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            android.provider.MediaStore.Images.Media._ID
        )
        val sortOrder = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"

        val cursor = contentResolver.query(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )

        cursor?.use { c ->
            val nameColumn = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
            val idColumn = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)

            while (c.moveToNext()) {
                val rawName = c.getString(nameColumn) ?: ""
                val digitsOnly = rawName.filter { it.isDigit() }

                val targetDigits = targetPhotoName.filter { it.isDigit() }
                if (digitsOnly.isNotEmpty() && digitsOnly == targetDigits) {
                    val id = c.getLong(idColumn)
                    val contentUri = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    return try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            android.graphics.ImageDecoder.decodeBitmap(
                                android.graphics.ImageDecoder.createSource(contentResolver, contentUri)
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            android.provider.MediaStore.Images.Media.getBitmap(contentResolver, contentUri)
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
        return null
    }

    private suspend fun callGeminiApi(
        historyText: String,
        promptText: String,
        userMessage: String,
        modelVersion: String,
        apiKey: String
    ): String = withContext(Dispatchers.IO) {
        val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$modelVersion:generateContent?key=$apiKey"
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.doOutput = true

        val partsArray = JSONArray()

        val systemInstruction = """
        Ты — ИИ-ассистент, работающий на умных часах.
        ВАЖНЫЕ ПРАВИЛА:
        1. Все прикрепленные фотографии передаются тебе напрямую в формате системных вложений inline_data.
        2. Когда в истории диалога или запросе пользователя упоминается имя файла в фигурных скобках {имя_файла.jpg}, относи этот запрос строго к соответствующему изображению.
        3. Никогда не пиши, что ты "не видишь картинки", "не получаешь Base64", "файлы переданы текстом". Изображения перед тобой — сразу анализируй их пиксельное содержимое.
        4. Отвечай кратко, чётко и емко, так как ответ будет читаться с маленького экрана часов.
        5. Выполняй все инструкции пользователя строго по порядку. Даже если кажется что они не поместятся на экран.
        6. Не используй Markdown-разметку, JSON-структуры, смайлы и спецсимволы.
        7. До и после ответа добавь по одной пустой строке чтобы ответ не обрезался.
        8. Принимай ответ как продолжение диалога.
        ИЗОБРАЖЕНИЯ (VISION & OCR FIRST)
        1. OCR ПРЕВЫШЕ ВСЕГО: Если на фото есть текст или мелкие детали, СНАЧАЛА полностью прочитай все надписи и мелкие детали, а затем делай выводы.
        2. БЕЗ ДОГАДОК: Не угадывай — опирайся строго на точные маркеры.
        """.trimIndent()

        partsArray.put(JSONObject().apply {
            put("text", "СИСТЕМНАЯ ИНСТРУКЦИЯ:\n$systemInstruction\n---")
        })

        val savedPhotoFiles = ResponseStorage.getAllSavedPhotoFiles(applicationContext)
        for (photoFile in savedPhotoFiles) {
            val base64Data = ResponseStorage.getPhotoAsBase64(photoFile)
            if (base64Data != null) {
                partsArray.put(JSONObject().apply {
                    put("text", "Изображение {${photoFile.name}}:")
                })
                partsArray.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", base64Data)
                    })
                })
            }
        }

        val fullTextPrompt = "История диалога:\n$historyText\n\nДополнительные инструкции пользователя: $promptText\n\nТекущее сообщение с часов: $userMessage"
        partsArray.put(JSONObject().apply {
            put("text", fullTextPrompt)
        })

        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", partsArray)
                })
            })
        }

        val input = jsonBody.toString().toByteArray(Charsets.UTF_8)
        val requestSizeMb = input.size / (1024.0 * 1024.0)

        connection.outputStream.use { os ->
            os.write(input, 0, input.size)
        }

        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            ResponseStorage.appendLog(
                applicationContext,
                "Отправлен пакет в Gemini (модель: $modelVersion, объем: %.3f MБ). Сервер подтвердил получение (HTTP 200)."
                    .format(requestSizeMb)
            )

            val responseString = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonResponse = JSONObject(responseString)
            val candidates = jsonResponse.getJSONArray("candidates")
            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.getJSONObject("content")
            val parts = content.getJSONArray("parts")
            return@withContext parts.getJSONObject(0).getString("text")
        } else {
            val errorString = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
            throw RuntimeException("HTTP Error: ${connection.responseCode}, $errorString")
        }
    }

    private fun createNotification(): Notification {
        val channelId = "wearable_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Wearable Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("GeminiCompanion")
            .setContentText("Прослушивание часов активно.")
            .setSmallIcon(R.drawable.ic_launcher_foregraund)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        curNode?.let { node ->
            messageApi?.removeListener(node.id)
        }
        serviceScope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }

    private fun getNextCompressedImageBase64(knownNames: List<String>): Pair<String, String>? {
        val projection = arrayOf(
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            android.provider.MediaStore.Images.Media._ID
        )
        val sortOrder = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"

        val cursor = contentResolver.query(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )

        cursor?.use { c ->
            val nameColumn = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
            val idColumn = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)

            while (c.moveToNext()) {
                val rawName = c.getString(nameColumn) ?: ""
                val digitsOnly = rawName.filter { it.isDigit() }

                val safeName = if (digitsOnly.isNotEmpty()) {
                    "$digitsOnly.jpg"
                } else {
                    "${System.currentTimeMillis()}.jpg"
                }

                if (safeName !in knownNames) {
                    val id = c.getLong(idColumn)
                    val contentUri = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    try {
                        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            android.graphics.ImageDecoder.decodeBitmap(
                                android.graphics.ImageDecoder.createSource(contentResolver, contentUri)
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            android.provider.MediaStore.Images.Media.getBitmap(contentResolver, contentUri)
                        }

                        val minDim = Math.min(bitmap.width, bitmap.height)
                        val cropX = (bitmap.width - minDim) / 2
                        val cropY = (bitmap.height - minDim) / 2
                        val croppedBitmap = android.graphics.Bitmap.createBitmap(bitmap, cropX, cropY, minDim, minDim)

                        val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(croppedBitmap, 138, 138, true)

                        val byteArrayOutputStream = java.io.ByteArrayOutputStream()
                        scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 25, byteArrayOutputStream)
                        val imageBytes = byteArrayOutputStream.toByteArray()

                        val base64String = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)

                        return Pair(safeName, base64String)
                    } catch (_: Exception) {}
                }
            }
        }
        return null
    }

    object T9Engine {
        private data class KeyCoord(val r: Double, val c: Double, val lang: Int)
        private data class Candidate(val word: String, val score: Double)

        private val keyCoords = mapOf(
            '-' to KeyCoord(0.0, 0.0, 1), 'я' to KeyCoord(1.2, 0.0, 1), 'ч' to KeyCoord(2.4, 0.0, 1),
            'с' to KeyCoord(3.6, 0.0, 1), 'м' to KeyCoord(4.8, 0.0, 1), 'и' to KeyCoord(6.0, 0.0, 1),
            'т' to KeyCoord(7.2, 0.0, 1), 'ь' to KeyCoord(8.4, 0.0, 1), 'б' to KeyCoord(9.6, 0.0, 1),
            'ю' to KeyCoord(10.8, 0.0, 1), '!' to KeyCoord(12.0, 0.0, 1), '?' to KeyCoord(13.2, 0.0, 1),

            'ф' to KeyCoord(0.0, 1.7, 1), 'ы' to KeyCoord(1.2, 1.7, 1), 'в' to KeyCoord(2.4, 1.7, 1),
            'а' to KeyCoord(3.6, 1.7, 1), 'п' to KeyCoord(4.8, 1.7, 1), 'р' to KeyCoord(6.0, 1.7, 1),
            'о' to KeyCoord(7.2, 1.7, 1), 'л' to KeyCoord(8.4, 1.7, 1), 'д' to KeyCoord(9.6, 1.7, 1),
            'ж' to KeyCoord(10.8, 1.7, 1), 'э' to KeyCoord(12.0, 1.7, 1), '.' to KeyCoord(13.2, 1.7, 1),

            'й' to KeyCoord(0.0, 3.4, 1), 'ц' to KeyCoord(1.2, 3.4, 1), 'у' to KeyCoord(2.4, 3.4, 1),
            'к' to KeyCoord(3.6, 3.4, 1), 'е' to KeyCoord(4.8, 3.4, 1), 'н' to KeyCoord(6.0, 3.4, 1),
            'г' to KeyCoord(7.2, 3.4, 1), 'ш' to KeyCoord(8.4, 3.4, 1), 'щ' to KeyCoord(9.6, 3.4, 1),
            'з' to KeyCoord(10.8, 3.4, 1), 'х' to KeyCoord(12.0, 3.4, 1), ',' to KeyCoord(13.2, 3.4, 1),

            'z' to KeyCoord(1.2, 0.0, 2), 'x' to KeyCoord(2.4, 0.0, 2), 'c' to KeyCoord(3.6, 0.0, 2),
            'v' to KeyCoord(4.8, 0.0, 2), 'b' to KeyCoord(6.0, 0.0, 2), 'n' to KeyCoord(7.2, 0.0, 2),
            'm' to KeyCoord(8.4, 0.0, 2),

            'a' to KeyCoord(0.0, 1.7, 2), 's' to KeyCoord(1.2, 1.7, 2), 'd' to KeyCoord(2.4, 1.7, 2),
            'f' to KeyCoord(3.6, 1.7, 2), 'g' to KeyCoord(4.8, 1.7, 2), 'h' to KeyCoord(6.0, 1.7, 2),
            'j' to KeyCoord(7.2, 1.7, 2), 'k' to KeyCoord(8.4, 1.7, 2), 'l' to KeyCoord(9.6, 1.7, 2),

            'q' to KeyCoord(0.0, 3.4, 2), 'w' to KeyCoord(1.2, 3.4, 2), 'e' to KeyCoord(2.4, 3.4, 2),
            'r' to KeyCoord(3.6, 3.4, 2), 't' to KeyCoord(4.8, 3.4, 2), 'y' to KeyCoord(6.0, 3.4, 2),
            'u' to KeyCoord(7.2, 3.4, 2), 'i' to KeyCoord(8.4, 3.4, 2), 'o' to KeyCoord(9.6, 3.4, 2),
            'p' to KeyCoord(10.8, 3.4, 2),

            '1' to KeyCoord(0.0, 0.0, 3), '4' to KeyCoord(1.2, 0.0, 3), '7' to KeyCoord(2.4, 0.0, 3),
            '0' to KeyCoord(3.6, 0.0, 3), '*' to KeyCoord(4.8, 0.0, 3), '(' to KeyCoord(6.0, 0.0, 3),
            '[' to KeyCoord(7.2, 0.0, 3), '<' to KeyCoord(8.4, 0.0, 3), '{' to KeyCoord(9.6, 0.0, 3),

            '2' to KeyCoord(0.0, 1.7, 3), '5' to KeyCoord(1.2, 1.7, 3), '8' to KeyCoord(2.4, 1.7, 3),
            '+' to KeyCoord(3.6, 1.7, 3), ')' to KeyCoord(6.0, 1.7, 3), ']' to KeyCoord(7.2, 1.7, 3),
            '>' to KeyCoord(8.4, 1.7, 3), '}' to KeyCoord(9.6, 1.7, 3),

            '3' to KeyCoord(0.0, 3.4, 3), '6' to KeyCoord(1.2, 3.4, 3), '9' to KeyCoord(2.4, 3.4, 3),
            '/' to KeyCoord(6.0, 3.4, 3), '%' to KeyCoord(7.2, 3.4, 3), '=' to KeyCoord(8.4, 3.4, 3)
        )

        private var dictionary: List<String> = emptyList()

        fun init(context: Context) {
            if (dictionary.isNotEmpty()) return
            try {
                dictionary = context.assets.open("dictionary")
                    .bufferedReader(Charsets.UTF_8)
                    .useLines { lines -> lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toList() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        suspend fun getTwoSuggestions(context: Context, phrase: String): Pair<String, String> = withContext(Dispatchers.Default) {
            if (dictionary.isEmpty()) init(context)
            if (phrase.isBlank() || dictionary.isEmpty()) return@withContext Pair("", "")

            val cleanInput = phrase.trim().split("\\s+".toRegex()).lastOrNull()?.lowercase() ?: ""
            if (cleanInput.isEmpty()) return@withContext Pair("", "")

            val inputLen = cleanInput.length
            val firstChar = cleanInput.first()
            val firstKey = keyCoords[firstChar]

            val chunkSize = (dictionary.size + 3) / 4
            val chunks = dictionary.chunked(chunkSize)

            val deferredResults = chunks.map { chunk ->
                async {
                    processChunk(chunk, cleanInput, inputLen, firstKey)
                }
            }

            val allCandidates = deferredResults.awaitAll().flatten()

            val topWords = allCandidates
                .filter { it.word != cleanInput }
                .sortedByDescending { it.score }
                .distinctBy { it.word }
                .take(2)

            val w1 = topWords.getOrNull(0)?.word ?: ""
            val w2 = topWords.getOrNull(1)?.word ?: ""

            return@withContext Pair(w1, w2)
        }

        private fun processChunk(
            chunk: List<String>,
            cleanInput: String,
            inputLen: Int,
            firstKey: KeyCoord?
        ): List<Candidate> {
            val candidates = ArrayList<Candidate>()

            for (dictWord in chunk) {
                var score = 0.0
                val dictLen = dictWord.length

                if (abs(dictLen - inputLen) > 5) {
                    score -= 30
                    continue
                }

                val dictFirstChar = dictWord.firstOrNull() ?: continue
                val dictFirstKey = keyCoords[dictFirstChar]

                if (firstKey != null && dictFirstKey != null) {
                    if (firstKey.lang != dictFirstKey.lang) {
                        score -= 150
                        continue
                    }

                    val firstDist = abs(firstKey.r - dictFirstKey.r) + abs(firstKey.c - dictFirstKey.c)
                    if (firstDist > 3) {
                        score -= 30
                        continue
                    }
                }

                var lastIndex = -1

                for (j in cleanInput.indices) {
                    val inputChar = cleanInput[j]
                    val p1 = keyCoords[inputChar] ?: continue

                    for (k in (lastIndex + 1) until dictLen) {
                        val targetChar = dictWord[k]
                        val p2 = keyCoords[targetChar]

                        if (p2 != null) {
                            if (p1.lang != p2.lang) continue

                            val distance = abs(p1.r - p2.r) + abs(p1.c - p2.c)
                            if (distance <= 1.2) {
                                val skipped = if (lastIndex == -1) k else (k - lastIndex - 1)
                                if (skipped > 2) {
                                    score -= (skipped - 2) * 0.3
                                }
                            }

                            if (distance == 0.0) {
                                score += 1.0
                                if (k == 0) score += 1.0
                                lastIndex = k
                                break
                            } else if (distance == 1.2) {
                                score += 0.6
                                if (k == 0) score += 0.4
                                lastIndex = k
                                break
                            }
                        }
                    }
                }

                if (0 - cleanInput.length + dictWord.length < 0)
                    score -= (cleanInput.length - dictWord.length) * 2
                else if (cleanInput.length == dictWord.length && cleanInput.length <= 2)
                    score += 2

                if (score >= -80) {
                    candidates.add(Candidate(dictWord, score))
                }
            }

            return candidates
        }
    }
}