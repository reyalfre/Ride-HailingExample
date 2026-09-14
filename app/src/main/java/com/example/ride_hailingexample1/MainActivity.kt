package com.example.ride_hailingexample1

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.example.ride_hailingexample1.ui.theme.RideHailingExample1Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

enum class Screen {
    Map, History
}

class MainActivity : ComponentActivity() {
    private val TAG = "RideHailingLog"
    private val startPoint = GeoPoint(40.4168, -3.7038)
    private var distanceKm by mutableStateOf(0.0)
    private var destinationName by mutableStateOf("")
    private var routePolyline: Polyline? = null
    private var mapViewInstance: MapView? = null
    private var destMarker: Marker? = null
    private lateinit var dbHelper: DatabaseHelper
    private var currentScreen by mutableStateOf(Screen.Map)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        dbHelper = DatabaseHelper(this)
        
        val ctx = applicationContext
        val osmConfig = Configuration.getInstance()
        osmConfig.load(ctx, ctx.getSharedPreferences("osmdroid_prefs", MODE_PRIVATE))
        val userAgent = "Mozilla/5.0 (Linux; Android 15; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        osmConfig.userAgentValue = userAgent
        
        val osmCacheDir = File(ctx.cacheDir, "osmdroid")
        if (!osmCacheDir.exists()) osmCacheDir.mkdirs()
        osmConfig.osmdroidBasePath = osmCacheDir
        osmConfig.osmdroidTileCache = File(osmCacheDir, "tiles")

