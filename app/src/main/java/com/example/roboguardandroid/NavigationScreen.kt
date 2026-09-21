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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.roboguardandroid.ui.theme.RoboGuardAndroidTheme
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
 * Same look as the rest of the app: the blue header bar, ordinary buttons for ordinary actions, red for stopping and
 * deleting, green for a yes (see [RoboGuardColors]). All texts come from `assets/texts/texts.json`.
 *
 * Like the robot's own screen it starts plain: the status lines and the robot's log only appear behind the "Show debug"
 * switch at the bottom. Problems that stop the robot from driving (unreadable private areas, no localization) are shown
 * without the switch, because they explain why nothing happens.
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

    /** Name dialog: which command it belongs to. */
    var askName by remember { mutableStateOf<String?>(null) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(false) }

    BackHandler { onBack() }

    fun send(action: String, build: NavigationClient.JsonObjectBuilderScope.() -> Unit = {}) {
        scope.launch { client.command(action, build) }
    }

    RoboGuardAndroidTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            HeaderAppName(UiText.get("nav.header.title"))

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onBack) { Text(UiText.get("nav.button.back")) }
                Box(modifier = Modifier.weight(1f))
                ConnectionDot(connection)
            }

            ConnectionBanner(connection) { client.retry() }

            val s = state
            if (s != null && !s.running) {
                Warning(UiText.get("nav.warning.not_running", "reason" to (s.error ?: UiText.get("nav.label.unknown_reason"))))
            }
            // Always visible, also without debug: these are the reasons the robot refuses to drive.
            s?.areaStoreError?.let { Warning(UiText.get("nav.error.areas_unreadable", "error" to it)) }
            s?.areaSaveWarning?.let { Warning(it) }
            s?.locationStoreError?.let { Warning(UiText.get("nav.error.locations_unreadable", "error" to it)) }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    MapView(state, mapInfo, mapImage) { x, y -> send("point") { at(x, y) } }
                }

                item {
                    // Driving: one big target button and a red STOP, like on the robot.
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { send("drive") },
                            enabled = state?.selected != null,
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text(
                                state?.selected?.let { UiText.get("nav.button.drive_to", "location" to it.name) }
                                    ?: UiText.get("nav.button.drive_to_none"),
                                maxLines = 2
                            )
                        }
                        Button(
                            onClick = { send("stop") },
                            colors = ButtonDefaults.buttonColors(containerColor = RoboGuardColors.Danger),
                            modifier = Modifier.height(48.dp)
                        ) { Text(UiText.get("nav.button.stop"), fontWeight = FontWeight.Bold, color = Color.White) }
                    }
                }

                item {
                    Section(UiText.get("nav.label.speed")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // The preset name is what the robot understands; only the label is a text.
                            listOf("SLOW" to "nav.speed.slow", "MEDIUM" to "nav.speed.medium", "DEFAULT" to "nav.speed.default")
                                .forEach { (preset, textKey) ->
                                    val label = UiText.get(textKey)
                                    if (state?.speed == preset) {
                                        Button(onClick = { send("speed") { preset(preset) } }, modifier = Modifier.weight(1f)) {
                                            Text(label, fontSize = 12.sp, maxLines = 1)
                                        }
                                    } else {
                                        OutlinedButton(onClick = { send("speed") { preset(preset) } }, modifier = Modifier.weight(1f)) {
                                            Text(label, fontSize = 12.sp, maxLines = 1)
                                        }
                                    }
                                }
                        }
                    }
                }

                item {
                    Section(UiText.get("nav.label.places")) {
                        val points = (state?.places.orEmpty() + state?.points.orEmpty())
                        if (points.isEmpty()) Text(UiText.get("nav.label.no_places"), fontSize = 13.sp)
                        points.forEach { point ->
                            val selected = state?.selected?.name == point.name
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val label = UiText.get(
                                    "nav.label.place",
                                    "name" to point.name,
                                    "x" to "%.2f".format(point.x),
                                    "y" to "%.2f".format(point.y)
                                )
                                if (selected) {
                                    Button(onClick = { send("select") { name(point.name) } }, modifier = Modifier.weight(1f)) {
                                        Text(label, fontSize = 13.sp)
                                    }
                                } else {
                                    OutlinedButton(onClick = { send("select") { name(point.name) } }, modifier = Modifier.weight(1f)) {
                                        Text(label, fontSize = 13.sp)
                                    }
                                }
                                // Only places saved in RoboGuard can be deleted; the robot's own map places cannot.
                                if (selected && point.persistent) {
                                    OutlinedButton(onClick = { send("deleteLocation") }) {
                                        Text(UiText.get("nav.button.delete"), fontSize = 12.sp, color = RoboGuardColors.Danger)
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { askName = "saveLocation" },
                                enabled = state?.localized == true
                            ) { Text(UiText.get("nav.button.save_position"), fontSize = 12.sp) }
                            OutlinedButton(onClick = { send("clearPoints") }) {
                                Text(UiText.get("nav.button.clear_points"), fontSize = 12.sp)
                            }
                            OutlinedButton(onClick = { send("reload"); client.forgetMap() }) {
                                Text(UiText.get("nav.button.reload_map"), fontSize = 12.sp)
                            }
                        }
                    }
                }

                item {
                    PrivateAreas(
                        state,
                        onAction = { action, name -> send(action) { name(name) } },
                        onAsk = { askName = it }
                    )
                }

                if (showDebug) {
                    item { StatusLines(state) }
                    item {
                        OutlinedButton(onClick = { showLog = !showLog }) {
                            Text(UiText.get(if (showLog) "nav.button.hide_log" else "nav.button.show_log"), fontSize = 13.sp)
                        }
                    }
                    if (showLog) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                                    .background(RoboGuardColors.LogBackground, RoundedCornerShape(6.dp))
                                    .padding(6.dp).verticalScroll(rememberScrollState())
                            ) {
                                state?.log.orEmpty().forEach { Text(it, fontSize = 11.sp) }
                            }
                        }
                    }
                }

                item {
                    // Same idea as on the robot: everything technical sits behind this one switch.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = showDebug, onCheckedChange = { showDebug = it })
                        Text(UiText.get("nav.switch.debug"), fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
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

        askName?.let { action ->
            NameDialog(
                title = UiText.get(
                    when (action) {
                        "saveLocation" -> "nav.dialog.name.position"
                        "areaCircle" -> "nav.dialog.name.area"
                        else -> "nav.dialog.name.drawn_area"
                    }
                ),
                onCancel = { askName = null },
                onConfirm = { typed -> askName = null; send(action) { name(typed) } }
            )
        }

        message?.let { text ->
            AlertDialog(
                onDismissRequest = { client.clearMessage() },
                confirmButton = { Button(onClick = { client.clearMessage() }) { Text(UiText.get("button.ok")) } },
                title = { Text(UiText.get("nav.dialog.message.title")) },
                text = { Text(text) },
                containerColor = Color.White
            )
        }
    }
}

