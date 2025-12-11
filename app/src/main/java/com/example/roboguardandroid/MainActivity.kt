package com.example.roboguardandroid

import android.graphics.fonts.FontStyle
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.roboguardandroid.ui.theme.RoboGuardAndroidTheme
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Define the API client once, outside the Composable content
        val apiRob = RobotAPI(robotIP = "10.0.2.16")

        setContent {
                StartUI()


                LaunchedEffect(key1 = true) {
                    launch { // Launch a new coroutine within the scope provided by LaunchedEffect
                        try {
                            val response = apiRob.pingRobot()
                            Log.d("RobotAPI", "Ping response: $response")
                            val response2 = apiRob.dataToRobot("Geh nicht ins Wohnzimmer")
                            Log.d("RobotAPI", "Data response: $response2")
                        } catch (e: Exception) {
                            Log.e("RobotAPI", "API call failed", e)
                        }
                    }
                }


            }
        }
    }

@Preview(showBackground = true)
@Composable
fun StartUI() {

    RoboGuardAndroidTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            HeaderAppName()

            // Sync button
            Button(
                onClick = { syncRobot() },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)

            ) {
                Text(text = "Sync with your Robot!",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White)
            }
            Column() {


            }

        }
    }
}
@Composable
fun HeaderAppName(){
        Text(
            text = "RoboGuard \n\nPrivacy Settings", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White,

            modifier = Modifier
                .padding(top= 26.dp, bottom = 40.dp)
                .fillMaxWidth()
                .background(Color(0xFF1A73E8))
                .padding(20.dp)
        )

}

fun syncRobot() {
    Log.d("RobotAPI", "Attempting to sync with your robot")
}


