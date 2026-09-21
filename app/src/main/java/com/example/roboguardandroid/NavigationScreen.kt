package com.example.roboguardandroid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * The robot's "Navigation and Map" screen on the phone.
 *
 * It shows what the robot reports (map, its position, saved places, private areas, the event log) and sends the same
 * commands the robot's own screen sends. Nothing is decided here: the robot checks the privacy rules, refuses targets in
 * private areas and stops on its own — the phone only asks.
 *
 * Reachable only inside the local network; when the robot server cannot be found, the last known state stays on screen
 * with a clear offline banner instead of an empty map ([NavConnection]).
 */
@Composable
fun NavigationScreen(apiRob: RobotAPI, onBack: () -> Unit) {
    val client = remember { NavigationClient(apiRob) }
    val scope = rememberCoroutineScope()

    // The poll loop lives as long as this screen; leaving it stops polling but NOT the robot's drive.
    LaunchedEffect(client) { client.run() }

    val state by client.state.collectAsState()
    val connection by client.connection.collectAsState()
    val mapInfo by client.mapInfo.collectAsState()
    val mapImage by client.mapImage.collectAsState()
    val message by client.message.collectAsState()

    /** Name dialog: which command it belongs to, plus the suggested name. */
    var askName by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showLog by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    fun send(action: String, build: NavigationClient.JsonObjectBuilderScope.() -> Unit = {}) {
        scope.launch { client.command(action, build) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HeaderAppName()

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text("Navigation and Map", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            ConnectionDot(connection)
        }

        ConnectionBanner(connection) { client.retry() }

        val s = state
        if (s != null && !s.running) {
            Warning("The navigation is not running on the robot: ${s.error ?: "unknown reason"}")
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                MapView(state, mapInfo, mapImage) { x, y -> send("point") { at(x, y) } }
            }

            item { StatusLines(state) }

            item {
                // Driving: one big target button and a red STOP, like on the robot.
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { send("drive") },
                        enabled = state?.selected != null,
                        modifier = Modifier.weight(1f)
                    ) { Text("Drive to ${state?.selected?.name ?: "…"}") }
                    Button(
                        onClick = { send("stop") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        modifier = Modifier.height(48.dp)
                    ) { Text("STOP", fontWeight = FontWeight.Bold, color = Color.White) }
                }
            }

            item {
                Section("Speed") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("SLOW" to "Slow", "MEDIUM" to "Medium", "DEFAULT" to "Robot default").forEach { (key, label) ->
                            val active = state?.speed == key
                            Button(
                                onClick = { send("speed") { preset(key) } },
                                colors = if (active) ButtonDefaults.buttonColors()
                                else ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1A73E8)),
                                modifier = Modifier.weight(1f)
                            ) { Text(label, fontSize = 12.sp) }
                        }
                    }
                }
            }

            item {
                Section("Places on the map") {
                    val points = (state?.places.orEmpty() + state?.points.orEmpty())
                    if (points.isEmpty()) Text("No places yet.", fontSize = 13.sp)
                    points.forEach { point ->
                        val selected = state?.selected?.name == point.name
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { send("select") { name(point.name) } },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    "${if (selected) "▶ " else ""}${point.name}  (%.2f, %.2f)".format(point.x, point.y),
                                    fontSize = 13.sp
                                )
                            }
                            if (selected && point.persistent) {
                                OutlinedButton(onClick = { send("deleteLocation") }) { Text("Delete", fontSize = 12.sp) }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { askName = "saveLocation" to "" },
                            enabled = state?.localized == true
                        ) { Text("Save position", fontSize = 12.sp) }
                        OutlinedButton(onClick = { send("clearPoints") }) { Text("Clear tapped", fontSize = 12.sp) }
                        OutlinedButton(onClick = { send("reload"); client.forgetMap() }) { Text("Reload map", fontSize = 12.sp) }
                    }
                }
            }

            item {
                PrivateAreas(state, onAction = { action, name -> send(action) { name(name) } }, onAsk = { askName = it })
            }

            item {
                OutlinedButton(onClick = { showLog = !showLog }) {
                    Text(if (showLog) "Hide robot log" else "Show robot log", fontSize = 13.sp)
                }
            }
            if (showLog) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                            .background(Color(0xFFF2F2F2), RoundedCornerShape(6.dp))
                            .padding(6.dp).verticalScroll(rememberScrollState())
                    ) {
                        state?.log.orEmpty().forEach { Text(it, fontSize = 11.sp) }
                    }
                }
            }
        }
    }

    // The robot asks whether it may cross a private area; the phone may answer it just like the robot's own screen.
    state?.question?.let { question ->
        CrossingDialog(
            area = question.area,
            onAllow = { minutes -> send("answerPrivacy") { id(question.id); minutes(minutes) } },
            onDeny = { send("answerPrivacy") { id(question.id) } }
        )
    }

    askName?.let { (action, suggestion) ->
        NameDialog(
            title = when (action) {
                "saveLocation" -> "Name for this position"
                "areaCircle" -> "Name for the private area"
                else -> "Name for the drawn area"
            },
            suggestion = suggestion,
            onCancel = { askName = null },
            onConfirm = { typed -> askName = null; send(action) { name(typed) } }
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { client.clearMessage() },
            confirmButton = { Button(onClick = { client.clearMessage() }) { Text("OK") } },
            title = { Text("The robot says") },
            text = { Text(text) },
            containerColor = Color.White
        )
    }
}

