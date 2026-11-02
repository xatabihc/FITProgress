package ru.fitprogress.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class RouteMode(val label: String, val costing: String) { RUN("Бег", "pedestrian"), WALK("Ходьба", "pedestrian"), CYCLE("Велосипед", "bicycle") }
data class RouteCoordinate(val latitude: Double, val longitude: Double, val segment: Int = 0)
data class RouteInstruction(val distanceAlongMeters: Double, val distanceMeters: Double, val text: String)
data class NavigationRoute(
    val id: String = newId(), val name: String = "Маршрут", val mode: String = RouteMode.RUN.name,
    val points: List<RouteCoordinate>, val distanceMeters: Double, val instructions: List<RouteInstruction> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(), val source: String = "Valhalla/OSM", val targetMeters: Double = 0.0
)
data class RouteRequest(val locations: List<RouteCoordinate>, val mode: RouteMode, val alternates: Int = 0)
class RoutingException(message: String, val category: String, cause: Throwable? = null) : Exception(message, cause)

object RouteMath {
    private const val EARTH = 6371008.8
    fun distance(a: RouteCoordinate, b: RouteCoordinate): Double = Gps.distance(GeoPoint(a.latitude, a.longitude, 0f, 1), GeoPoint(b.latitude, b.longitude, 0f, 1))
    fun validate(route: NavigationRoute) {
        require(route.name.isNotBlank() && route.name.length <= 120 && route.points.size in 2..100000)
        require(route.distanceMeters.isFinite() && route.distanceMeters > 0.0 && route.distanceMeters <= 1_000_000.0)
        require(route.targetMeters.isFinite() && route.targetMeters >= 0.0 && route.createdAt > 0)
        require(route.mode in RouteMode.entries.map { it.name })
        route.points.forEach { require(it.latitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude.isFinite() && it.longitude in -180.0..180.0 && it.segment >= 0) }
        require(route.instructions.size <= 10000 && route.instructions.all { it.text.isNotBlank() && it.text.length <= 500 && it.distanceMeters.isFinite() && it.distanceMeters >= 0.0 && it.distanceAlongMeters.isFinite() && it.distanceAlongMeters in 0.0..route.distanceMeters + 100.0 })
    }
    data class Progress(val distanceFromRouteMeters: Double, val traveledMeters: Double, val remainingMeters: Double, val offRoute: Boolean)
    /** Nearest point on every route segment in a local tangent plane; never adds distance to the workout. */
    fun progress(route: NavigationRoute, fix: RouteCoordinate, offRouteThresholdMeters: Double = 45.0, previousAlongMeters: Double? = null, movedMeters: Double? = null): Progress {
        require(offRouteThresholdMeters > 0)
        data class Candidate(val cross: Double, val along: Double)
        val candidates = mutableListOf<Candidate>(); var accumulated = 0.0
        route.points.zipWithNext().filter { it.first.segment == it.second.segment }.forEach { (a, b) ->
            val lat0 = Math.toRadians((a.latitude + b.latitude + fix.latitude) / 3.0)
            val ax = Math.toRadians(a.longitude - fix.longitude) * EARTH * cos(lat0); val ay = Math.toRadians(a.latitude - fix.latitude) * EARTH
            val bx = Math.toRadians(b.longitude - fix.longitude) * EARTH * cos(lat0); val by = Math.toRadians(b.latitude - fix.latitude) * EARTH
            val dx = bx - ax; val dy = by - ay; val length2 = dx * dx + dy * dy
            val t = if(length2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / length2).coerceIn(0.0, 1.0)
            val cross = hypot(ax + t * dx, ay + t * dy)
            val length = sqrt(length2)
            candidates += Candidate(cross, accumulated + length * t)
            accumulated += length
        }
        val expected = previousAlongMeters?.plus((movedMeters ?: 0.0).coerceAtLeast(0.0))
        val chosen = candidates.minByOrNull { candidate ->
            val continuityPenalty = expected?.let { target ->
                val backwards = (target - candidate.along - 25.0).coerceAtLeast(0.0)
                val jumpedAhead = (candidate.along - target - (movedMeters ?: 0.0).coerceAtLeast(0.0) - 120.0).coerceAtLeast(0.0)
                backwards * 3.0 + jumpedAhead * 1.5
            } ?: 0.0
            candidate.cross + continuityPenalty
        }
        var bestCross = chosen?.cross ?: Double.POSITIVE_INFINITY
        var bestAlong = chosen?.along ?: 0.0
        if(bestCross == Double.POSITIVE_INFINITY) bestCross = distance(fix, route.points.first())
        val remaining = (route.distanceMeters - bestAlong).coerceAtLeast(0.0)
        return Progress(bestCross, bestAlong, remaining, bestCross > offRouteThresholdMeters)
    }
    fun loopWaypoints(start: RouteCoordinate, distanceMeters: Double, mode: RouteMode, radiusFactor: Double = 1.0): List<RouteCoordinate> {
        require(distanceMeters in 1000.0..50000.0 && radiusFactor in 0.7..1.3)
        val radius = distanceMeters / (2 * Math.PI) * radiusFactor
        return listOf(0.0, 120.0, 240.0).map { bearing ->
            val br = Math.toRadians(bearing); val angular = radius / EARTH; val lat1 = Math.toRadians(start.latitude); val lon1 = Math.toRadians(start.longitude)
            val lat = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(br))
            val lon = lon1 + atan2(sin(br) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat))
            RouteCoordinate(Math.toDegrees(lat), Math.toDegrees(lon))
        }
    }
}

