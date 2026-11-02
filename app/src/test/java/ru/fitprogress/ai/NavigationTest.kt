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

    private fun encodePolyline6(points: List<Pair<Double, Double>>): String {
        var lat = 0L; var lon = 0L
        fun encode(value: Long): String {
            var v = if(value < 0) (value shl 1).inv() else value shl 1
            return buildString { while(v >= 0x20) { append(((0x20L or (v and 0x1f)) + 63L).toInt().toChar()); v = v shr 5 }; append((v + 63L).toInt().toChar()) }
        }
        return buildString { points.forEach { (a, o) -> val nextLat = (a * 1_000_000).toLong(); val nextLon = (o * 1_000_000).toLong(); append(encode(nextLat - lat)); append(encode(nextLon - lon)); lat = nextLat; lon = nextLon } }
    }
}