/** Coloured dot plus the state word, so the connection is visible without reading the banner. */
@Composable
private fun ConnectionDot(connection: NavConnection) {
    val (color, key) = when (connection) {
        is NavConnection.Online -> RoboGuardColors.Good to "nav.state.connected"
        is NavConnection.Connecting -> RoboGuardColors.Idle to "nav.state.connecting"
        is NavConnection.Offline -> RoboGuardColors.Danger to "nav.state.offline"
        is NavConnection.Refused -> RoboGuardColors.Warn to "nav.state.refused"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(10.dp).background(color, RoundedCornerShape(5.dp)))
        Text(UiText.get(key), fontSize = 12.sp, color = color)
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
                UiText.get("nav.banner.offline", "reason" to connection.reason, "seconds" to seconds),
                onRetry
            )
        }
        is NavConnection.Refused -> {
            // 401 is the one worth explaining: the pairing is gone, so nothing here works until it is redone.
            val extra = if (connection.code == 401) UiText.get("nav.banner.refused.unpaired") else ""
            Warning(
                UiText.get("nav.banner.refused", "code" to connection.code, "extra" to extra, "reason" to connection.reason),
                onRetry
            )
        }
        else -> {}
    }
}

@Composable
private fun Warning(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(8.dp)
            .background(RoboGuardColors.ErrorBackground, RoundedCornerShape(6.dp)).padding(8.dp)
    ) {
        Text(text, fontSize = 13.sp, color = RoboGuardColors.ErrorText)
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) {
                Text(UiText.get("nav.button.retry"))
            }
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