/** Requires repeated accurate fixes before declaring a deviation; retains route progress across crossings. */
class RouteDeviationMonitor(private val thresholdMeters: Double = 45.0, private val confirmations: Int = 3) {
    private var previous: RouteCoordinate? = null
    private var previousAlong: Double? = null
    private var outsideCount = 0
    private var activeRouteId: String? = null
    fun update(route: NavigationRoute, fix: RouteCoordinate, accuracyMeters: Float): RouteMath.Progress? {
        if(accuracyMeters > 35f || !accuracyMeters.isFinite() || accuracyMeters < 0f) return null
        if(activeRouteId != route.id) { reset(); activeRouteId = route.id }
        val moved = previous?.let { RouteMath.distance(it, fix) }
        val progress = RouteMath.progress(route, fix, thresholdMeters, previousAlong, moved)
        previous = fix
        if(progress.offRoute) outsideCount++ else outsideCount = 0
        previousAlong = progress.traveledMeters
        return progress.copy(offRoute = outsideCount >= confirmations)
    }
    fun reset() { previous = null; previousAlong = null; outsideCount = 0; activeRouteId = null }
}

/** A per-run circuit breaker for automatic requests. */
class AutoRerouteGate(private val cooldownMs: Long = 120_000L, private val maxAttempts: Int = 3) {
    private var lastAttemptMs = Long.MIN_VALUE
    private var attempts = 0
    private var inFlight = false
    fun tryBegin(nowMs: Long): Boolean {
        if(inFlight || attempts >= maxAttempts || (lastAttemptMs != Long.MIN_VALUE && nowMs - lastAttemptMs < cooldownMs)) return false
        inFlight = true; attempts++; lastAttemptMs = nowMs; return true
    }
    fun finish() { inFlight = false }
    fun reset() { lastAttemptMs = Long.MIN_VALUE; attempts = 0; inFlight = false }
}

