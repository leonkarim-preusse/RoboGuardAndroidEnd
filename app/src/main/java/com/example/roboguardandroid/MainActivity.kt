package com.example.roboguardandroid

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.roboguardandroid.ui.theme.RoboGuardAndroidTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Sealed class representing the possible results of a synchronization attempt with the robot.
 */
sealed class SyncResult {
    object Success : SyncResult()
    object Failed : SyncResult()
    object ReloadNeeded : SyncResult()
}

/**
 * Data class representing the full application settings to be sent to the robot.
 */
@Serializable
data class AppSettings(
    val sensors: Map<String, Boolean>,
    val rooms: List<RoomSettings>,
    val situationalSettings: Map<String, Boolean>,
    val sleepTime: String
)

/**
 * Data class representing privacy settings for a specific room.
 */
@Serializable
data class RoomSettings(
    val name: String,
    val sensors: Map<String, Boolean>
)

/**
 * Main Activity of the RoboGuard Android application.
 * Manages the top-level navigation between pairing (QR scan) and the settings UI.
 */
class MainActivity : ComponentActivity() {

    lateinit var apiRob: RobotAPI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Screen texts come from assets/texts/texts.json; load them before the first screen is built.
        UiText.init(this)
        apiRob = RobotAPI(this)

        setContent {
            var isCoupled by remember { mutableStateOf(apiRob.isCoupled) }

            Scaffold(
                modifier = Modifier.fillMaxSize()
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding)) {
                    // The navigation screen is a second full screen, shown instead of the settings.
                    var showNavigation by remember { mutableStateOf(false) }
                    if (isCoupled) {
                        if (showNavigation) {
                            NavigationScreen(apiRob, onBack = { showNavigation = false })
                        } else {
                            StartUI(
                                apiRob,
                                onUncouple = { isCoupled = false },
                                onOpenNavigation = { showNavigation = true }
                            )
                        }
                    } else {
                        QRscanUI(apiRob) { isCoupled = true }
                    }
                }
            }
        }
    }
}

/**
 * Composable that handles the QR code scanning flow for pairing with a robot.
 * @param apiRob The [RobotAPI] instance.
 * @param onPairingComplete Callback triggered when pairing is successful.
 */
@Composable
fun QRscanUI(apiRob: RobotAPI, onPairingComplete: () -> Unit) {

    var showScanner by remember { mutableStateOf(false) }
    var scannedQr by remember { mutableStateOf<String?>(null) }
    var showQRValidation by remember { mutableStateOf(false) }

    if (!showQRValidation) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            HeaderAppName()
            Text(
                UiText.get("pair.instructions"),
                fontSize = 40.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,

                modifier = Modifier
                    .padding(top = 26.dp, bottom = 40.dp)
                    .fillMaxWidth()
                    .padding(20.dp)
                    .align(Alignment.Center),
                textAlign = TextAlign.Center
            )
            Button(
                onClick = {
                    Log.d("Camera", "Starting QR Code Scanner")
                    showScanner = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.BottomCenter)
            ) {
                Text(
                    text = UiText.get("pair.button.scan"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            if (showScanner) {

                BackHandler {
                    showScanner = false
                }

                CameraPermissionWrapper { qrValue ->
                    Log.d("QR", qrValue)

                    scannedQr = qrValue
                    showScanner = false
                    showQRValidation = true
                }
            }
        }
    }

    if (showQRValidation){

        var isQRvalid by remember {mutableStateOf(verify_QR(scannedQr))}
        var showErrorDialog by remember { mutableStateOf(false) }
        isQRvalidScreen(isQRvalid)
        if (isQRvalid) {
            LaunchedEffect(scannedQr) {
                val QRdata = parseQR(scannedQr)
                val publicKey: String = QRdata.publicKey
                val otp: String = QRdata.otp
                val ip: String = QRdata.ip

                apiRob.completePairing(ip, publicKey)
                Log.i("RobotAPI", "Set robot IP to $apiRob.robotIP, attempting ping ")
                try{
                    apiRob.pingRobot()
                }catch(e: Exception){
                    Log.e("Server", "Ping failed with error: $e")
                }
                val success = apiRob.secrethandshake(otp)
                if (success) {
                    // After successful handshake, fetch capabilities
                    apiRob.fetchRobotCapabilities()
                    onPairingComplete()
                } else {
                    showErrorDialog = true
                }
            }
            if (showErrorDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showErrorDialog = false; showQRValidation= false },
                    confirmButton = {
                        Button(onClick = { showErrorDialog = false; showQRValidation= false }) {
                            Text(UiText.get("button.ok"))
                        }
                    },
                    title = { Text(UiText.get("pair.error.title")) },
                    text = { Text(UiText.get("pair.error.text")) },
                    containerColor = Color.White,
                    titleContentColor = Color.Red
                )
            }
        }

        else{
            LaunchedEffect(Unit) {
                delay(5000)
                showQRValidation = false
            }
        }
    }
}

