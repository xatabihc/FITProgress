package ru.fitprogress.ai

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Immersive map for choosing route points. The background route builder remains intact;
 * taps and crosshair confirmation use the same callback as the embedded map.
 */
@Composable
fun FullscreenRouteMap(
    selectedRole: String,
    loop: Boolean,
    markers: List<RouteCoordinate>,
    currentLocation: RouteCoordinate?,
    initialCenter: RouteCoordinate?,
    plannedRoute: NavigationRoute?,
    startSelected: Boolean,
    finishSelected: Boolean,
    waypointCount: Int,
    onRoleSelected: (String) -> Unit,
    onPointSelected: (RouteCoordinate) -> Unit,
    onCameraIdle: (RouteCoordinate) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Карта маршрута", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Закрыть полноэкранную карту") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("start" to "Старт", "finish" to "Финиш", "via" to "Точка +")
                        .filter { !loop || it.first != "finish" }
                        .forEach { (role, label) ->
                            FilterChip(
                                selected = selectedRole == role,
                                onClick = { onRoleSelected(role) },
                                label = { Text(label) }
                            )
                        }
                }
                Text(
                    "Старт: ${if(startSelected) "выбран" else "нет"} · Финиш: ${if(finishSelected) "выбран" else "нет"} · Промежуточных: $waypointCount",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Коснитесь карты либо передвиньте её под «+» и подтвердите точку. Для возврата нажмите ×.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    MapPanel(
                        points = emptyList(),
                        plannedRoute = plannedRoute,
                        selectionEnabled = true,
                        markers = markers,
                        currentLocation = currentLocation,
                        initialCenter = initialCenter,
                        fullscreen = true,
                        modifier = Modifier.fillMaxSize(),
                        onCameraIdle = onCameraIdle,
                        onMapTap = onPointSelected
                    )
                }
            }
        }
    }
}