interface RoutingBackend {
    suspend fun route(request: RouteRequest): List<NavigationRoute>
    suspend fun loop(start: RouteCoordinate, mode: RouteMode, targetMeters: Double): NavigationRoute
}
private object RoutingRequestGate {
    private val mutex = Mutex()
    private var previousRequestMs = 0L
    suspend fun waitTurn() = mutex.withLock {
        val now = System.currentTimeMillis()
        val wait = (1200L - (now - previousRequestMs)).coerceAtLeast(0L)
        if(wait > 0) delay(wait)
        previousRequestMs = System.currentTimeMillis()
    }
}
fun interface RoutingTransport { fun post(url: String, body: String): String }
class UrlConnectionRoutingTransport(private val connectTimeoutMs: Int = 8000, private val readTimeoutMs: Int = 15000) : RoutingTransport {
    override fun post(url: String, body: String): String {
        val connection = try { URL(url).openConnection() as HttpURLConnection } catch(e: Exception) { throw RoutingException("Не удалось подключиться к маршрутизатору", "network", e) }
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = connectTimeoutMs; connection.readTimeout = readTimeoutMs
            connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json; charset=utf-8"); connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "FitProgressAI/3.0 (+https://github.com/xatabihc/train)")
            connection.setRequestProperty("X-Client-Id", "FitProgressAI (github.com/xatabihc/train)")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            val stream = if(code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
                while(true) { val count = input.read(buffer); if(count < 0) break; total += count; if(total > 5_000_000) throw RoutingException("Ответ маршрутизатора слишком велик", "invalid_response"); out.write(buffer, 0, count) }
                out.toString(Charsets.UTF_8.name())
            }.orEmpty()
            if(code !in 200..299) throw RoutingException(when(code) { 429 -> "Маршрутизатор временно ограничил запросы"; in 500..599 -> "Сервис маршрутизации временно недоступен"; else -> "Маршрутизатор вернул ошибку HTTP $code" }, if(code == 429) "rate_limit" else "http_$code")
            return text
        } catch(e: RoutingException) { throw e } catch(e: Exception) { throw RoutingException("Нет ответа от сервера маршрутизации", "network", e) }
        finally { connection.disconnect() }
    }
}

class ValhallaRoutingBackend(baseUrl: String, private val transport: RoutingTransport = UrlConnectionRoutingTransport()) : RoutingBackend {
    private val endpoint: String
    init {
        val url = URL(baseUrl.trim())
        require(url.protocol in listOf("https", "http") && url.host.isNotBlank() && url.userInfo == null && url.query == null && url.ref == null) { "Укажите базовый адрес HTTP(S) маршрутизатора без параметров" }
        require(url.protocol == "https" || url.host in listOf("localhost", "127.0.0.1", "10.0.2.2") || url.host.startsWith("192.168.") || url.host.startsWith("10.")) { "Для удалённого сервера требуется HTTPS" }
        val base = baseUrl.trim().trimEnd('/')
        endpoint = if(base.endsWith("/route")) base else "$base/route"
    }
    override suspend fun route(request: RouteRequest): List<NavigationRoute> {
        require(request.locations.size in 2..10 && request.alternates in 0..3)
        request.locations.forEach { require(it.latitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude.isFinite() && it.longitude in -180.0..180.0) }
        val body = JsonObject().apply {
        add("locations", com.google.gson.JsonArray().also { arr -> request.locations.forEachIndexed { index, p -> arr.add(JsonObject().apply { addProperty("lat", p.latitude); addProperty("lon", p.longitude); addProperty("type", if(index == 0 || index == request.locations.lastIndex) "break" else "through") }) } })
            addProperty("costing", request.mode.costing); addProperty("units", "kilometers"); addProperty("language", "ru-RU"); addProperty("directions_type", "instructions")
            if(request.alternates > 0 && request.locations.size == 2) addProperty("alternates", request.alternates)
        }.toString()
        val response = try { RoutingRequestGate.waitTurn(); withContext(Dispatchers.IO) { transport.post(endpoint, body) } } catch(e: RoutingException) { throw e } catch(e: Exception) { throw RoutingException("Не удалось связаться с маршрутизатором", "network", e) }
        return ValhallaResponse.parse(response, request.mode)
    }
    override suspend fun loop(start: RouteCoordinate, mode: RouteMode, targetMeters: Double): NavigationRoute {
        require(targetMeters in 1000.0..50000.0)
        val attempts = listOf(0.82, 1.0, 1.18).mapNotNull { factor ->
            val via = RouteMath.loopWaypoints(start, targetMeters, mode, factor)
            try { route(RouteRequest(listOf(start) + via + start, mode)).firstOrNull()?.copy(name = "Круговой · ${mode.label}", targetMeters = targetMeters) }
            catch(e: CancellationException) { throw e }
            catch(e: RoutingException) { if(e.category in setOf("network", "rate_limit")) throw e else null }
        }
        if(attempts.isEmpty()) throw RoutingException("Не удалось построить круг по дорожной сети. Попробуйте другую точку или дистанцию.", "no_route")
        return attempts.minBy { abs(it.distanceMeters - targetMeters) }.also { best ->
            if(abs(best.distanceMeters - targetMeters) / targetMeters > 0.25) throw RoutingException("Не удалось построить круг близкой длины. Попробуйте другую дистанцию или точки.", "distance_unavailable")
        }
    }
}

