package com.xiaomi.xms.wearable.demo

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

object ResponseStorage {
    private const val PREF_NAME = "wearable_ai_prefs"
    private const val KEY_CHAT_HISTORY = "chat_history"
    private const val KEY_SYSTEM_LOGS = "system_logs"
    private const val KEY_LAST_RESPONSE = "last_response"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_API_KEY_2 = "api_key_2"
    private const val KEY_CUSTOM_PROMPT = "custom_prompt"

    private const val MAX_CHAT_CHARS = 15000
    private const val MAX_LOG_CHARS = 30000

    private const val PHOTOS_DIR = "saved_photos"
    private const val MAX_PHOTOS = 4

    // --- Система логов ---
    @Synchronized
    fun appendLog(context: Context, logMessage: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val currentLogs = prefs.getString(KEY_SYSTEM_LOGS, "") ?: ""

        val timeFormatter = SimpleDateFormat("dd HH:mm:ss", Locale.getDefault())
        val timeStamp = timeFormatter.format(Date())
        val formattedLog = "[$timeStamp] $logMessage\n"

        var updatedLogs = currentLogs + formattedLog

        // Если логи превышают 50000 символов, обрезаем старые записи с начала
        if (updatedLogs.length > MAX_LOG_CHARS) {
            updatedLogs = updatedLogs.takeLast(MAX_LOG_CHARS)
            val firstNewline = updatedLogs.indexOf('\n')
            if (firstNewline != -1) {
                updatedLogs = updatedLogs.substring(firstNewline + 1)
            }
        }

        prefs.edit().putString(KEY_SYSTEM_LOGS, updatedLogs).apply()
    }

