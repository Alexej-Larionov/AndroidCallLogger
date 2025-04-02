package com.example.androidcalllogger

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.icu.text.SimpleDateFormat
import android.media.MediaPlayer
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale


    @SuppressLint("Range")
    fun getContactName(context: Context, phoneNumber: String): String? {
        val contentResolver = context.contentResolver
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(if (cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)>=0) {cursor.getColumnIndex(
                    ContactsContract.PhoneLookup.DISPLAY_NAME)}
                else{0})
            }
        }
        return null
    }

    fun processCallLogCursor(
        cursor: Cursor,
        context: Context
         // Фильтр для выборочного добавления записей
    ): CallEntry {
        val numberColumn = cursor.getColumnIndex(CallLog.Calls.NUMBER)
        val typeColumn = cursor.getColumnIndex(CallLog.Calls.TYPE)
        val dateColumn = cursor.getColumnIndex(CallLog.Calls.DATE)
        val durationColumn = cursor.getColumnIndex(CallLog.Calls.DURATION)

        val number = cursor.getString(numberColumn) ?: "Неизвестно"
        val typeCode = cursor.getInt(typeColumn)
        val dateMillis = cursor.getLong(dateColumn)
        val duration = cursor.getInt(durationColumn)

        val name = getContactName(context, number) ?: number
        val callDate = Date(dateMillis)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormatPath = SimpleDateFormat("yyMMdd", Locale.getDefault())
        val timeFormatPath = SimpleDateFormat("HHmm", Locale.getDefault())

        val callType = when (typeCode) {
            CallLog.Calls.INCOMING_TYPE -> "Входящий"
            CallLog.Calls.OUTGOING_TYPE -> "Исходящий"
            CallLog.Calls.MISSED_TYPE -> "Пропущенный"
            CallLog.Calls.REJECTED_TYPE -> "Отклоненный"
            CallLog.Calls.BLOCKED_TYPE -> "Заблокированный"
            else -> "Неизвестный"
        }
        val pathM=name+"-"+dateFormatPath.format(callDate)+timeFormatPath.format(callDate)+".mp3"
        return CallEntry(
            number = number,
            name = name,
            type = callType,
            date = dateFormat.format(callDate),
            time = timeFormat.format(callDate),
            duration = duration,
            dateMillis = dateMillis,
            path=pathM
        )
    }

    // Функция загрузки ограниченного количества звонков при запуск
    fun loadCallLog(
        context: Context,
        callLog: MutableState<List<CallEntry>>,
        limit: Int,
        totalCount: MutableState<Int>,
        hasMoreCalls: MutableState<Boolean>
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val callEntries = mutableListOf<CallEntry>()
            val contentResolver = context.contentResolver
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )

            // Подсчитываем общее количество записей
            val countCursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                "${CallLog.Calls.DURATION} > 0",
                null,
                null
            )
            totalCount.value = countCursor?.count ?: 0
            countCursor?.close()

            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                "${CallLog.Calls.DURATION} > 0",
                null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val entry = processCallLogCursor(it, context)
                    callEntries.add(entry)
                    count++
                }
            }

            withContext(Dispatchers.Main) {
                callLog.value = callEntries
                hasMoreCalls.value = callEntries.size < totalCount.value
            }
        }
    }

fun loadMoreCalls(
    context: Context,
    callLog: MutableState<List<CallEntry>>,
    offset: Int,
    totalCount: MutableState<Int>,
    hasMoreCalls: MutableState<Boolean>,
    limit: Int = 20
) {
    CoroutineScope(Dispatchers.IO).launch {
        println("Starting loadMoreCalls with offset: $offset, limit: $limit") // Отладка
        val callEntries = mutableListOf<CallEntry>()
        val contentResolver = context.contentResolver
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        val cursor = contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.DURATION} > 0",
            null,
            "${CallLog.Calls.DATE} DESC"
        )

        cursor?.use {
            var position = 0
            while (it.moveToNext() && callEntries.size < limit) {
                if (position >= offset) {
                    val entry = processCallLogCursor(it, context)
                    callEntries.add(entry)
                }
                position++
            }
            println("Loaded ${callEntries.size} new entries") // Отладка
        } ?: println("Cursor is null, no data retrieved") // Отладка

        withContext(Dispatchers.Main) {
            if (callEntries.isNotEmpty()) {
                callLog.value += callEntries
            }
            hasMoreCalls.value = (offset + callEntries.size) < totalCount.value
            println("Updated callLog size: ${callLog.value.size}, hasMoreCalls: ${hasMoreCalls.value}") // Отладка
        }
    }
}