data class GeocodedPlace(val name: String, val coordinate: RouteCoordinate)
fun interface GeocodingTransport { fun get(url: String): String }
class UrlConnectionGeocodingTransport : GeocodingTransport {
    override fun get(url: String): String {
        val connection = try { URL(url).openConnection() as HttpURLConnection } catch(e: Exception) { throw RoutingException("Не удалось подключиться к поиску адресов", "network", e) }
        try {
            connection.requestMethod = "GET"; connection.connectTimeout = 7000; connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("User-Agent", "FitProgressAI/3.0 (+https://github.com/xatabihc/train)")
            val code = connection.responseCode
            if(code !in 200..299) throw RoutingException(if(code == 429) "Поиск адресов временно ограничил запросы" else "Поиск адресов недоступен (HTTP $code)", if(code == 429) "rate_limit" else "http_$code")
            val stream = connection.inputStream
            return stream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096); var total = 0
                while(true) { val n = input.read(buffer); if(n < 0) break; total += n; if(total > 1_000_000) throw RoutingException("Ответ поиска адресов слишком велик", "invalid_response"); out.write(buffer, 0, n) }
                out.toString(Charsets.UTF_8.name())
            }
        } catch(e: RoutingException) { throw e } catch(e: Exception) { throw RoutingException("Нет ответа от поиска адресов", "network", e) } finally { connection.disconnect() }
    }
}
private object NominatimRequestGate {
    private val mutex = Mutex(); private var lastRequestMs = 0L
    suspend fun waitTurn() = mutex.withLock { val now = System.currentTimeMillis(); val wait = (1100L - (now - lastRequestMs)).coerceAtLeast(0); if(wait > 0) delay(wait); lastRequestMs = System.currentTimeMillis() }
}
class NominatimGeocoder(baseUrl: String = "https://nominatim.openstreetmap.org", private val transport: GeocodingTransport = UrlConnectionGeocodingTransport(), private val beforeRequest: suspend () -> Unit = { NominatimRequestGate.waitTurn() }) {
    private val endpoint = URL(baseUrl.trim()).let { url ->
        require(url.protocol in listOf("https", "http") && url.host.isNotBlank() && url.userInfo == null && url.query == null && url.ref == null)
        require(url.protocol == "https" || url.host in listOf("localhost", "127.0.0.1", "10.0.2.2") || url.host.startsWith("192.168.") || url.host.startsWith("10."))
        url.toString().trimEnd('/')
    }
    private val cache = LinkedHashMap<String, List<GeocodedPlace>>(16, .75f, true)
    suspend fun search(query: String): List<GeocodedPlace> {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        require(normalized.length in 3..120) { "Введите адрес длиной от 3 до 120 символов" }
        synchronized(cache) { cache[normalized.lowercase()]?.let { return it } }
        beforeRequest()
        val url = "$endpoint/search?format=jsonv2&limit=5&accept-language=ru&q=${URLEncoder.encode(normalized, "UTF-8")}"
        val places = try { parse(transport.get(url)) } catch(e: RoutingException) { throw e } catch(e: Exception) { throw RoutingException("Ответ геокодера повреждён", "invalid_response", e) }
        synchronized(cache) { cache[normalized.lowercase()] = places; while(cache.size > 50) cache.remove(cache.keys.first()) }
        return places
    }
    companion object {
        fun parse(json: String): List<GeocodedPlace> {
            val array = try { JsonParser.parseString(json).asJsonArray } catch(e: Exception) { throw RoutingException("Ответ геокодера повреждён", "invalid_response", e) }
            if(array.size() > 5) throw RoutingException("Геокодер вернул слишком много результатов", "invalid_response")
            return array.mapNotNull { node -> runCatching {
                val obj = node.asJsonObject; val name = obj.get("display_name").asString.take(300)
                val point = RouteCoordinate(obj.get("lat").asString.toDouble(), obj.get("lon").asString.toDouble())
                require(name.isNotBlank() && point.latitude.isFinite() && point.latitude in -90.0..90.0 && point.longitude.isFinite() && point.longitude in -180.0..180.0)
                GeocodedPlace(name, point)
            }.getOrNull() }
        }
    }
}

