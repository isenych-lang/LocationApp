package com.example.locationapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

// ================= 1. DATA LAYER (Room Location Database) =================
@Entity(tableName = "location_history")
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)

@Dao
interface LocationDao {
    @Query("SELECT * FROM location_history ORDER BY timestamp DESC")
    fun getAllLocations(): Flow<List<LocationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: LocationEntity)

    @Query("DELETE FROM location_history")
    suspend fun clearAll()
}

@Database(entities = [LocationEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao
    companion object {
        private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "location_db")
                    .build().also { INSTANCE = it }
            }
        }
    }
}

// ================= 2. PRESENTATION LAYER (ViewModel + Location Logic) =================
class LocationViewModel(context: Context) : ViewModel() {
    private val db = AppDatabase.getInstance(context)
    private val dao = db.locationDao()
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    val locationsState: StateFlow<List<LocationEntity>> = dao.getAllLocations()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun fetchAndSaveCurrentLocation(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        viewModelScope.launch {
            try {
                // Запрашиваем текущую геопозицию с высоким приоритетом точности
                val location = fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    null
                ).await()

                if (location != null) {
                    val entity = LocationEntity(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        timestamp = System.currentTimeMillis()
                    )
                    dao.insertLocation(entity)
                } else {
                    Toast.makeText(context, "Не удалось получить локацию. Включите GPS", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { dao.clearAll() }
    }
}

// ================= 3. UI LAYER (Jetpack Compose + Runtime Permissions) =================
@Composable
fun LocationScreen(viewModel: LocationViewModel) {
    val context = LocalContext.current
    val locations by viewModel.locationsState.collectAsState()
    val sdf = remember { SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()) }

    // Лаунчер для обработки Runtime Permissions API
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.fetchAndSaveCurrentLocation(context)
        } else {
            Toast.makeText(context, "Доступ к геопозиции отклонен", Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = {
                val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    viewModel.fetchAndSaveCurrentLocation(context)
                } else {
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }) {
                Text("Получить позицию")
            }

            Button(
                onClick = { viewModel.clearHistory() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Очистить")
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("История локаций (Offline кэш):", style = MaterialTheme.typography.titleMedium)

        LazyColumn(Modifier.fillMaxSize()) {
            items(locations) { item ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Широта: ${item.latitude}, Долгота: ${item.longitude}")
                        Text("Время: ${sdf.format(Date(item.timestamp))}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ================= 4. ENTRY POINT =================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val viewModel = remember { LocationViewModel(context) }
                LocationScreen(viewModel)
            }
        }
    }
}
