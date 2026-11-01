package ru.fitprogress.ai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*

@Composable fun MapPanel(points: List<GeoPoint>) {
    val context = LocalContext.current; val owner = LocalLifecycleOwner.current
    var error by remember { mutableStateOf("") }; var map by remember { mutableStateOf<MapLibreMap?>(null) }; var styleLoaded by remember { mutableStateOf(false) }
    val mapView = remember { MapLibre.getInstance(context); MapView(context).apply { onCreate(null) } }
    DisposableEffect(mapView, owner) {
        var started = false; var resumed = false
        fun start() { if(!started) { mapView.onStart(); started = true } }
        fun resume() { start(); if(!resumed) { mapView.onResume(); resumed = true } }
        fun pause() { if(resumed) { mapView.onPause(); resumed = false } }
        fun stop() { pause(); if(started) { mapView.onStop(); started = false } }
        val observer = LifecycleEventObserver { _, event -> when(event) { Lifecycle.Event.ON_START -> start(); Lifecycle.Event.ON_RESUME -> resume(); Lifecycle.Event.ON_PAUSE -> pause(); Lifecycle.Event.ON_STOP -> stop(); else -> Unit } }
        owner.lifecycle.addObserver(observer)
        if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        mapView.addOnDidFailLoadingMapListener { error = "Картографические данные недоступны. GPS и схема маршрута работают без интернета." }
        mapView.getMapAsync { m ->
            map = m
            m.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"attribution":"© OpenStreetMap contributors","maxzoom":19}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#202820"}},{"id":"osm","type":"raster","source":"osm"}]}""")) { style ->
                style.addSource(GeoJsonSource("route", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addLayer(LineLayer("route-line", "route").withProperties(lineColor("#4C9C16"), lineWidth(5f)))
                styleLoaded = true
            }
        }
        onDispose { owner.lifecycle.removeObserver(observer); stop(); mapView.onDestroy() }
    }
    LaunchedEffect(points.size, map, styleLoaded) {
        val m = map
        if(m != null && styleLoaded && points.isNotEmpty()) {
            val features = points.groupBy { it.segment }.values.filter { it.size >= 2 }.map { segment -> Feature.fromGeometry(LineString.fromLngLats(segment.map { Point.fromLngLat(it.lon, it.lat) })) }
            m.style?.getSourceAs<GeoJsonSource>("route")?.setGeoJson(FeatureCollection.fromFeatures(features))
            if(points.size == 1) m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points[0].lat, points[0].lon), 16.0))
            else runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build(), 45)) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxWidth().height(260.dp))
        Text("© OpenStreetMap contributors · карта требует сети или кэша", style = MaterialTheme.typography.labelSmall)
        if(error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall)
        if(points.isEmpty()) Text("Маршрут появится после получения точных GPS-точек.")
        else { Text("Схема маршрута · доступна офлайн", style = MaterialTheme.typography.labelMedium); RouteFallback(points) }
    }
}