/** Green/red dot plus the state word, so the connection is visible without reading the banner. */
@Composable
private fun ConnectionDot(connection: NavConnection) {
    val (color, label) = when (connection) {
        is NavConnection.Online -> Color(0xFF2E7D32) to "connected"
        is NavConnection.Connecting -> Color(0xFF9E9E9E) to "connecting"
        is NavConnection.Offline -> Color.Red to "offline"
        is NavConnection.Refused -> Color(0xFFEF6C00) to "refused"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(10.dp).background(color, RoundedCornerShape(5.dp)))
        Text(label, fontSize = 12.sp, color = color)
    }
}

/**
 * What to do when the robot cannot be found. This is the common case in a flat: phone on mobile data, robot switched off,
 * or a different WiFi — so it says which of those to check instead of only "error".
 */
@Composable
private fun ConnectionBanner(connection: NavConnection, onRetry: () -> Unit) {
    when (connection) {
        is NavConnection.Offline -> {
            val seconds = ((System.currentTimeMillis() - connection.since) / 1000).coerceAtLeast(0)
            Warning(
                "The robot server was not found (${connection.reason}).\n" +
                    "Check that the phone is in the same WiFi as the robot and that the robot is switched on. " +
                    "Showing the last known state, ${seconds}s old.",
                onRetry
            )
        }
        is NavConnection.Refused -> {
            // 401 is the one worth explaining: the pairing is gone, so nothing on this screen will work until it is redone.
            val extra = if (connection.code == 401) " The pairing with this robot is no longer valid; pair again." else ""
            Warning("The robot refused the request (${connection.code}).$extra ${connection.reason}", onRetry)
        }
        else -> {}
    }
}

@Composable
private fun Warning(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(8.dp)
            .background(Color(0xFFFFE5E5), RoundedCornerShape(6.dp)).padding(8.dp)
    ) {
        Text(text, fontSize = 13.sp, color = Color(0xFFB00020))
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) { Text("Try again") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        content()
    }
}