@Composable
fun AudioPlayer(
    call: CallEntry,
    selectedFolder: Uri,
    context: Context = LocalContext.current
) {
    var isPlaying by remember { mutableStateOf(false) }
    var playbackPosition by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableIntStateOf(0) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var audioFile by remember { mutableStateOf<Uri?>(null) }
    var isLoading by remember { mutableStateOf(true) } // Для индикации загрузки

    // Формируем URI аудиофайла и инициализируем MediaPlayer в фоновом потоке
    LaunchedEffect(call, selectedFolder) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val folder = DocumentFile.fromTreeUri(context, selectedFolder)
                if (folder != null && folder.isDirectory) {
                    val file = folder.findFile(call.path)
                    val uri = file?.uri
                    if (uri != null) {
                        val player = MediaPlayer().apply {
                            setDataSource(context, uri)
                            prepare()
                            setOnCompletionListener {
                                isPlaying = false
                                playbackPosition = 0f
                            }
                        }
                        audioFile = uri
                        mediaPlayer = player
                        duration = player.duration
                        println("Audio file URI: ${uri.toString()}")
                    }
                } else {
                    Log.e("AudioPlayer", "Selected folder is not a directory or is null")
                }
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error loading file or initializing MediaPlayer: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    // Очистка ресурсов
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isLoading) {
            CircularProgressIndicator()
            Text(
                text = "Загрузка аудио...",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        } else if (audioFile != null && mediaPlayer != null) {
            IconButton(
                onClick = {
                    mediaPlayer?.let { player ->
                        if (isPlaying) {
                            player.pause()
                        } else {
                            player.start()
                        }
                        isPlaying = !isPlaying
                    }
                },
                modifier = Modifier.size(48.dp),
                enabled = mediaPlayer != null
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Person else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(48.dp)
                )
            }

            Slider(
                value = playbackPosition,
                onValueChange = { newPosition ->
                    playbackPosition = newPosition
                    mediaPlayer?.seekTo((newPosition * duration).toInt())
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = mediaPlayer != null
            )

            LaunchedEffect(isPlaying) {
                while (isPlaying && mediaPlayer != null) {
                    delay(1000)
                    mediaPlayer?.let {
                        if (it.isPlaying) {
                            playbackPosition = it.currentPosition.toFloat() / duration
                        }
                    }
                }
            }
        } else {
            Text(
                text = "Аудиозапись для этого звонка не найдена",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
    }
}

    // Форматирование длительности звонка
    fun formatDuration(seconds: Int): String {
        if (seconds < 60) return "$seconds сек."
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return if (remainingSeconds == 0) "$minutes мин." else "$minutes мин. $remainingSeconds сек."
    }

@Composable
fun CallLogItem(
    call: CallEntry,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(2.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when (call.type) {
                "Входящий" -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                "Исходящий" -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                "Пропущенный" -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                else -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp) // Расстояние между элементами
            ) {
                // 1. Имя
                Text(
                    text = call.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // 2. Номер в прямоугольнике
                Box(
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Номер: ${call.number}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 3. Дата и Время в прямоугольниках
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp) // Расстояние между датой и временем
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Дата: ${call.date}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Время: ${call.time}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.End
            ) {
                Icon(
                    imageVector = when (call.type) {
                        "Входящий" -> Icons.Filled.Person
                        "Исходящий" -> Icons.Filled.Call
                        "Пропущенный" -> Icons.Filled.Check
                        else -> Icons.Filled.Call
                    },
                    contentDescription = call.type,
                    tint = when (call.type) {
                        "Пропущенный" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                Text(
                    text = formatDuration(call.duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
    fun saveAudioCache(prefs: SharedPreferences, gson: Gson, audioCache: Map<String, Uri?>)
    {
        val serializableCache = audioCache.mapValues { it.value?.toString() }
        val json = gson.toJson(serializableCache)
        prefs.edit() { putString("audioCache", json) }
    }
