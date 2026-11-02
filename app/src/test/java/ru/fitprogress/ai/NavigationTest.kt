package ru.fitprogress.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NavigationTest {
    private fun route() = NavigationRoute(name = "Тестовый путь", points = listOf(
        RouteCoordinate(55.0, 37.0), RouteCoordinate(55.001, 37.0), RouteCoordinate(55.002, 37.0)
    ), distanceMeters = 222.4)

    @Test fun distanceProgressAndDeviationUseRoadRouteGeometry() {
        val r = route()
        val onRoute = RouteMath.progress(r, RouteCoordinate(55.001, 37.00001))
        assertTrue(onRoute.distanceFromRouteMeters < 5)
        assertFalse(onRoute.offRoute)
        assertTrue(onRoute.remainingMeters in 100.0..130.0)
        val offRoute = RouteMath.progress(r, RouteCoordinate(55.001, 37.002))
        assertTrue(offRoute.offRoute)
        assertTrue(offRoute.distanceFromRouteMeters > 100)
        assertTrue(RouteMath.distance(r.points.first(), r.points.last()) > 200)
    }

    @Test fun routeMatcherKeepsProgressOnEarlierLegAtSelfIntersection() {
        val r = NavigationRoute(points = listOf(
            RouteCoordinate(0.0, -0.002), RouteCoordinate(0.0, 0.0), RouteCoordinate(0.002, 0.0),
            RouteCoordinate(0.0, 0.0), RouteCoordinate(0.0, 0.002)
        ), distanceMeters = 900.0)
        val crossing = RouteCoordinate(0.000001, 0.0)
        val progress = RouteMath.progress(r, crossing, previousAlongMeters = 220.0, movedMeters = 5.0)
        assertTrue("matcher jumped to later crossing leg: ${progress.traveledMeters}", progress.traveledMeters < 300.0)
    }

    @Test fun deviationRequiresThreeAccurateFixesAndIgnoresPoorAccuracy() {
        val monitor = RouteDeviationMonitor()
        val r = route()
        val far = RouteCoordinate(55.001, 37.003)
        assertFalse(monitor.update(r, far, 12f)!!.offRoute)
        assertNull(monitor.update(r, far, 60f))
        assertFalse(monitor.update(r, far, 10f)!!.offRoute)
        assertTrue(monitor.update(r, far, 10f)!!.offRoute)
        assertFalse(monitor.update(r, RouteCoordinate(55.001, 37.0), 9f)!!.offRoute)
    }

    @Test fun rerouteGateLimitsConcurrencyFrequencyAndAttempts() {
        val gate = AutoRerouteGate(cooldownMs = 1000, maxAttempts = 2)
        assertTrue(gate.tryBegin(100))
        assertFalse(gate.tryBegin(101))
        gate.finish()
        assertFalse(gate.tryBegin(1099))
        assertTrue(gate.tryBegin(1100)); gate.finish()
        assertFalse(gate.tryBegin(2100))
        gate.reset()
        assertTrue(gate.tryBegin(0))
    }

    @Test fun nominatimParserValidatesAndReturnsRussianAddressCoordinates() {
        val results = NominatimGeocoder.parse("""[{"display_name":"Парк, Москва","lat":"55.75","lon":"37.61"},{"display_name":"bad","lat":"95","lon":"0"}]""")
        assertEquals(1, results.size)
        assertEquals("Парк, Москва", results.single().name)
        assertEquals(37.61, results.single().coordinate.longitude, 0.00001)
        assertThrows(RoutingException::class.java) { NominatimGeocoder.parse("not json") }
    }

    @Test fun nominatimSearchIsManualCachedAndEscapesQuery() = runBlocking {
        var requests = 0; var requestedUrl = ""
        val geocoder = NominatimGeocoder("https://geo.example", { url -> requests++; requestedUrl = url; """[{"display_name":"Москва","lat":"55.7","lon":"37.6"}]""" }, {})
        val first = geocoder.search("Москва, парк")
        val second = geocoder.search("Москва, парк")
        assertEquals(first, second); assertEquals(1, requests); assertTrue(requestedUrl.contains("%2C"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { geocoder.search("x") } }
        Unit
    }

    @Test fun routeRejectsInvalidAndTooShortCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            RouteMath.validate(route().copy(points = listOf(RouteCoordinate(0.0, 0.0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RouteMath.validate(route().copy(points = listOf(RouteCoordinate(100.0, 0.0), RouteCoordinate(0.0, 0.0))))
        }
    }

    @Test fun gpxRoundTripPreservesNameSegmentsAndDistance() {
        val source = route().copy(name = "Парк & набережная", mode = RouteMode.CYCLE.name)
        val xml = GpxCodec.exportRoute(source)
        val loaded = GpxCodec.importRoute(xml)
        assertEquals(source.name, loaded.name)
        assertEquals(source.points.size, loaded.points.size)
        assertEquals(source.distanceMeters, loaded.distanceMeters, 0.1)
        assertEquals("GPX", loaded.source)
        assertEquals(RouteMode.CYCLE.name, loaded.mode)
    }

    @Test fun gpxRejectsMalformedCoordinatesAndExternalEntities() {
        val bad = "<gpx><rtept lat=\"91\" lon=\"0\"/><rtept lat=\"0\" lon=\"0\"/></gpx>"
        assertThrows(IllegalArgumentException::class.java) { GpxCodec.importRoute(bad) }
        val xxe = "<!DOCTYPE gpx [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><gpx><name>&x;</name></gpx>"
        assertThrows(IllegalArgumentException::class.java) { GpxCodec.importRoute(xxe) }
    }

    @Test fun versionFourBackupKeepsSavedRoutesAndOlderVersionsRemainReadable() {
        val current = Backup(savedRoutes = listOf(route()))
        val restored = BackupCodec.decode(BackupCodec.checkedExport(current))
        assertEquals(1, restored.savedRoutes.size)
        assertEquals(route().mode, restored.savedRoutes.single().mode)
        val oldRoot = BackupCodec.gson.toJsonTree(Backup(version = 3)).asJsonObject.apply { remove("savedRoutes") }
        val oldDigest = java.security.MessageDigest.getInstance("SHA-256").digest(BackupCodec.gson.toJson(oldRoot).toByteArray()).joinToString("") { "%02x".format(it) }
        oldRoot.addProperty("sha256", oldDigest)
        assertTrue(BackupCodec.decode(BackupCodec.gson.toJson(oldRoot)).savedRoutes.isEmpty())
    }

    @Test fun valhallaBackendReportsOfflineAndSerializesRealRoutingRequest() = runBlocking {
        var requestBody = ""
        val unavailable = ValhallaRoutingBackend("https://routing.example") { _, _ -> throw RoutingException("Офлайн", "network") }
        val offline = assertThrows(RoutingException::class.java) {
            runBlocking { unavailable.route(RouteRequest(listOf(RouteCoordinate(55.0, 37.0), RouteCoordinate(55.01, 37.01)), RouteMode.CYCLE)) }
        }
        assertEquals("network", offline.category)

        val polyline = encodePolyline6(listOf(55.0 to 37.0, 55.001 to 37.001))
        val backend = ValhallaRoutingBackend("https://routing.example") { _, body ->
            requestBody = body
            """{"trip":{"summary":{"length":0.2},"legs":[{"summary":{"length":0.2},"shape":"$polyline","maneuvers":[{"length":0.2,"begin_shape_index":0,"instruction":"Продолжайте"}]}]}}"""
        }
        val result = backend.route(RouteRequest(listOf(RouteCoordinate(55.0, 37.0), RouteCoordinate(55.01, 37.01)), RouteMode.CYCLE)).single()
        assertEquals(RouteMode.CYCLE.name, result.mode)
        assertEquals(200.0, result.distanceMeters, 0.01)
        assertTrue(requestBody.contains("\"costing\":\"bicycle\""))
        assertTrue(requestBody.contains("ru-RU"))
    }

    @Test fun valhallaErrorsAreClassifiedWithoutInventingRouteGeometry() {
        val noRoute = assertThrows(RoutingException::class.java) { ValhallaResponse.parse("""{"error":"No suitable edges"}""", RouteMode.RUN) }
        assertEquals("no_route", noRoute.category)
        val malformed = assertThrows(RoutingException::class.java) { ValhallaResponse.parse("""{"trip":{"legs":[]}}""", RouteMode.WALK) }
        assertEquals("invalid_response", malformed.category)
    }

    private fun encodePolyline6(points: List<Pair<Double, Double>>): String {
        var lat = 0L; var lon = 0L
        fun encode(value: Long): String {
            var v = if(value < 0) (value shl 1).inv() else value shl 1
            return buildString { while(v >= 0x20) { append(((0x20L or (v and 0x1f)) + 63L).toInt().toChar()); v = v shr 5 }; append((v + 63L).toInt().toChar()) }
        }
        return buildString { points.forEach { (a, o) -> val nextLat = (a * 1_000_000).toLong(); val nextLon = (o * 1_000_000).toLong(); append(encode(nextLat - lat)); append(encode(nextLon - lon)); lat = nextLat; lon = nextLon } }
    }
}