/** Map name, localization, SDK control, what the robot is doing, and who is steering it. */
@Composable
private fun StatusLines(state: NavState?) {
    Column {
        Text("Map: ${state?.map ?: "—"}", fontSize = 13.sp)
        Text(
            "Localized: ${state?.localized?.let { if (it) "yes" else "no" } ?: "?"}   " +
                "SDK control: ${if (state?.sdkControl == true) "yes" else "no"}",
            fontSize = 13.sp,
            // Without localization the robot refuses to drive, so it is worth showing in red.
            color = if (state?.localized == false) Color.Red else Color.Unspecified
        )
        Text("Navigation: ${state?.navState.orEmpty()}", fontSize = 13.sp)
        state?.pose?.let { Text("Position: (%.2f, %.2f)".format(it.x, it.y), fontSize = 13.sp) }
        state?.remoteControl?.let { Text("Steered from: $it", fontSize = 12.sp, color = Color(0xFF1A73E8)) }
        state?.areaStoreError?.let { Text("Private areas could not be read: $it", fontSize = 13.sp, color = Color.Red) }
        state?.areaSaveWarning?.let { Text(it, fontSize = 13.sp, color = Color.Red) }
        state?.locationStoreError?.let { Text("Saved places could not be read: $it", fontSize = 13.sp, color = Color.Red) }
    }
}

/**
 * The robot's map with its position, the places and the private areas. A tap sends "point" to the robot: it becomes a
 * target there, or the next corner while an area is being drawn — exactly what a tap does on the robot's own screen.
 */
@Composable
private fun MapView(
    state: NavState?,
    info: NavMapInfo?,
    image: android.graphics.Bitmap?,
    onTap: (Double, Double) -> Unit
) {
    if (info == null || image == null) {
        Box(
            modifier = Modifier.fillMaxWidth().height(180.dp).background(Color(0xFFEEEEEE), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) { Text("No map picture yet", fontSize = 13.sp) }
        return
    }
    val ratio = if (info.heightPx > 0) info.widthPx.toFloat() / info.heightPx else 1f
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio)
            .background(Color(0xFFEEEEEE), RoundedCornerShape(6.dp))
            .pointerInput(info) {
                detectTapGestures { offset ->
                    // Picture pixel -> robot coordinates; the top row of the picture is the highest y.
                    val fx = offset.x / size.width
                    val fy = offset.y / size.height
                    onTap(info.minX + fx * (info.maxX - info.minX), info.maxY - fy * (info.maxY - info.minY))
                }
            }
    ) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = "Map of the robot",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            fun px(x: Double) = ((x - info.minX) / (info.maxX - info.minX) * size.width).toFloat()
            fun py(y: Double) = ((info.maxY - y) / (info.maxY - info.minY) * size.height).toFloat()

            // Private areas (red), the one being drawn in blue.
            state?.areas.orEmpty().forEach { area ->
                if (area.corners.size < 2) return@forEach
                val path = Path().apply {
                    moveTo(px(area.corners[0].x), py(area.corners[0].y))
                    area.corners.drop(1).forEach { lineTo(px(it.x), py(it.y)) }
                    close()
                }
                // An area the robot may currently cross is drawn orange, so a granted permission is visible.
                val color = if (area.allowedUntil != null) Color(0xFFFF9100) else Color.Red
                drawPath(path, color.copy(alpha = 0.25f))
                drawPath(path, color, style = Stroke(width = 2f))
            }
            state?.drawing?.let { corners ->
                corners.forEach { drawCircle(Color(0xFF1A73E8), radius = 5f, center = androidx.compose.ui.geometry.Offset(px(it.x), py(it.y))) }
                if (corners.size >= 2) {
                    val path = Path().apply {
                        moveTo(px(corners[0].x), py(corners[0].y))
                        corners.drop(1).forEach { lineTo(px(it.x), py(it.y)) }
                    }
                    drawPath(path, Color(0xFF1A73E8), style = Stroke(width = 2f))
                }
            }

            state?.places.orEmpty().forEach {
                drawCircle(Color(0xFF2E7D32), radius = 6f, center = androidx.compose.ui.geometry.Offset(px(it.x), py(it.y)))
            }
            state?.points.orEmpty().forEach {
                drawCircle(Color(0xFF7B1FA2), radius = 6f, center = androidx.compose.ui.geometry.Offset(px(it.x), py(it.y)))
            }
            state?.selected?.let {
                drawCircle(Color(0xFF1A73E8), radius = 10f, style = Stroke(width = 3f),
                    center = androidx.compose.ui.geometry.Offset(px(it.x), py(it.y)))
            }

            // The robot: a dot with a line in its heading direction.
            state?.pose?.let { pose ->
                val center = androidx.compose.ui.geometry.Offset(px(pose.x), py(pose.y))
                drawCircle(Color.Black, radius = 7f, center = center)
                val nose = androidx.compose.ui.geometry.Offset(
                    px(pose.x + 0.4 * cos(pose.theta)),
                    py(pose.y + 0.4 * sin(pose.theta))
                )
                drawLine(Color.Black, center, nose, strokeWidth = 3f)
            }
        }
    }
}

