package app.radd.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.radd.android.Thumbs
import app.radd.model.RecoverableItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Every list and screen has a visible, honest state for "nothing here" and "something went wrong". */
@Composable
fun EmptyState(title: String, body: String, actionLabel: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.ImageSearch, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorPanel(title: String, body: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier, secondaryLabel: String? = null, onSecondary: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.ErrorOutline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onAction) { Text(actionLabel) }
        if (secondaryLabel != null) TextButton(onSecondary) { Text(secondaryLabel) }
    }
}

/** A thumbnail decoded off the main thread from the item's byte range. A file that cannot be decoded shows a broken-image tile. */
@Composable
fun FileThumb(item: RecoverableItem, modifier: Modifier = Modifier, maxPx: Int = 360, contentScale: ContentScale = ContentScale.Crop) {
    var bitmap by remember(item.originPath, item.offset, item.length, maxPx) { mutableStateOf(Thumbs.cached(item, maxPx)) }
    var failed by remember(item.originPath, item.offset, item.length, maxPx) { mutableStateOf(false) }
    LaunchedEffect(item.originPath, item.offset, item.length, maxPx) {
        if (bitmap == null) {
            val b = withContext(Dispatchers.IO) { Thumbs.load(item, maxPx) }
            if (b == null) failed = true else bitmap = b
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = contentScale)
        else if (failed) Icon(Icons.Filled.BrokenImage, "تعذّر عرض الصورة", tint = MaterialTheme.colorScheme.outline)
    }
}

/** Pinch to zoom, double-tap to toggle. While zoomed the parent pager must not swipe, so the zoom state is reported up. */
@Composable
@androidx.compose.foundation.ExperimentalFoundationApi
fun ZoomableThumb(item: RecoverableItem, onZoomed: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        if (scale > 1f) { offsetX += pan.x; offsetY += pan.y } else { offsetX = 0f; offsetY = 0f }
        onZoomed(scale > 1.02f)
    }
    Box(
        modifier.clip(RoundedCornerShape(14.dp))
            .transformable(state, canPan = { scale > 1f })
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offsetX = 0f; offsetY = 0f } else scale = 2.5f
                    onZoomed(scale > 1.02f)
                })
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX; translationY = offsetY },
    ) { FileThumb(item, Modifier.fillMaxSize(), maxPx = 1600, contentScale = ContentScale.Fit) }
}
