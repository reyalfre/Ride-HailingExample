package com.example.ride_hailingexample1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ride_hailingexample1.ui.theme.RideHailingExample1Theme
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configuración definitiva para evitar el 403
        val ctx = applicationContext
        val osmConfig = Configuration.getInstance()
        
        // 1. Cargar preferencias primero
        osmConfig.load(ctx, ctx.getSharedPreferences("osmdroid_prefs", MODE_PRIVATE))
        
        // 2. Establecer un User-Agent que imite a un navegador real (Chrome en Android)
        osmConfig.userAgentValue = "Mozilla/5.0 (Linux; Android 15; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        
        // 3. Forzar el uso de la memoria interna para la caché (evita errores en API 30+)
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
                    MapScreen()
                }
            }
        }
    }
}

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val singapore = GeoPoint(1.35, 103.87)

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(10.0)
            controller.setCenter(singapore)
        }
    }

    // Gestionar el ciclo de vida del MapView
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

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        ) { mv ->
            // Añadir un marcador
            val marker = Marker(mv)
            marker.position = singapore
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title = "Singapore"
            marker.subDescription = "Marker in Singapore"
            mv.overlays.clear()
            mv.overlays.add(marker)
        }
    }
}