/**
 * The main settings interface shown once the app is successfully paired.
 * Allows users to toggle sensors, rooms, and situational privacy modes.
 * @param apiRob The [RobotAPI] instance.
 * @param onUncouple Callback triggered when the user chooses to uncouple.
 */
@Composable
fun StartUI(apiRob: RobotAPI, onUncouple: () -> Unit, onOpenNavigation: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Use a refresh key to force re-initialization of states when capabilities change
    var refreshKey by remember { mutableStateOf(0) }

    // Load capabilities from API (saved in prefs, with fallback to defaults)
    val capabilities = remember(refreshKey) { apiRob.getSavedCapabilities() }
    
    val sensorList = capabilities.sensors
    val roomList = capabilities.rooms
    val situationalList = capabilities.situational

    val sensorStates = remember(refreshKey) {
        val initialMap = sensorList.associateWith { true }.toMutableMap()
        mutableStateMapOf<String, Boolean>().apply { putAll(initialMap) }
    }

    val situationalStates = remember(refreshKey) {
        val initialMap = situationalList.associateWith { false }.toMutableMap()
        mutableStateMapOf<String, Boolean>().apply { putAll(initialMap) }
    }

    val rooms = remember(refreshKey) {
        roomList.map { roomName -> room(roomName, sensorList) }
    }

    var showSleepPopup by remember { mutableStateOf(false) }
    var selectedTime by remember { mutableStateOf("Don't") }

    // States for Info Dialogs
    var infoDialogTitle by remember { mutableStateOf<String?>(null) }
    var infoDialogText by remember { mutableStateOf<String?>(null) }

    var syncStatus by remember { mutableStateOf<SyncResult?>(null) }

    RoboGuardAndroidTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            HeaderAppName()

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                item {
                    SensorCategory(rooms, sensorStates, sensorList)
                }

                item {
                    create_setting_category(UiText.get("settings.category.situational")) {
                        situationalList.forEach { settingName ->
                            create_row_settings(
                                setting = settingName,
                                isChecked = situationalStates[settingName] ?: false,
                                onTextClick = {
                                    // Map info text based on name
                                    // settingName comes from the robot, so it is matched, never displayed as a key.
                                    when (settingName) {
                                        "Discretion Mode" -> {
                                            infoDialogTitle = UiText.get("settings.info.discretion.title")
                                            infoDialogText = UiText.get("settings.info.discretion.text")
                                        }
                                        "pixelate objects" -> {
                                            infoDialogTitle = UiText.get("settings.info.pixelate.title")
                                            infoDialogText = UiText.get("settings.info.pixelate.text")
                                        }
                                        else -> {
                                            infoDialogTitle = settingName
                                            infoDialogText = UiText.get("settings.info.unknown.text", "setting" to settingName)
                                        }
                                    }
                                }
                            ) {
                                situationalStates[settingName] = it
                                toggle_setting()
                            }
                        }
                    }
                }

                item {
                    create_setting_category(UiText.get("settings.category.sleep")) {
                        create_row_settings_button(UiText.get("settings.sleep.label"), sleepTimeText(selectedTime)) {
                            showSleepPopup = true
                        }

                        sleepPopup(
                            show = showSleepPopup,
                            onDismiss = { showSleepPopup = false },
                            onSelect = { selectedTime = it }
                        )
                    }
                }
            }

            // Navigation and Map: the robot's own screen, shown on the phone (local network only).
            Button(
                onClick = onOpenNavigation,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text(
                    text = UiText.get("settings.button.navigation"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Uncouple Button
            Row {
                Button(
                    onClick = {
                        apiRob.uncoupleRobot()
                        onUncouple()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text(
                        text = UiText.get("settings.button.uncouple"),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Button(
                onClick = {
                    Log.d("StartUI", "Sensor States: $sensorStates")
                    scope.launch {
                        syncStatus = syncRobot(
                            context = context,
                            sensorStates = sensorStates,
                            rooms = rooms,
                            situationalStates = situationalStates,
                            sleepTime = selectedTime,
                            apiRob = apiRob,
                            currentCapabilities = capabilities
                        )
                        if (syncStatus is SyncResult.ReloadNeeded) {
                            refreshKey++
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = UiText.get("settings.button.sync"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Dynamic Info Dialog
            if (infoDialogTitle != null && infoDialogText != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { infoDialogTitle = null; infoDialogText = null },
                    confirmButton = {
                        Button(onClick = { infoDialogTitle = null; infoDialogText = null }) {
                            Text(UiText.get("button.ok"))
                        }
                    },
                    title = { Text(infoDialogTitle!!) },
                    text = { Text(infoDialogText!!) },
                    containerColor = Color.White
                )
            }

            // Sync Status Dialogs
            when (syncStatus) {
                is SyncResult.Success -> {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { syncStatus = null },
                        confirmButton = {
                            Button(onClick = { syncStatus = null }) {
                                Text(UiText.get("button.ok"))
                            }
                        },
                        title = { Text(UiText.get("sync.success.title")) },
                        text = { Text(UiText.get("sync.success.text")) },
                        containerColor = Color.White
                    )
                }
                is SyncResult.Failed -> {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { syncStatus = null },
                        confirmButton = {
                            Button(onClick = { syncStatus = null }) {
                                Text(UiText.get("button.ok"))
                            }
                        },
                        title = { Text(UiText.get("sync.failed.title")) },
                        text = { Text(UiText.get("sync.failed.text")) },
                        containerColor = Color.White
                    )
                }
                is SyncResult.ReloadNeeded -> {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { syncStatus = null },
                        confirmButton = {
                            Button(onClick = { syncStatus = null }) {
                                Text(UiText.get("sync.reload.button"))
                            }
                        },
                        title = { Text(UiText.get("sync.reload.title")) },
                        text = { Text(UiText.get("sync.reload.text")) },
                        containerColor = Color.White
                    )
                }
                else -> {}
            }
        }
    }
}

/**
 * Displays the application header with the title.
 */
@Composable
fun HeaderAppName(title: String = UiText.get("app.header.title")) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A73E8))
            .padding(top = statusBarPadding)
    ) {
        Text(
            text = title,
            fontSize = 42.sp,
            lineHeight = 50.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(20.dp)
        )
    }
}

/**
 * Creates a setting row with a title and a switch.
 * @param setting The name of the setting.
 * @param isChecked Current state of the switch.
 * @param onTextClick Optional callback for clicking the setting name.
 * @param onCheckedChange Callback for switch state changes.
 */
@Composable
fun create_row_settings(
    setting: String,
    isChecked: Boolean,
    onTextClick: (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(
            text = setting,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onTextClick != null) {
                        Modifier.clickable { onTextClick() }
                    } else {
                        Modifier
                    }
                )
        )

        Switch(
            checked = isChecked,
            onCheckedChange = { newValue ->
                onCheckedChange(newValue)
            }
        )
    }
}

