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

sealed class SyncResult {
    object Success : SyncResult()
    object Failed : SyncResult()
    object ReloadNeeded : SyncResult()
}

@Serializable
data class AppSettings(
    val sensors: Map<String, Boolean>,
    val rooms: List<RoomSettings>,
    val situationalSettings: Map<String, Boolean>,
    val sleepTime: String
)


@Serializable
data class RoomSettings(
    val name: String,
    val sensors: Map<String, Boolean>
)



class MainActivity : ComponentActivity() {

    lateinit var apiRob: RobotAPI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        apiRob = RobotAPI(this)

        setContent {
            var isCoupled by remember { mutableStateOf(apiRob.isCoupled) }

            Scaffold(
                modifier = Modifier.fillMaxSize()
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding)) {
                    if (isCoupled) {
                        StartUI(apiRob, onUncouple = { isCoupled = false })
                    } else {
                        QRscanUI(apiRob) { isCoupled = true }
                    }
                }
            }
        }
    }
}


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
                "You need to scan the QR Code on your robot to pair your device.",
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
                    text = "Scan for QR Code!",
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
                            Text("OK")
                        }
                    },
                    title = { Text("Oops something went wrong when pairing with your robot!") },
                    text = { Text("Please try scanning the QR Code again.") },
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
@Composable
fun StartUI(apiRob: RobotAPI, onUncouple: () -> Unit) {
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
                    create_setting_category("Situational") {
                        situationalList.forEach { settingName ->
                            create_row_settings(
                                setting = settingName,
                                isChecked = situationalStates[settingName] ?: false,
                                onTextClick = {
                                    // Map info text based on name
                                    when (settingName) {
                                        "Discretion Mode" -> {
                                            infoDialogTitle = "Discretion Mode"
                                            infoDialogText = "The robot turns away automatically when it detects sensitive situations or nudity to protect your privacy."
                                        }
                                        "pixelate objects" -> {
                                            infoDialogTitle = "Pixelate Objects"
                                            infoDialogText = "When enabled, the robot's camera stream will automatically blur objects or persons marked as private."
                                        }
                                        else -> {
                                            infoDialogTitle = settingName
                                            infoDialogText = "Specific information for $settingName is not available."
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
                    create_setting_category("Sleep") {
                        create_row_settings_button("Sleep for", selectedTime) {
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
                        text = "Pair again / Uncouple",
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
                    text = "Sync with your Robot!",
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
                            Text("OK")
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
                                Text("OK")
                            }
                        },
                        title = { Text("Sync Successful!") },
                        text = { Text("Your privacy settings were successfully saved on your robot") },
                        containerColor = Color.White
                    )
                }
                is SyncResult.Failed -> {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { syncStatus = null },
                        confirmButton = {
                            Button(onClick = { syncStatus = null }) {
                                Text("OK")
                            }
                        },
                        title = { Text("Sync Failed") },
                        text = { Text("Could not connect to the robot. Please check your connection.") },
                        containerColor = Color.White
                    )
                }
                is SyncResult.ReloadNeeded -> {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { syncStatus = null },
                        confirmButton = {
                            Button(onClick = { syncStatus = null }) {
                                Text("Update Now")
                            }
                        },
                        title = { Text("Robot Config Changed") },
                        text = { Text("The robot's available sensors or rooms have changed. The UI will now be updated.") },
                        containerColor = Color.White
                    )
                }
                else -> {}
            }
        }
    }
}

@Composable
fun HeaderAppName() {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A73E8))
            .padding(top = statusBarPadding)
    ) {
        Text(
            text = "RoboGuard\nPrivacy Settings",
            fontSize = 42.sp,
            lineHeight = 50.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(20.dp)
        )
    }
}

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
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.rotate(if (expanded) 180f else 0f)
            )
        }

        Divider()
        if (expanded) {
            content()
        }
    }
}
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
                    Text("Sleep for:", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    val options = listOf("Dont", "5 minutes", "10 minutes", "1 hour")
                    options.forEach { option ->
                        Button(
                            onClick = {
                                onSelect(option)
                                onDismiss()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(option)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

                    var hours by remember { mutableStateOf("") }
                    var minutes by remember { mutableStateOf("") }
                    var seconds by remember { mutableStateOf("") }

                    Text("Custom Time", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                        OutlinedTextField(
                            value = hours,
                            onValueChange = { hours = it.filter { c -> c.isDigit() } },
                            label = { Text("Hours") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = minutes,
                            onValueChange = { minutes = it.filter { c -> c.isDigit() } },
                            label = { Text("Minutes") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = seconds,
                            onValueChange = { seconds = it.filter { c -> c.isDigit() } },
                            label = { Text("Seconds") },
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
                        Text("Set Custom Time")
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.LightGray)
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
fun SensorCategory(
    rooms: List<room>,
    sensorStates: MutableMap<String, Boolean>,
    sensorList: List<String>
    ) {

    create_setting_category("Sensors") {
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

fun toggle_setting() {

}
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