    fun getSystemLogs(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SYSTEM_LOGS, "") ?: ""
    }

    fun clearSystemLogs(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SYSTEM_LOGS).apply()
    }

    // --- Управление фото ---
    fun saveApiKey2(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_API_KEY_2, key).apply()
    }

    fun getApiKey2(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY_2, "") ?: ""
    }

    private fun getPhotosDir(context: Context): File {
        val dir = File(context.filesDir, PHOTOS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Synchronized
    fun savePhotoWithLimit(context: Context, photoName: String, bitmap: Bitmap) {
        val dir = getPhotosDir(context)
        val targetFile = File(dir, photoName)

        if (targetFile.exists()) return

        val files = dir.listFiles()?.sortedBy { it.lastModified() }?.toMutableList() ?: mutableListOf()

        if (files.size >= MAX_PHOTOS) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            var currentHistory = prefs.getString(KEY_CHAT_HISTORY, "") ?: ""

            var deletedPhotoName: String? = null
            var deletedFile: File? = null

            for (file in files) {
                val tag = "{${file.name}}"
                if (currentHistory.contains(tag)) {
                    deletedPhotoName = file.name
                    deletedFile = file
                    break
                }
            }

            if (deletedFile == null && files.isNotEmpty()) {
                deletedFile = files.first()
                deletedPhotoName = deletedFile.name
            }

            if (deletedPhotoName != null) {
                val tagToReplace = "{$deletedPhotoName}"
                if (currentHistory.contains(tagToReplace)) {
                    currentHistory = currentHistory.replace(
                        tagToReplace,
                        "'фото удалено из за ограничений по колличеству'"
                    )
                    prefs.edit().putString(KEY_CHAT_HISTORY, currentHistory).apply()
                }
            }

            deletedFile?.delete()
            appendLog(context, "Удалено старое фото по лимиту: $deletedPhotoName")
        }

        try {
            val originalBytes = bitmap.byteCount
            val originalKb = originalBytes / 1024.0

            val maxSideThreshold = 1200f
            val maxSide = maxOf(bitmap.width, bitmap.height)

            val scale = if (maxSide > maxSideThreshold) {
                maxSideThreshold / maxSide
            } else {
                0.75f
            }

            val targetWidth = (bitmap.width * scale).toInt()
            val targetHeight = (bitmap.height * scale).toInt()

            val scaledBitmap = Bitmap.createScaledBitmap(
                bitmap,
                maxOf(targetWidth, 1),
                maxOf(targetHeight, 1),
                true
            )

            val stream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
            val bytes = stream.toByteArray()
            targetFile.writeBytes(bytes)

            val compressedKb = bytes.size / 1024.0

            appendLog(
                context,
                "Сохранено фото $photoName: до сжатия %.1f КБ, после %.1f КБ (размер %dx%d)".format(
                    originalKb,
                    compressedKb,
                    scaledBitmap.width,
                    scaledBitmap.height
                )
            )
        } catch (e: Exception) {
            appendLog(context, "Ошибка при сохранении фото $photoName: ${e.message}")
        }
    }

    fun extractPhotoNames(text: String): List<String> {
        val list = mutableListOf<String>()
        val matcher = Pattern.compile("\\{([^}]+)\\}").matcher(text)
        while (matcher.find()) {
            matcher.group(1)?.let { name ->
                list.add(name.trim())
            }
        }
        return list
    }

    fun getAllSavedPhotoFiles(context: Context): List<File> {
        return getPhotosDir(context).listFiles()?.toList() ?: emptyList()
    }

    fun getPhotoAsBase64(file: File): String? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun appendMessage(context: Context, sender: String, text: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val currentHistory = prefs.getString(KEY_CHAT_HISTORY, "") ?: ""

        val formattedMessage = "$sender: $text\n"
        var updatedHistory = currentHistory + formattedMessage

        while (updatedHistory.length > MAX_CHAT_CHARS) {
            val firstUserIndex = updatedHistory.indexOf("Часы:")
            val firstGeminiIndex = updatedHistory.indexOf("Gemini:")

            val nextBoundary = when {
                firstUserIndex != -1 && firstGeminiIndex != -1 -> minOf(firstUserIndex, firstGeminiIndex)
                firstUserIndex != -1 -> firstUserIndex
                else -> firstGeminiIndex
            }

            if (nextBoundary == -1) break

            val subString = updatedHistory.substring(nextBoundary + 1)
            val nextUserIndex = subString.indexOf("Часы:")
            val nextGeminiIndex = subString.indexOf("Gemini:")

            val secondBoundary = when {
                nextUserIndex != -1 && nextGeminiIndex != -1 -> minOf(nextUserIndex, nextGeminiIndex)
                nextUserIndex != -1 -> nextUserIndex
                else -> nextGeminiIndex
            }

            if (secondBoundary != -1) {
                updatedHistory = updatedHistory.substring(nextBoundary + 1 + secondBoundary)
            } else {
                updatedHistory = ""
                break
            }
        }

        prefs.edit().putString(KEY_CHAT_HISTORY, updatedHistory).apply()
    }

    fun getChatHistory(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CHAT_HISTORY, "") ?: ""
    }

    fun saveLastResponse(context: Context, response: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_RESPONSE, response).apply()
    }

    fun getLastResponse(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_RESPONSE, "Здесь пока нет истории.") ?: "Здесь пока нет истории."
    }

    fun clearHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_CHAT_HISTORY)
            .remove(KEY_LAST_RESPONSE)
            .apply()
    }

    fun saveApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun saveCustomPrompt(context: Context, prompt: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_PROMPT, prompt).apply()
    }

    fun getCustomPrompt(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val defaultPrompt = "Пиши ответ"
        return prefs.getString(KEY_CUSTOM_PROMPT, defaultPrompt) ?: defaultPrompt
    }

    // Внутри объекта ResponseStorage:

    @Synchronized
    fun clearAllData(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // 1. Очищаем историю и последний ответ
        prefs.edit()
            .remove(KEY_CHAT_HISTORY)
            .remove(KEY_LAST_RESPONSE)
            .apply()

        // 2. Очищаем все сохраненные фото
        val photosDir = getPhotosDir(context)
        photosDir.listFiles()?.forEach { file ->
            file.delete()
        }

        // 3. Очищаем логи и добавляем запись о дате очистки
        prefs.edit().remove(KEY_SYSTEM_LOGS).apply()
        appendLog(context, "История, кэш фото и системные логи были полностью очищены.")
    }
}