/** Debug lines: map name, localization, SDK control, what the robot is doing, and who is steering it. */
@Composable
private fun StatusLines(state: NavState?) {
    fun yesNo(value: Boolean?) = UiText.get(
        when (value) { true -> "nav.label.yes"; false -> "nav.label.no"; null -> "nav.label.unknown" }
    )
    Column {
        Text(UiText.get("nav.label.map", "map" to (state?.map ?: UiText.get("nav.label.unknown"))), fontSize = 13.sp)
        Text(
            UiText.get("nav.label.localized", "state" to yesNo(state?.localized), "sdk" to yesNo(state?.sdkControl)),
            fontSize = 13.sp,
            // Without localization the robot refuses to drive, so it is worth showing in red.
            color = if (state?.localized == false) RoboGuardColors.Danger else Color.Unspecified
        )
        Text(UiText.get("nav.label.navigation", "state" to state?.navState.orEmpty()), fontSize = 13.sp)
        state?.pose?.let {
            Text(
                UiText.get("nav.label.position", "x" to "%.2f".format(it.x), "y" to "%.2f".format(it.y)),
                fontSize = 13.sp
            )
        }
        state?.remoteControl?.let {
            Text(UiText.get("nav.label.remote", "client" to it), fontSize = 12.sp, color = RoboGuardColors.Header)
        }
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
            modifier = Modifier.fillMaxWidth().height(180.dp)
                .background(RoboGuardColors.Surface, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) { Text(UiText.get("nav.map.none"), fontSize = 13.sp) }
        return
    }
    val ratio = if (info.heightPx > 0) info.widthPx.toFloat() / info.heightPx else 1f
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio)
            .background(RoboGuardColors.Surface, RoundedCornerShape(6.dp))
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
            contentDescription = UiText.get("nav.map.description"),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            fun px(x: Double) = ((x - info.minX) / (info.maxX - info.minX) * size.width).toFloat()
            fun py(y: Double) = ((info.maxY - y) / (info.maxY - info.minY) * size.height).toFloat()

            // Private areas in red; one the robot may currently cross in orange, so a granted permission is visible.
            state?.areas.orEmpty().forEach { area ->
                if (area.corners.size < 2) return@forEach
                val path = Path().apply {
                    moveTo(px(area.corners[0].x), py(area.corners[0].y))
                    area.corners.drop(1).forEach { lineTo(px(it.x), py(it.y)) }
                    close()
                }
                val color = if (area.allowedUntil != null) RoboGuardColors.Allowed else RoboGuardColors.Danger
                drawPath(path, color.copy(alpha = 0.25f))
                drawPath(path, color, style = Stroke(width = 2f))
            }
            state?.drawing?.let { corners ->
                corners.forEach { drawCircle(RoboGuardColors.Header, radius = 5f, center = Offset(px(it.x), py(it.y))) }
                if (corners.size >= 2) {
                    val path = Path().apply {
                        moveTo(px(corners[0].x), py(corners[0].y))
                        corners.drop(1).forEach { lineTo(px(it.x), py(it.y)) }
                    }
                    drawPath(path, RoboGuardColors.Header, style = Stroke(width = 2f))
                }
            }

            state?.places.orEmpty().forEach {
                drawCircle(RoboGuardColors.Good, radius = 6f, center = Offset(px(it.x), py(it.y)))
            }
            state?.points.orEmpty().forEach {
                drawCircle(RoboGuardColors.OwnPoint, radius = 6f, center = Offset(px(it.x), py(it.y)))
            }
            state?.selected?.let {
                drawCircle(RoboGuardColors.Header, radius = 10f, style = Stroke(width = 3f), center = Offset(px(it.x), py(it.y)))
            }

            // The robot: a dot with a line in its heading direction.
            state?.pose?.let { pose ->
                val center = Offset(px(pose.x), py(pose.y))
                drawCircle(Color.Black, radius = 7f, center = center)
                drawLine(
                    Color.Black,
                    center,
                    Offset(px(pose.x + 0.4 * cos(pose.theta)), py(pose.y + 0.4 * sin(pose.theta))),
                    strokeWidth = 3f
                )
            }
        }
    }
}