/**
 * Creates a setting row with a button instead of a switch.
 * @param setting The name of the setting.
 * @param text_button The text shown on the button.
 * @param onClick Callback for button clicks.
 */
@Composable fun create_row_settings_button(setting:String, text_button: String, onClick: () -> Unit){
    Row( horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically ) {
        Text(modifier = Modifier
            .padding(start = 20.dp),
            text = setting,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.weight(1f))
        Button(modifier = Modifier.padding(end = 30.dp) ,
            onClick = onClick) { Text(text_button) } }
}

/**
 * An expandable category container for settings.
 * @param name The name of the category.
 * @param content The composable content to show when expanded.
 */
@Composable
fun create_setting_category(name: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp, horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = name,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = UiText.get(if (expanded) "settings.category.collapse" else "settings.category.expand"),
                modifier = Modifier.rotate(if (expanded) 180f else 0f)
            )
        }

        Divider()
        if (expanded) {
            content()
        }
    }
}

/**
 * Dialog for selecting a sleep duration or entering a custom time.
 * @param show Controls visibility of the dialog.
 * @param onDismiss Callback when the dialog is dismissed.
 * @param onSelect Callback when a time is selected.
 */
@Composable
fun sleepPopup(
    show: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    if (!show) return

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x88000000))
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, shape = RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(UiText.get("sleep.dialog.title"), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    // The value stays fixed (parseSleepTimeToSeconds reads it); only the label comes from the texts.
                    SLEEP_OPTIONS.forEach { option ->
                        Button(
                            onClick = {
                                onSelect(option)
                                onDismiss()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(sleepTimeText(option))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

                    var hours by remember { mutableStateOf("") }
                    var minutes by remember { mutableStateOf("") }
                    var seconds by remember { mutableStateOf("") }

                    Text(UiText.get("sleep.custom.title"), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                        OutlinedTextField(
                            value = hours,
                            onValueChange = { hours = it.filter { c -> c.isDigit() } },
                            label = { Text(UiText.get("sleep.custom.hours")) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = minutes,
                            onValueChange = { minutes = it.filter { c -> c.isDigit() } },
                            label = { Text(UiText.get("sleep.custom.minutes")) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = seconds,
                            onValueChange = { seconds = it.filter { c -> c.isDigit() } },
                            label = { Text(UiText.get("sleep.custom.seconds")) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val customTime = "${hours.ifBlank { "0" }}h ${minutes.ifBlank { "0" }}m ${seconds.ifBlank { "0" }}s"
                            onSelect(customTime)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(UiText.get("sleep.button.set_custom"))
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.LightGray)
                    ) {
                        Text(UiText.get("button.cancel"))
                    }
                }
            }
        }
    }
}

/**
 * Composable for the "Sensors" category. Displays global sensor toggles
 * and per-room sensor overrides.
 * @param rooms List of [room] objects.
 * @param sensorStates Map of global sensor enablement states.
 * @param sensorList List of available sensor names.
 */
@Composable
fun SensorCategory(
    rooms: List<room>,
    sensorStates: MutableMap<String, Boolean>,
    sensorList: List<String>
    ) {

    create_setting_category(UiText.get("settings.category.sensors")) {
        sensorList.forEach { sensorName ->
            var sensorExpanded by remember { mutableStateOf(false) }
            val sensorEnabled = sensorStates.getOrDefault(sensorName, true)
            Column(modifier = Modifier.fillMaxWidth()) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { sensorExpanded = !sensorExpanded }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        sensorName,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = sensorEnabled,
                        onCheckedChange = { newValue ->
                            sensorStates[sensorName] = newValue
                            if (!newValue) {
                                rooms.forEach { room ->
                                    room.update_sensors(sensorName, false)
                                }
                            }
                        }
                    )

                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .rotate(if (sensorExpanded) 180f else 0f)
                    )
                }
                Divider()
                if (sensorExpanded) {
                    Column(modifier = Modifier.padding(start = 40.dp)) {
                        rooms.forEach { room ->
                            var checked by remember {
                                mutableStateOf(
                                    room.sensors[sensorName] ?: true
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { newValue ->
                                        checked = newValue
                                        room.update_sensors(sensorName, newValue)
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(room.name, fontSize = 14.sp)
                            }

                            if (!sensorEnabled && checked) {
                                checked = false
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Synchronizes the current UI settings with the robot.
 * Checks for capability mismatches before sending data.
 * @return [SyncResult] indicating success, failure, or if a UI reload is needed.
 */
suspend fun syncRobot(
    sensorStates: Map<String, Boolean>,
    rooms: List<room>,
    situationalStates: Map<String, Boolean>,
    sleepTime: String,
    context: Context,
    apiRob: RobotAPI,
    currentCapabilities: RobotCapabilities
): SyncResult {

    Log.d("RobotAPI", "Verifying robot configuration before sync...")
    
    // 1. Fetch latest capabilities from robot
    val latestCapabilities = apiRob.fetchRobotCapabilities()
    
    // 2. Compare with what the UI is currently using
    if (latestCapabilities != null && latestCapabilities != currentCapabilities) {
        Log.w("RobotAPI", "Configuration mismatch! UI needs reload.")
        return SyncResult.ReloadNeeded
    }

    Log.d("RobotAPI", "Attempting to sync with your robot")
    val json = createSettingsJson(
        sensorStates = sensorStates,
        rooms = rooms,
        situationalStates = situationalStates,
        sleepTime = parseSleepTimeToSeconds(sleepTime).toString()
    )
    Log.d("StartUI", "Settings JSON: $json")

    return try {
        val response = apiRob.dataToRobot(json)
        if (response.status.value in 200..299) {
            Log.d("RobotAPI", "Sync successful!")
            SyncResult.Success
        } else {
            SyncResult.Failed
        }
    } catch (e: Exception) {
        Log.e("RobotAPI", "Sync failed: ${e.message}")
        SyncResult.Failed
    }
}

/** Placeholder for setting toggle logic. */
fun toggle_setting() {

}

/**
 * Converts UI states into a JSON string of [AppSettings].
 */
fun createSettingsJson(
    sensorStates: Map<String, Boolean>,
    rooms: List<room>,
    situationalStates: Map<String, Boolean>,
    sleepTime: String
): String {

    val roomSettingsList = rooms.map { r ->
        RoomSettings(
            name = r.name,
            sensors = r.sensors.toMap()
        )
    }

    val settings = AppSettings(
        sensors = sensorStates.toMap(),
        rooms = roomSettingsList,
        situationalSettings = situationalStates.toMap(),
        sleepTime = sleepTime
    )
    return Json { prettyPrint = true }.encodeToString<AppSettings>(settings)
}

/**
 * The fixed sleep values. They are NOT texts: [parseSleepTimeToSeconds] turns exactly these strings into seconds for the
 * robot, so they must not change when the wording does. [sleepTimeText] is what the person reads.
 */
val SLEEP_OPTIONS = listOf("Dont", "5 minutes", "10 minutes", "1 hour")

/** The label for a sleep value; a freely typed duration ("0h 5m 0s") is shown as it is. */
fun sleepTimeText(value: String): String = when (value) {
    "Dont" -> UiText.get("sleep.option.none")
    "5 minutes" -> UiText.get("sleep.option.5min")
    "10 minutes" -> UiText.get("sleep.option.10min")
    "1 hour" -> UiText.get("sleep.option.1hour")
    else -> value
}

/**
 * Parses human-readable sleep time strings (e.g., "5 minutes") into seconds.
 */
fun parseSleepTimeToSeconds(sleepTime: String): Int {
    return when (sleepTime.lowercase()) {
        "dont" -> 0
        "5 minutes" -> 5 * 60
        "10 minutes" -> 10 * 60
        "1 hour" -> 60 * 60
        else -> {
            val hoursRegex = """(\d+)h""".toRegex()
            val minutesRegex = """(\d+)m""".toRegex()
            val secondsRegex = """(\d+)s""".toRegex()
            val hours = hoursRegex.find(sleepTime)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minutes = minutesRegex.find(sleepTime)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val seconds = secondsRegex.find(sleepTime)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            hours * 3600 + minutes * 60 + seconds
        }
    }
}

/**
 * Saves the settings JSON string to a local file.
 */
fun saveSettingsLocally(context: Context, jsonString: String, filename: String = "settings.json") {
    try {
        context.openFileOutput(filename, Context.MODE_PRIVATE).use { output ->
            output.write(jsonString.toByteArray())
        }
        Log.d("StartUI", "Settings saved locally to $filename")
    } catch (e: Exception) {
        Log.e("StartUI", "Failed to save settings", e)
    }
}