object ValhallaResponse {
    fun parse(json: String, mode: RouteMode): List<NavigationRoute> = try {
        val root = JsonParser.parseString(json).asJsonObject
        if(root.has("error")) throw RoutingException(root.get("error")?.asString ?: "Маршрут не найден", "no_route")
        val primary = parseTrip(root.getAsJsonObject("trip"), mode)
        val alternate = root.getAsJsonArray("alternates")?.mapNotNull { runCatching { parseTrip(it.asJsonObject.getAsJsonObject("trip"), mode).copy(name = "Альтернативный маршрут") }.getOrNull() }.orEmpty()
        listOf(primary) + alternate
    } catch(e: RoutingException) { throw e } catch(e: Exception) { throw RoutingException("Ответ маршрутизатора повреждён или не содержит маршрут", "invalid_response", e) }

    private fun parseTrip(trip: JsonObject?, mode: RouteMode): NavigationRoute {
        if(trip == null) throw RoutingException("Маршрут не найден", "no_route")
        val legs = trip.getAsJsonArray("legs") ?: throw RoutingException("Сервис не вернул геометрию маршрута", "invalid_response")
        val points = mutableListOf<RouteCoordinate>(); val instructions = mutableListOf<RouteInstruction>(); var length = 0.0
        legs.forEach { node ->
            val leg = node.asJsonObject; val summary = leg.getAsJsonObject("summary"); val legLength = summary?.get("length")?.asDouble?.times(1000.0) ?: 0.0
            val shape = leg.get("shape")?.asString ?: throw RoutingException("У маршрута отсутствует дорожная геометрия", "invalid_response")
            val decoded = Polyline6.decode(shape); require(decoded.size >= 2)
            points += decoded
            val legOffsets = DoubleArray(decoded.size); for(index in 1 until decoded.size) legOffsets[index] = legOffsets[index - 1] + RouteMath.distance(decoded[index - 1], decoded[index])
            val maneuvers = leg.getAsJsonArray("maneuvers")
            maneuvers?.forEach { m -> val obj = m.asJsonObject; val km = obj.get("length")?.asDouble ?: 0.0; val shapeIndex = (obj.get("begin_shape_index")?.asInt ?: 0).coerceIn(0, decoded.lastIndex); instructions += RouteInstruction(length + legOffsets[shapeIndex], km * 1000.0, obj.get("instruction")?.asString?.takeIf { it.isNotBlank() } ?: "Продолжайте по маршруту") }
            length += legLength
        }
        val correctedLength = if(length > 0) length else points.zipWithNext().sumOf { RouteMath.distance(it.first, it.second) }
        val route = NavigationRoute(name = "Маршрут · ${mode.label}", mode = mode.name, points = points, distanceMeters = correctedLength, instructions = instructions)
        RouteMath.validate(route); return route
    }
}

object Polyline6 {
    fun decode(encoded: String): List<RouteCoordinate> {
        val result = mutableListOf<RouteCoordinate>(); var index = 0; var lat = 0L; var lon = 0L
        fun component(): Long {
            var shift = 0; var value = 0L
            while(true) { require(index < encoded.length && shift <= 60) { "Повреждённая линия маршрута" }; val b = encoded[index++].code - 63; require(b in 0..63); value = value or ((b and 0x1f).toLong() shl shift); shift += 5; if(b < 0x20) break }
            return if(value and 1L != 0L) (value shr 1).inv() else value shr 1
        }
        while(index < encoded.length) { lat += component(); lon += component(); val a = lat / 1_000_000.0; val o = lon / 1_000_000.0; require(a in -90.0..90.0 && o in -180.0..180.0); result += RouteCoordinate(a, o) }
        return result
    }
}

