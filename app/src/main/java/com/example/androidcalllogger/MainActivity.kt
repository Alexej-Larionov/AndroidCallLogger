package com.example.androidcalllogger

//import android.content.Room
import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.text.SimpleDateFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

// Константы для приложения
private const val PREFS_NAME = "CallLoggerPrefs"
private const val KEY_FOLDER_URI = "folderUri"
private const val KEY_SEND_AFTER_CALL = "sendAfterCall"
private const val KEY_SEND_ON_WIFI = "sendOnWifi"
private const val KEY_SELECTED_TIME_HOUR = "selectedTimeHour"
private const val KEY_SELECTED_TIME_MINUTE = "selectedTimeMinute"
private const val INITIAL_CALL_LOG_LIMIT = 20 // Загружать только 20 последних вызовов при запуске

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("isFirstRun", true)

        if (isFirstRun) {
            //loadCallLog()
            prefs.edit() { putBoolean("isFirstRun", false) }
        }
        setContent {
            CallLoggerApp()
        }
    }
}



data class CallEntry(
    val number: String,
    val name: String,
    val type: String,
    val date: String,
    val time: String,
    val duration: Int,
    val dateMillis: Long,
    val path:String
)

@RequiresApi(Build.VERSION_CODES.S)
@Preview
@Composable
fun CallLoggerApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val gson = Gson()

    // Загружаем сохраненные настройки
    val savedFolderUri = prefs.getString(KEY_FOLDER_URI, null)
    var selectedFolder by remember {mutableStateOf(savedFolderUri?.toUri()) }

    val calendar = Calendar.getInstance()
    calendar.set(Calendar.HOUR_OF_DAY, prefs.getInt(KEY_SELECTED_TIME_HOUR, 12))
    calendar.set(Calendar.MINUTE, prefs.getInt(KEY_SELECTED_TIME_MINUTE, 0))
    val selectedTime by remember { mutableStateOf(calendar) }

    var sendAfterCall by remember {
        mutableStateOf(prefs.getBoolean(KEY_SEND_AFTER_CALL, false))
    }
    var sendOnWiFi by remember {
        mutableStateOf(prefs.getBoolean(KEY_SEND_ON_WIFI, false))
    }

    var expandedCall by remember { mutableStateOf<CallEntry?>(null) }
    val audioCache = remember { mutableStateMapOf<String, Uri?>() }
    val callLog = remember { mutableStateOf<List<CallEntry>>(emptyList()) }
    val isLoading = remember { mutableStateOf(true) }
    val hasMoreCalls = remember { mutableStateOf(true) }
    val totalCallCount = remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Сохраняем права доступа
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
                Log.d("FolderPicker", "Permission granted for URI: $uri")
            } catch (e: SecurityException) {
                Log.e("FolderPicker", "Failed to take permission: $e")
            }
            selectedFolder = uri
            Log.d("FolderPicker", "Selected folder URI: $uri")

            // Проверяем сохраненные права
            val permissions = context.contentResolver.persistedUriPermissions
            Log.d("FolderPicker", "Current permissions: ${permissions.map { it.uri to it.isReadPermission }}")
        }
    }
    val audioFiles = remember { mutableStateOf<List<Uri>>(emptyList()) }


    // Сохранение настроек при их изменении
    LaunchedEffect(selectedFolder, sendAfterCall, sendOnWiFi, selectedTime) {
        with(prefs.edit()) {
            putString(KEY_FOLDER_URI, selectedFolder?.toString())
            putBoolean(KEY_SEND_AFTER_CALL, sendAfterCall)
            putBoolean(KEY_SEND_ON_WIFI, sendOnWiFi)
            putInt(KEY_SELECTED_TIME_HOUR, selectedTime.get(Calendar.HOUR_OF_DAY))
            putInt(KEY_SELECTED_TIME_MINUTE, selectedTime.get(Calendar.MINUTE))
            apply()
        }
    }

    fun requestCallLogPermission(activity: ComponentActivity) {
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CALL_LOG), 1)
        }
    }



    LaunchedEffect(Unit) {
        loadCallLog(context, callLog, INITIAL_CALL_LOG_LIMIT, totalCallCount, hasMoreCalls)
        isLoading.value = false
    }

    // Разрешение на доступ к журналу вызовов
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Требуется разрешение для доступа к журналу вызовов", Toast.LENGTH_SHORT).show()
        } else
        {
            loadCallLog(context, callLog, INITIAL_CALL_LOG_LIMIT, totalCallCount, hasMoreCalls)
            isLoading.value = false
        }
    }

    val dynamicColor = true
    val darkTheme = isSystemInDarkTheme()
    val colors = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(LocalContext.current)
        dynamicColor && !darkTheme -> dynamicLightColorScheme(LocalContext.current)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }


    LaunchedEffect(audioCache) {
        saveAudioCache(prefs, gson, audioCache)
    }

    MaterialTheme(colorScheme = colors) {
        val folderPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) selectedFolder = uri
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            if (expandedCall == null)
            {
                val lazyListState = rememberLazyListState()
                var offset by remember { mutableIntStateOf(0) } // Отслеживаем offset

                // Подгрузка данных при прокрутке
                LaunchedEffect(lazyListState) {
                    snapshotFlow { lazyListState.layoutInfo.visibleItemsInfo }
                        .collect { visibleItems ->
                            if (!isLoading.value && hasMoreCalls.value &&
                                visibleItems.isNotEmpty() &&
                                visibleItems.last().index >= callLog.value.size - 5
                            ) {
                                isLoading.value = true
                                println("Loading more calls with offset: $offset") // Отладка
                                loadMoreCalls(
                                    context,
                                    callLog,
                                    totalCount = totalCallCount,
                                    hasMoreCalls = hasMoreCalls,
                                    offset = offset,
                                    limit = INITIAL_CALL_LOG_LIMIT
                                )
                                offset += INITIAL_CALL_LOG_LIMIT // Увеличиваем offset
                                println("Loaded more calls, new offset: $offset, callLog size: ${callLog.value.size}") // Отладка
                                isLoading.value = false
                            }
                        }
                }

                if (callLog.value.isEmpty() && !isLoading.value) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Нет доступных записей звонков",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                } else {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(fraction = 0.9f)
                            .padding(0.dp)
                    ) {
                        items(callLog.value) { call ->
                            CallLogItem(
                                call = call,
                                onClick = { expandedCall = call }
                            )
                        }

                        if (isLoading.value && hasMoreCalls.value) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            } else {
                // Открытая "заметка"
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    // Заменяем шапку на CallLogItem
                    CallLogItem(
                        call = expandedCall!!,
                        onClick = { expandedCall = null } // Назад при клике
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        expandedCall?.let { call ->
                            if (selectedFolder != null) {
                                // Показываем плеер только если выбрана папка
                                AudioPlayer(
                                    call = call,
                                    selectedFolder = selectedFolder!!,
                                    context = context
                                )
                            } else {
                                // Сообщение, если папка не выбрана
                                Text(
                                    text = "Выберите папку с аудиозаписями для воспроизведения",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        Text(
                            text = "Дата звонка: ${expandedCall!!.date}\nВремя звонка: ${expandedCall!!.time}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }


            // Нижний блок кнопок
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LocalConfiguration.current.screenHeightDp.dp / 10f)
                    .padding(horizontal = 0.dp, vertical = 0.dp)
                    .align(Alignment.BottomCenter),
                shape = RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            )
            {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(
                            onClick = { folderPickerLauncher.launch(null)

                                      },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = RoundedCornerShape(topStart = 16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Settings, "Directory", tint = MaterialTheme.colorScheme.onPrimary)
                                Text(if (selectedFolder != null) "Папка выбрана" else "Выбрать папку",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Button(
                            onClick = {
                                val timePicker = TimePickerDialog(
                                    context,
                                    { _, hour, minute ->
                                        selectedTime.set(Calendar.HOUR_OF_DAY, hour)
                                        selectedTime.set(Calendar.MINUTE, minute)
                                    },
                                    selectedTime.get(Calendar.HOUR_OF_DAY),
                                    selectedTime.get(Calendar.MINUTE),
                                    true
                                )
                                timePicker.show()
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = RectangleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Home, "Schedule", tint = MaterialTheme.colorScheme.onPrimary)
                                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                                Text(timeFormat.format(selectedTime.time),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Button(
                            onClick = { sendAfterCall = !sendAfterCall },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = RectangleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (sendAfterCall) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    if (sendAfterCall) Icons.Filled.Check else Icons.Filled.Close,
                                    "Send After Call",
                                    tint = if (sendAfterCall) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text("После звонка",
                                    color = if (sendAfterCall) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center)
                            }
                        }
                        Button(
                            onClick = { sendOnWiFi = !sendOnWiFi },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = RoundedCornerShape(topEnd = 16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (sendOnWiFi) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    if (sendOnWiFi) Icons.Filled.Warning else Icons.Filled.KeyboardArrowUp,
                                    "Send on WiFi",
                                    tint = if (sendOnWiFi) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text("По Wi-Fi",
                                    color = if (sendOnWiFi) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }}