        enableEdgeToEdge()
        setContent {
            RideHailingExample1Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (currentScreen) {
                        Screen.Map -> {
                            RideHailingApp(
                                onSearch = { query -> performSearch(query, userAgent) },
                                distance = distanceKm,
                                onConfirm = { option -> saveTripToDb(option) },
                                onGoToHistory = { currentScreen = Screen.History }
                            )
                        }
                        Screen.History -> {
                            HistoryScreen(
                                dbHelper = dbHelper,
                                onBack = { currentScreen = Screen.Map }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun performSearch(query: String, userAgent: String) {
        Log.d(TAG, "Iniciando búsqueda para: $query")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = URL("https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=1")
                val connection = searchUrl.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", userAgent)
                
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                
                if (jsonArray.length() > 0) {
                    val firstResult = jsonArray.getJSONObject(0)
                    val lat = firstResult.getDouble("lat")
                    val lon = firstResult.getDouble("lon")
                    val dest = GeoPoint(lat, lon)

                    val routingUrl = URL("https://router.project-osrm.org/route/v1/driving/${startPoint.longitude},${startPoint.latitude};${lon},${lat}?overview=full&geometries=geojson")
                    val routeConn = routingUrl.openConnection() as HttpURLConnection
                    routeConn.setRequestProperty("User-Agent", userAgent)
                    
                    val routeResponse = routeConn.inputStream.bufferedReader().use { it.readText() }
                    val routeJson = JSONObject(routeResponse)
                    val routes = routeJson.getJSONArray("routes")
                    
                    if (routes.length() > 0) {
                        val route = routes.getJSONObject(0)
                        val distance = route.getDouble("distance") / 1000.0
                        val geometry = route.getJSONObject("geometry")
                        val coordinates = geometry.getJSONArray("coordinates")
                        
                        val points = mutableListOf<GeoPoint>()
                        for (i in 0 until coordinates.length()) {
                            val coord = coordinates.getJSONArray(i)
                            points.add(GeoPoint(coord.getDouble(1), coord.getDouble(0)))
                        }

                        withContext(Dispatchers.Main) {
                            distanceKm = distance
                            destinationName = query
                            updateMapRoute(points, dest)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en la búsqueda", e)
            }
        }
    }

    private fun updateMapRoute(points: List<GeoPoint>, dest: GeoPoint) {
        mapViewInstance?.let { mv ->
            routePolyline?.let { mv.overlays.remove(it) }
            destMarker?.let { mv.overlays.remove(it) }
            
            val line = Polyline(mv)
            line.setPoints(points)
            line.outlinePaint.color = android.graphics.Color.BLACK
            line.outlinePaint.strokeWidth = 12f
            mv.overlays.add(line)
            routePolyline = line

            val marker = Marker(mv)
            marker.position = dest
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title = "Destino"
            mv.overlays.add(marker)
            destMarker = marker

            mv.controller.animateTo(dest)
            mv.invalidate()
        }
    }

    private fun saveTripToDb(option: RideOption) {
        if (distanceKm == 0.0) {
            Toast.makeText(this, "Busca un destino primero", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch(Dispatchers.IO) {
            dbHelper.saveTrip(
                destination = destinationName,
                distance = distanceKm,
                price = option.price,
                type = option.name
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Viaje confirmado y guardado!", Toast.LENGTH_LONG).show()
                // Limpiar después de confirmar
                distanceKm = 0.0
                destinationName = ""
                routePolyline?.let { mapViewInstance?.overlays?.remove(it) }
                destMarker?.let { mapViewInstance?.overlays?.remove(it) }
                routePolyline = null
                destMarker = null
                mapViewInstance?.invalidate()
            }
        }
    }

    @Composable
    fun RideHailingApp(
        onSearch: (String) -> Unit, 
        distance: Double, 
        onConfirm: (RideOption) -> Unit,
        onGoToHistory: () -> Unit
    ) {
        val rideOptions = remember(distance) {
            listOf(
                RideOption(1, "G-Economy", calculatePrice(8.50, 1.20, distance), "4 min", Icons.Default.DirectionsCar),
                RideOption(2, "G-Comfort", calculatePrice(12.20, 1.80, distance), "2 min", Icons.Default.DirectionsCar),
                RideOption(3, "G-Electric", calculatePrice(9.00, 1.30, distance), "6 min", Icons.Default.ElectricCar),
                RideOption(4, "G-XL", calculatePrice(18.00, 2.50, distance), "5 min", Icons.Default.Groups)
            )
        }
        var selectedOption by remember { mutableStateOf(rideOptions[0]) }

        Box(modifier = Modifier.fillMaxSize()) {
            OSMMapView(modifier = Modifier.fillMaxSize()) { mv ->
                mapViewInstance = mv
            }

            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                SearchArea(onSearch = onSearch, onMenuClick = onGoToHistory)
            }

            RideSelectionPanel(
                modifier = Modifier.align(Alignment.BottomCenter),
                options = rideOptions,
                selectedOption = selectedOption,
                onOptionSelected = { selectedOption = it },
                onConfirm = { onConfirm(selectedOption) }
            )
        }
    }

    private fun calculatePrice(base: Double, ratePerKm: Double, distance: Double): String {
        val total = base + (ratePerKm * distance)
        return String.format(Locale.getDefault(), "€%.2f", total)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(dbHelper: DatabaseHelper, onBack: () -> Unit) {
    var trips by remember { mutableStateOf(listOf<TripRecord>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val list = dbHelper.getAllTrips()
            withContext(Dispatchers.Main) {
                trips = list
            }
        }
    }

    BackHandler {
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historial de Viajes", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        if (trips.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No hay viajes registrados aún", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(trips) { trip ->
                    TripItem(trip)
                }
            }
        }
    }
}

@Composable
fun TripItem(trip: TripRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp).background(Color.Black, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(trip.destination, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text("${trip.type} • ${String.format(Locale.getDefault(), "%.1f", trip.distance)} km", fontSize = 14.sp, color = Color.Gray)
                Text(trip.getFormattedDate(), fontSize = 12.sp, color = Color.LightGray)
            }
            Text(trip.price, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = Color.Black)
        }
    }
}

data class RideOption(
    val id: Int,
    val name: String,
    val price: String,
    val time: String,
    val icon: ImageVector
)

@Composable
fun OSMMapView(modifier: Modifier = Modifier, onMapReady: (MapView) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val initialPoint = GeoPoint(40.4168, -3.7038)

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(initialPoint)
            onMapReady(this)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDetach()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(factory = { mapView }, modifier = modifier) { mv ->
        val startMarker = Marker(mv)
        startMarker.position = initialPoint
        startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        startMarker.title = "Tu ubicación"
        mv.overlays.add(startMarker)
    }
}

@Composable
fun SearchArea(onSearch: (String) -> Unit, onMenuClick: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.History, contentDescription = "Historial", tint = Color.Black)
            }
            
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("¿A dónde vas?") },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (query.isNotEmpty()) {
                        onSearch(query)
                        keyboardController?.hide()
                    }
                }),
                trailingIcon = {
                    IconButton(onClick = {
                        if (query.isNotEmpty()) {
                            onSearch(query)
                            keyboardController?.hide()
                        }
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.Black)
                    }
                }
            )
        }
    }
}

@Composable
fun RideSelectionPanel(
    modifier: Modifier = Modifier,
    options: List<RideOption>,
    selectedOption: RideOption,
    onOptionSelected: (RideOption) -> Unit,
    onConfirm: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        elevation = CardDefaults.cardElevation(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Selecciona tu G-Ride",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(16.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(options) { option ->
                    RideTypeCard(
                        option = option,
                        isSelected = option.id == selectedOption.id,
                        onClick = { onOptionSelected(option) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
            ) {
                Text("Confirmar ${selectedOption.name} - ${selectedOption.price}", fontSize = 18.sp, color = Color.White)
            }
        }
    }
}

@Composable
fun RideTypeCard(
    option: RideOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(110.dp)
            .height(130.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Color(0xFFEEEEEE) else Color.Transparent,
        border = if (isSelected) null else BorderStroke(1.dp, Color(0xFFDDDDDD))
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                option.icon,
                contentDescription = null,
                tint = if (isSelected) Color.Black else Color.Gray,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(option.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Text(option.time, fontSize = 10.sp, color = Color.Gray)
            Text(option.price, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
        }
    }
}