object GpxCodec {
    fun importRoute(xml: String): NavigationRoute {
        require(xml.toByteArray(Charsets.UTF_8).size <= 10_000_000) { "GPX-файл больше 10 МБ" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; isXIncludeAware = false; isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false); setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", ""); setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val doc = try { factory.newDocumentBuilder().apply { setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
            override fun warning(e: org.xml.sax.SAXParseException) { throw e }
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        }) }.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))) } catch(e: Exception) { throw IllegalArgumentException("Не удалось прочитать GPX: файл повреждён или небезопасен", e) }
        require(doc.documentElement.localName == "gpx" || doc.documentElement.nodeName == "gpx") { "Ожидается GPX" }
        val name = elements(doc.documentElement, "name").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotBlank() } ?: "Импортированный GPX маршрут"
        val mode = elements(doc.documentElement, "type").firstOrNull()?.textContent?.trim()?.removePrefix("FitProgressAI:")?.takeIf { value -> RouteMode.entries.any { it.name == value } } ?: RouteMode.RUN.name
        val segments = mutableListOf<List<RouteCoordinate>>()
        elements(doc.documentElement, "trkseg").forEach { seg -> segments += elements(seg, "trkpt").map { coordinate(it, segments.size) } }
        if(segments.isEmpty()) { val points = elements(doc.documentElement, "rtept").map { coordinate(it, 0) }; if(points.isNotEmpty()) segments += points }
        val all = segments.filter { it.size >= 2 }.flatMapIndexed { i, xs -> xs.map { it.copy(segment = i) } }
        require(all.size in 2..100000) { "В GPX нет подходящего маршрута или слишком много точек" }
        val distance = all.zipWithNext().filter { it.first.segment == it.second.segment }.sumOf { RouteMath.distance(it.first, it.second) }
        val route = NavigationRoute(name = name.take(120), mode = mode, points = all, distanceMeters = distance, source = "GPX")
        RouteMath.validate(route); return route
    }
    fun exportRoute(route: NavigationRoute): String { RouteMath.validate(route); return buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\" creator=\"FitProgressAI\" xmlns=\"http://www.topografix.com/GPX/1/1\"><metadata><name>").append(escape(route.name)).append("</name></metadata><trk><name>").append(escape(route.name)).append("</name><type>FitProgressAI:").append(route.mode).append("</type>")
        route.points.groupBy { it.segment }.toSortedMap().values.forEach { points -> append("<trkseg>"); points.forEach { append("<trkpt lat=\"").append(it.latitude).append("\" lon=\"").append(it.longitude).append("\"/>") }; append("</trkseg>") }
        append("</trk></gpx>")
    } }
    fun exportRun(run: Run, name: String = "FitProgressAI GPS") : String {
        require(run.recordingMode == "gps" && !run.demo && run.points.size >= 2) { "Для GPX нужны реальные GPS-точки" }
        val route = NavigationRoute(name = name, mode = run.navigation?.mode ?: RouteMode.RUN.name, points = run.points.map { RouteCoordinate(it.lat, it.lon, it.segment) }, distanceMeters = run.distance.coerceAtLeast(1.0), source = "GPS")
        return exportRoute(route)
    }
    private fun coordinate(e: Element, segment: Int): RouteCoordinate {
        val lat = e.getAttribute("lat").toDoubleOrNull() ?: error("У точки GPX нет широты"); val lon = e.getAttribute("lon").toDoubleOrNull() ?: error("У точки GPX нет долготы")
        require(lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0)
        return RouteCoordinate(lat, lon, segment)
    }
    private fun elements(parent: org.w3c.dom.Node, local: String): List<Element> {
        val out = mutableListOf<Element>(); val nodes = (parent as? org.w3c.dom.Document)?.getElementsByTagNameNS("*", local) ?: (parent as? Element)?.getElementsByTagNameNS("*", local) ?: return out
        for(i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(out::add)
        if(out.isEmpty()) { val fallback = if(parent is org.w3c.dom.Document) parent.getElementsByTagName(local) else (parent as Element).getElementsByTagName(local); for(i in 0 until fallback.length) (fallback.item(i) as? Element)?.let(out::add) }
        return out
    }
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