/** Private areas: create, delete, and end a temporary crossing permission early. */
@Composable
private fun PrivateAreas(
    state: NavState?,
    onAction: (String, String) -> Unit,
    onAsk: (String) -> Unit
) {
    val drawing = state?.drawing
    Section(UiText.get("nav.label.areas")) {
        if (state?.areasLoaded == false) {
            Text(UiText.get("nav.label.areas_not_loaded"), fontSize = 12.sp, color = RoboGuardColors.Danger)
        }
        state?.areas.orEmpty().forEach { area ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(area.name, fontSize = 13.sp)
                    area.allowedUntil?.let { until ->
                        // Counted against the ROBOT's clock, so the countdown does not depend on the phone's time.
                        val left = ((until - (state?.now ?: 0L)) / 1000).coerceAtLeast(0)
                        Text(
                            UiText.get("nav.label.area_allowed", "time" to "%d:%02d".format(left / 60, left % 60)),
                            fontSize = 11.sp,
                            color = RoboGuardColors.Allowed
                        )
                    }
                }
                if (area.allowedUntil != null) {
                    OutlinedButton(onClick = { onAction("revokeArea", area.name) }) {
                        Text(UiText.get("nav.button.revoke"), fontSize = 12.sp)
                    }
                }
                OutlinedButton(onClick = { onAction("deleteArea", area.name) }) {
                    Text(UiText.get("nav.button.delete"), fontSize = 12.sp, color = RoboGuardColors.Danger)
                }
            }
        }
        if (state?.areas.orEmpty().isEmpty()) Text(UiText.get("nav.label.no_areas"), fontSize = 13.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (drawing == null) {
                OutlinedButton(onClick = { onAsk("areaCircle") }, enabled = state?.selected != null) {
                    Text(UiText.get("nav.button.area_circle"), fontSize = 12.sp)
                }
                OutlinedButton(onClick = { onAction("drawStart", "") }) {
                    Text(UiText.get("nav.button.draw_area"), fontSize = 12.sp)
                }
            } else {
                OutlinedButton(onClick = { onAction("drawUndo", "") }) { Text(UiText.get("nav.button.undo"), fontSize = 12.sp) }
                OutlinedButton(onClick = { onAction("drawCancel", "") }) { Text(UiText.get("nav.button.cancel_drawing"), fontSize = 12.sp) }
                OutlinedButton(onClick = { onAsk("drawFinish") }, enabled = drawing.size >= 3) {
                    Text(UiText.get("nav.button.finish_area", "corners" to drawing.size), fontSize = 12.sp)
                }
            }
        }
        if (drawing != null) Text(UiText.get("nav.hint.drawing"), fontSize = 12.sp)
    }
}

/** The robot's "may I cross this area?" question, answered from the phone. */
@Composable
private fun CrossingDialog(area: String, onAllow: (Int) -> Unit, onDeny: () -> Unit) {
    var minutes by remember { mutableStateOf(5) }
    AlertDialog(
        onDismissRequest = { /* must be answered: ignoring it would leave the robot standing */ },
        title = { Text(UiText.get("nav.dialog.crossing.title", "area" to area)) },
        text = {
            Column {
                Text(UiText.get("nav.dialog.crossing.text", "minutes" to minutes), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf(1, 2, 5, 10, 60).forEach { value ->
                        if (value == minutes) {
                            Button(onClick = { minutes = value }, contentPadding = PaddingValues(8.dp)) {
                                Text("$value", fontSize = 12.sp)
                            }
                        } else {
                            OutlinedButton(onClick = { minutes = value }, contentPadding = PaddingValues(8.dp)) {
                                Text("$value", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAllow(minutes) },
                colors = ButtonDefaults.buttonColors(containerColor = RoboGuardColors.Good)
            ) { Text(UiText.get("nav.button.yes"), color = Color.White) }
        },
        dismissButton = {
            Button(
                onClick = onDeny,
                colors = ButtonDefaults.buttonColors(containerColor = RoboGuardColors.Danger)
            ) { Text(UiText.get("nav.button.no"), color = Color.White) }
        },
        containerColor = Color.White
    )
}

/** Asks for a name (saved position, private area) and lets the robot check it — names must be unique there. */
@Composable
private fun NameDialog(title: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            Button(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(UiText.get("button.ok")) }
        },
        dismissButton = { OutlinedButton(onClick = onCancel) { Text(UiText.get("button.cancel")) } },
        containerColor = Color.White
    )
}
