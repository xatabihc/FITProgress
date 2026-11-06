package ru.fitprogress.ai

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*

private object MapHttp {
    @Volatile private var client: OkHttpClient? = null
    fun get(context: android.content.Context): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            .cache(okhttp3.Cache(java.io.File(context.applicationContext.cacheDir, "map-http"), 48L * 1024 * 1024))
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "FitProgressAI/3.1 (+https://github.com/xatabihc/train)").build()) }
            .build().also { client = it }
    }
}

@Composable fun MapPanel(
    points: List<GeoPoint>, plannedRoute: NavigationRoute? = null, selectionEnabled: Boolean = false,
    markers: List<RouteCoordinate> = emptyList(), currentLocation: RouteCoordinate? = points.lastOrNull()?.let { RouteCoordinate(it.lat, it.lon) },
    followUser: Boolean = false, mapHeight: Dp = 300.dp,
    onMapTap: (RouteCoordinate) -> Unit = {}
) {
    val context = LocalContext.current; val owner = LocalLifecycleOwner.current
    var error by remember { mutableStateOf("") }; var map by remember { mutableStateOf<MapLibreMap?>(null) }; var styleLoaded by remember { mutableStateOf(false) }
    val selection = rememberUpdatedState(selectionEnabled); val tap = rememberUpdatedState(onMapTap)
    val mapView = remember {
        MapLibre.getInstance(context)
        HttpRequestUtil.setOkHttpClient(MapHttp.get(context))
        MapView(context).apply { onCreate(null) }
    }
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
        mapView.addOnDidFailLoadingMapListener {
            Log.w("FitMapLibre", "Map tiles/style unavailable; local GPS and route geometry remain available")
            error = "Картографические тайлы недоступны. GPS-запись и сохранённая линия маршрута продолжают работать."
        }
        mapView.getMapAsync { m ->
            map = m
            m.addOnMapClickListener { point -> if(selection.value) { tap.value(RouteCoordinate(point.latitude, point.longitude)); true } else false }
            m.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"attribution":"© OpenStreetMap contributors","maxzoom":19}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#202820"}},{"id":"osm","type":"raster","source":"osm"}]}""")) { style ->
                Log.i("FitMapLibre", "Map style loaded; route overlay sources initialized")
                style.addSource(GeoJsonSource("recorded", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addSource(GeoJsonSource("planned", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addSource(GeoJsonSource("markers", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addSource(GeoJsonSource("user-location", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addLayer(LineLayer("recorded-line", "recorded").withProperties(lineColor("#B4F667"), lineWidth(5f)))
                style.addLayer(LineLayer("planned-line", "planned").withProperties(lineColor("#48C8FF"), lineWidth(5f), lineDasharray(arrayOf(1.5f, 1f))))
                style.addLayer(CircleLayer("route-markers", "markers").withProperties(circleRadius(7f), circleColor("#FFB45B"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
                style.addLayer(CircleLayer("current-location", "user-location").withProperties(circleRadius(8f), circleColor("#48C8FF"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
                styleLoaded = true
            }
        }
        onDispose { owner.lifecycle.removeObserver(observer); stop(); mapView.onDestroy() }
    }
    LaunchedEffect(points.isNotEmpty(), map, styleLoaded) {
        val m = map ?: return@LaunchedEffect
        if(styleLoaded && points.isNotEmpty() && !followUser && plannedRoute == null) {
            val display = sampleGpsTrack(points)
            runCatching {
                if(display.size == 1) m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(display[0].lat, display[0].lon), 16.0))
                else m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(display.map { LatLng(it.lat, it.lon) }).build(), 48))
            }
        }
    }
    LaunchedEffect(points.size, points.lastOrNull()?.lat, points.lastOrNull()?.lon, points.lastOrNull()?.segment, map, styleLoaded) {
        val m = map ?: return@LaunchedEffect
        if(styleLoaded) {
            val displayPoints = sampleGpsTrack(points)
            val features = displayPoints.groupBy { it.segment }.values.filter { it.size >= 2 }.map { segment -> Feature.fromGeometry(LineString.fromLngLats(segment.map { Point.fromLngLat(it.lon, it.lat) })) }
            m.style?.getSourceAs<GeoJsonSource>("recorded")?.setGeoJson(FeatureCollection.fromFeatures(features))
        }
    }
    LaunchedEffect(plannedRoute?.id, plannedRoute?.points?.size, map, styleLoaded) {
        val m = map ?: return@LaunchedEffect
        if(styleLoaded) {
            val route = plannedRoute
            val displayRoute = route?.points?.let(::sampleRoute)
            val features = displayRoute?.groupBy { it.segment }?.values?.filter { it.size >= 2 }?.map { group -> Feature.fromGeometry(LineString.fromLngLats(group.map { Point.fromLngLat(it.longitude, it.latitude) })) }.orEmpty()
            m.style?.getSourceAs<GeoJsonSource>("planned")?.setGeoJson(FeatureCollection.fromFeatures(features))
            if(route != null && route.points.isNotEmpty()) runCatching { m.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(route.points.map { LatLng(it.latitude, it.longitude) }).build(), 48)) }
        }
    }
    LaunchedEffect(markers, currentLocation, map, styleLoaded) {
        val m = map ?: return@LaunchedEffect
        if(styleLoaded) {
            m.style?.getSourceAs<GeoJsonSource>("markers")?.setGeoJson(FeatureCollection.fromFeatures(markers.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }))
            val userFeature = currentLocation?.let { arrayOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) } ?: emptyArray()
            m.style?.getSourceAs<GeoJsonSource>("user-location")?.setGeoJson(FeatureCollection.fromFeatures(userFeature))
            if(plannedRoute == null && points.isEmpty() && currentLocation != null) m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(currentLocation.latitude, currentLocation.longitude), 15.0))
        }
    }
    LaunchedEffect(followUser, currentLocation, map, styleLoaded) {
        val m = map ?: return@LaunchedEffect
        val location = currentLocation ?: return@LaunchedEffect
        if(followUser && styleLoaded) m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 17.0), 450)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxWidth().height(mapHeight))
        Text("© OpenStreetMap contributors · карты требуют сети или действительного кэша", style = MaterialTheme.typography.labelSmall)
        if(selectionEnabled) Text("Нажмите на карту, чтобы выбрать следующую точку маршрута.", style = MaterialTheme.typography.bodySmall)
        if(error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall)
        if(points.isEmpty() && plannedRoute == null) Text(if(currentLocation == null) "Ожидается позиция GPS или выбор точки на карте." else "Синяя точка — последняя позиция устройства.")
        if(plannedRoute != null) Text("Маршрут по дорожной сети · %.2f км · %s".format(plannedRoute.distanceMeters / 1000, RouteMode.valueOf(plannedRoute.mode).label), style = MaterialTheme.typography.labelMedium)
        if(plannedRoute != null) { Text(stringResource(R.string.gps_map_offline_preview), style = MaterialTheme.typography.labelSmall); RouteFallback(sampleRoute(plannedRoute.points).map { GeoPoint(it.latitude, it.longitude, 0f, plannedRoute.createdAt, it.segment) }) }
        if(points.isNotEmpty()) { Text("Фактический GPS-трек", style = MaterialTheme.typography.labelMedium); RouteFallback(points) }
    }
}

private fun sampleGpsTrack(points: List<GeoPoint>, maximum: Int = 2400): List<GeoPoint> {
    if(points.size <= maximum) return points
    val step = ((points.size + maximum - 1) / maximum).coerceAtLeast(1)
    return points.filterIndexed { index, point -> index % step == 0 || index == points.lastIndex || points.getOrNull(index - 1)?.segment != point.segment || points.getOrNull(index + 1)?.segment != point.segment }
}
private fun sampleRoute(points: List<RouteCoordinate>, maximum: Int = 2400): List<RouteCoordinate> {
    if(points.size <= maximum) return points
    val step = ((points.size + maximum - 1) / maximum).coerceAtLeast(1)
    return points.filterIndexed { index, point -> index % step == 0 || index == points.lastIndex || points.getOrNull(index - 1)?.segment != point.segment || points.getOrNull(index + 1)?.segment != point.segment }
}
