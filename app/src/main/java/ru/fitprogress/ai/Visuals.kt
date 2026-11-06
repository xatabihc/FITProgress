package ru.fitprogress.ai

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

@Composable fun ExerciseArt(e: Exercise, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, e.imageUri) {
        value = if(e.imageUri.isBlank()) null else withContext(Dispatchers.IO) { runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(Uri.parse(e.imageUri)).use { BitmapFactory.decodeStream(it, null, options) }
            require(options.outWidth in 1..40000 && options.outHeight in 1..40000)
            var sample = 1
            while(max(options.outWidth, options.outHeight) / sample > 1200) sample *= 2
            context.contentResolver.openInputStream(Uri.parse(e.imageUri)).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() }
        }.getOrNull() }
    }
    if(bitmap != null) Image(bitmap!!, e.name, modifier, contentScale = ContentScale.Crop)
    else {
        val accent = MaterialTheme.colorScheme.primary; val bg = MaterialTheme.colorScheme.surfaceVariant
        Canvas(modifier.background(bg, RoundedCornerShape(16.dp))) {
            val sx = size.width / 100; val sy = size.height / 100
            fun point(x: Float, y: Float) = Offset(x * sx, y * sy)
            fun bone(ax: Float, ay: Float, bx: Float, by: Float) = drawLine(accent, point(ax, ay), point(bx, by), 4 * sx, StrokeCap.Round)
            drawLine(accent.copy(alpha = 0.25f), point(10f, 88f), point(90f, 88f), sx)
            if(e.category == "Грудь" || e.category == "Пресс") {
                drawCircle(accent, 7 * sx, point(76f, 42f)); bone(65f, 48f, 34f, 59f); bone(34f, 59f, 13f, 79f); bone(65f, 48f, 71f, 77f); bone(71f, 77f, 85f, 80f)
            } else if(e.category == "Ноги") {
                drawCircle(accent, 7 * sx, point(52f, 22f)); bone(50f, 33f, 44f, 56f); bone(44f, 56f, 67f, 65f); bone(67f, 65f, 60f, 84f); bone(44f, 56f, 26f, 69f); bone(26f, 69f, 35f, 84f); bone(49f, 37f, 77f, 40f)
            } else {
                drawCircle(accent, 7 * sx, point(52f, 20f)); bone(50f, 31f, 43f, 58f); bone(43f, 58f, 29f, 84f); bone(43f, 58f, 66f, 75f); bone(66f, 75f, 80f, 75f); bone(49f, 37f, 68f, 47f); bone(68f, 47f, 81f, 34f); bone(49f, 37f, 30f, 48f); bone(30f, 48f, 19f, 35f)
            }
        }
    }
}
@Composable fun LineChart(values: List<Double>, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        val color = MaterialTheme.colorScheme.primary; val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        if(values.isEmpty()) Text("Недостаточно данных для графика", style = MaterialTheme.typography.bodySmall)
        else {
            Canvas(Modifier.fillMaxWidth().height(140.dp).padding(8.dp)) {
                repeat(4) { val y = size.height * it / 3; drawLine(grid, Offset(0f, y), Offset(size.width, y)) }
                val high = (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
                val points = values.mapIndexed { i, value -> Offset(if(values.size == 1) size.width / 2 else size.width * i / (values.size - 1), size.height * (1 - value / high).toFloat()) }
                points.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, 4f, StrokeCap.Round) }; points.forEach { drawCircle(color, 4f, it) }
            }
            Text("Минимум: %.1f · максимум: %.1f".format(values.minOrNull(), values.maxOrNull()), style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable fun RouteFallback(points: List<GeoPoint>) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(190.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)).padding(16.dp)) {
        if(points.isNotEmpty()) {
            val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }; val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
            val centerLat = (minLat + maxLat) / 2; val xScale = cos(Math.toRadians(centerLat))
            val dx = ((maxLon - minLon) * xScale).coerceAtLeast(0.00001); val dy = (maxLat - minLat).coerceAtLeast(0.00001)
            val scale = min(size.width / dx, size.height / dy)
            fun xy(p: GeoPoint) = Offset((size.width / 2 + ((p.lon - (minLon + maxLon) / 2) * xScale) * scale).toFloat(), (size.height / 2 - (p.lat - centerLat) * scale).toFloat())
            points.zipWithNext().filter { it.first.segment == it.second.segment }.forEach { (a, b) -> drawLine(color, xy(a), xy(b), 5f, StrokeCap.Round) }
            drawCircle(Color.White, 7f, xy(points.first())); drawCircle(color, 7f, xy(points.last()))
        }
    }
}