/** Private areas: create, delete, and end a temporary crossing permission early. */
@Composable
private fun PrivateAreas(
    state: NavState?,
    onAction: (String, String) -> Unit,
    onAsk: (Pair<String, String>) -> Unit
) {
    val drawing = state?.drawing
    Section("Private areas") {
        if (state?.areasLoaded == false) {
            Text("Not loaded yet — the robot refuses to drive until they are.", fontSize = 12.sp, color = Color.Red)
        }
        state?.areas.orEmpty().forEach { area ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(area.name, fontSize = 13.sp)
                    area.allowedUntil?.let { until ->
                        // Counted against the ROBOT's clock, so the countdown does not depend on the phone's time.
                        val left = ((until - (state?.now ?: 0L)) / 1000).coerceAtLeast(0)
                        Text("temporarily allowed, %d:%02d left".format(left / 60, left % 60), fontSize = 11.sp, color = Color(0xFFFF9100))
                    }
                }
                if (area.allowedUntil != null) {
                    OutlinedButton(onClick = { onAction("revokeArea", area.name) }) { Text("✕", fontSize = 12.sp) }
                }
                OutlinedButton(onClick = { onAction("deleteArea", area.name) }) { Text("Delete", fontSize = 12.sp) }
            }
        }
        if (state?.areas.orEmpty().isEmpty()) Text("No private areas on this map.", fontSize = 13.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (drawing == null) {
                OutlinedButton(
                    onClick = { onAsk("areaCircle" to "") },
                    enabled = state?.selected != null
                ) { Text("Circle around selection", fontSize = 12.sp) }
                OutlinedButton(onClick = { onAction("drawStart", "") }) { Text("Draw area", fontSize = 12.sp) }
            } else {
                OutlinedButton(onClick = { onAction("drawUndo", "") }) { Text("Undo", fontSize = 12.sp) }
                OutlinedButton(onClick = { onAction("drawCancel", "") }) { Text("Cancel", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { onAsk("drawFinish" to "") },
                    enabled = drawing.size >= 3
                ) { Text("Finish (${drawing.size})", fontSize = 12.sp) }
            }
        }
        if (drawing != null) {
            Text("Tap the map to add corners of the area.", fontSize = 12.sp)
        }
    }
}

/** The robot's "may I cross this area?" question, answered from the phone. */
@Composable
private fun CrossingDialog(area: String, onAllow: (Int) -> Unit, onDeny: () -> Unit) {
    var minutes by remember { mutableStateOf(5) }
    AlertDialog(
        onDismissRequest = { /* must be answered: ignoring it would leave the robot standing */ },
        title = { Text("Allow crossing \"$area\"?") },
        text = {
            Column {
                Text("The robot's way goes through this private area. Yes allows it for $minutes minutes.", fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf(1, 2, 5, 10, 60).forEach { value ->
                        OutlinedButton(onClick = { minutes = value }, contentPadding = PaddingValues(8.dp)) {
                            Text(if (value == minutes) "[$value]" else "$value", fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAllow(minutes) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) { Text("Yes") }
        },
        dismissButton = {
            Button(onClick = onDeny, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("No") }
        },
        containerColor = Color.White
    )
}

/** Asks for a name (saved position, private area) and lets the robot check it — names must be unique there. */
@Composable
private fun NameDialog(title: String, suggestion: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(suggestion) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("OK") } },
        dismissButton = { OutlinedButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = Color.White
    )
}
