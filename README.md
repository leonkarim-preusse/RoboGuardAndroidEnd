This is the source code for the smartphone application of RoboGuard. Check [here](https://github.com/leonkarim-preusse/RoboGuard) for the robot application.

## Installation (Linux)
1. Android Studio is recommended; it already contains the Android Debug Bridge (adb) and a suitable JDK. Without Android Studio: ```sudo apt-get install adb``` and a JDK 17 or 21
2. On the phone, enable the Developer Options (usually by tapping "Build Number" 7 times until it says "You are now a developer"), then enable USB Debugging or Wireless Debugging and connect the phone (see https://developer.android.com/tools/adb for details)
3. In Android Studio, the phone appears as a target device (a wireless connection can be set up from within Android Studio); "Run" builds and installs the app
4. Without Android Studio:
   a) ```adb devices``` shows the id of the phone, needed if several devices are connected
   b) ```./gradlew installDebug``` builds and installs the app (```ANDROID_SERIAL=<id> ./gradlew installDebug``` with several devices), or build an APK with ```./gradlew assembleDebug``` and install it with ```adb -s <id> install -r app/build/outputs/apk/debug/app-debug.apk```
5. Start the app and allow the camera permission; it is only used to scan the QR code shown on the robot
6. The phone and the robot must be in the same network

## Troubleshooting
1. Uninstall: ```adb uninstall com.example.roboguardandroid```
2. Connection issues: ```adb kill-server``` ```adb start-server```
3. "Robot not reachable": check that the phone is in the robot's WiFi (not on mobile data) and that RoboGuard runs on the robot
4. Pairing no longer valid: pair again by scanning the QR code on the robot

## Features
The app is the user's way of controlling the robot's privacy settings.
- **Pairing:** the phone scans the QR code on the robot's screen and pairs with it through a one-time password; afterwards every request to the robot is signed. This code was written before the thesis.
- **Privacy settings:** switch the robot's sensors (camera, microphone, LIDAR) and the situational features (Discretion Mode = conversation detection, Pixelate Objects = object detection) on or off and send them to the robot. The app shows whether the robot applied all, some or none of the changes. This code was mostly written befor the thesis, only minor adjustments were made.
- **Navigation and Map:** the robot's map with its live position, places, saved locations and private areas. From the phone the robot can be sent to a place or a point on the map, positions can be saved, private areas created or deleted, and the robot's question whether it may cross a private area can be answered.

Entirely new additions to the code during the work on the thesis are found under ```java/com/example/roboguardandroid/NavigationScreen.kt```and ```java/com/example/roboguardandroid/NavigationModel.kt``` and ```java/com/example/roboguardandroid/DebugMode.kt```. The other files containt mostly minor adjustments. The first commit for the thesis is has the hash 3e36c0309f7f73ebf610252bd6b4587807712a31.