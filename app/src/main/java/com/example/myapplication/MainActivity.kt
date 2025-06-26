package com.example.myapplication

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.PolylineOptions
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlin.math.*

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var mapView: MapView
    private var locationClient: AMapLocationClient? = null

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var stepDetectorSensor: Sensor? = null
    private var stepCounterSensor: Sensor? = null

    private var useStepDetector = false
    private var useStepCounter = false

    private var initialStepCount = -1

    private val accelerationThreshold = 0.1f
    private val stepDetectionThreshold = 1.0f
    private var lastStepDetected = false
    private var lastAccel = 0f
    private val alpha = 0.8f

    private var isMoving = false

    private var stepCountGlobal = 0
    private var isRecordingGlobal = false
    private var distanceGlobal = 0.0
    private var lastLatLngGlobal: LatLng? = null
    private var lastTimestampGlobal: Long? = null
    private val pathPointsGlobal = mutableListOf<LatLng>()

    private val maxDistanceThreshold = 5.0
    private val maxSpeedThreshold = 10.0
    private val minDistanceThreshold = 0.5

    // 新增：平均步长（米），可根据实际调整或提供用户配置
    private val STEP_LENGTH_IN_METERS = 0.7

    private var lastStepTimestamp: Long = 0
    private val stepDelayThreshold = 1000L  // 设置一个时间阈值，防止短时间内重复记录步数

    private var isFirstStep = true  // 标记是否是第一次检测步数

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)

        // 权限申请
        val locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                initLocationClient()
            } else {
                Log.e("TrackerDebug", "定位权限未授权")
            }
        }

        val activityRecognitionPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                Log.d("TrackerDebug", "活动识别权限已授权")
            } else {
                Log.e("TrackerDebug", "ACTIVITY_RECOGNITION 权限未授权，无法计步")
            }
        }

        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        activityRecognitionPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        useStepDetector = stepDetectorSensor != null
        useStepCounter = !useStepDetector && stepCounterSensor != null

        if (!useStepDetector && !useStepCounter) {
            Log.w("TrackerDebug", "设备不支持计步传感器，使用加速度传感器降级算法")
        } else {
            Log.i("TrackerDebug", "计步传感器支持 - STEP_DETECTOR: $useStepDetector, STEP_COUNTER: $useStepCounter")
        }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(savedInstanceState)
                }
            }
        }
    }

    private fun initLocationClient() {
        if (locationClient == null) {
            locationClient = AMapLocationClient(this)
            val option = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                interval = 1000
                isOnceLocation = false
            }
            locationClient!!.setLocationOption(option)

            locationClient!!.setLocationListener { location ->
                if (location.errorCode == 0) {
                    if (location.accuracy > 30) {
                        Log.d("TrackerDebug", "定位精度差，忽略点: ${location.accuracy}米")
                        return@setLocationListener
                    }
                    val current = LatLng(location.latitude, location.longitude)
                    val currentTime = System.currentTimeMillis()

                    if (isRecordingGlobal) {
                        lastLatLngGlobal?.let {
                            val dist = calculateDistance(it.latitude, it.longitude, current.latitude, current.longitude)
                            val timeDiff = if (lastTimestampGlobal != null) (currentTime - lastTimestampGlobal!!) / 1000.0 else 1.0
                            val speed = dist / timeDiff

                            Log.d("TrackerDebug", "路径点距离: $dist 米, 速度: $speed 米/秒")

                            if (dist in minDistanceThreshold..maxDistanceThreshold && speed <= maxSpeedThreshold) {
                                pathPointsGlobal.add(current)
                                lastLatLngGlobal = current
                                lastTimestampGlobal = currentTime
                                Log.d("TrackerDebug", "有效路径点，更新地图轨迹")
                            } else {
                                Log.d("TrackerDebug", "忽略路径点：跳点或静止")
                            }
                        } ?: run {
                            pathPointsGlobal.add(current)
                            lastLatLngGlobal = current
                            lastTimestampGlobal = currentTime
                            Log.d("TrackerDebug", "记录首个路径点")
                        }
                    } else {
                        Log.d("TrackerDebug", "未录制，忽略定位点")
                    }
                } else {
                    Log.e("TrackerDebug", "定位错误: ${location.errorCode}, ${location.errorInfo}")
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                if (isFirstStep) {
                    // 跳过第一次步数检测
                    isFirstStep = false
                    return
                }

                val currentTime = System.currentTimeMillis()
                // 如果当前时间距离上次步数检测时间大于阈值，则更新步数
                if (currentTime - lastStepTimestamp > stepDelayThreshold) {
                    stepCountGlobal++
                    distanceGlobal = stepCountGlobal * STEP_LENGTH_IN_METERS
                    lastStepTimestamp = currentTime  // 更新步数检测时间
                    Log.d("TrackerDebug", "STEP_DETECTOR检测到一步，步数: $stepCountGlobal，距离: $distanceGlobal 米")
                } else {
                    Log.d("TrackerDebug", "忽略重复步数检测")
                }
            }

            Sensor.TYPE_STEP_COUNTER -> {
                val totalSteps = event.values[0].toInt()
                if (initialStepCount < 0) {
                    initialStepCount = totalSteps
                }
                stepCountGlobal = totalSteps - initialStepCount
                distanceGlobal = stepCountGlobal * STEP_LENGTH_IN_METERS
                Log.d("TrackerDebug", "STEP_COUNTER当前步数: $stepCountGlobal，距离: $distanceGlobal 米")
            }

            Sensor.TYPE_ACCELEROMETER -> {
                if (!useStepDetector && !useStepCounter) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]

                    val accelCurrent = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH
                    val filteredAccel = alpha * lastAccel + (1 - alpha) * accelCurrent
                    lastAccel = filteredAccel

                    isMoving = filteredAccel > accelerationThreshold

                    if (filteredAccel > stepDetectionThreshold) {
                        if (!lastStepDetected) {
                            stepCountGlobal++
                            distanceGlobal = stepCountGlobal * STEP_LENGTH_IN_METERS
                            lastStepDetected = true
                            Log.d("TrackerDebug", "加速度法检测到一步，步数: $stepCountGlobal，距离: $distanceGlobal 米")
                        }
                    } else {
                        lastStepDetected = false
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @Composable
    fun MainScreen(savedInstanceState: Bundle?) {
        var isRecording by remember { mutableStateOf(false) }
        var elapsedTime by remember { mutableStateOf(0L) }
        val savedRecords = remember { mutableStateListOf<Record>() }

        val context = LocalContext.current

        LaunchedEffect(Unit) {
            initLocationClient()
        }

        // 注册传感器监听
        LaunchedEffect(isRecording) {
            if (isRecording) {
                when {
                    useStepDetector -> sensorManager.registerListener(this@MainActivity, stepDetectorSensor, SensorManager.SENSOR_DELAY_NORMAL)
                    useStepCounter -> sensorManager.registerListener(this@MainActivity, stepCounterSensor, SensorManager.SENSOR_DELAY_NORMAL)
                    else -> sensorManager.registerListener(this@MainActivity, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
                }
                while (isRecording) {
                    kotlinx.coroutines.delay(1000)
                    elapsedTime += 1000
                }
            } else {
                sensorManager.unregisterListener(this@MainActivity)
                initialStepCount = -1
            }
        }

        LaunchedEffect(isRecording) {
            isRecordingGlobal = isRecording
            if (isRecording) {
                Log.d("TrackerDebug", "点击开始，isRecording = true")
                Log.d("TrackerDebug", "开始定位")
                locationClient?.startLocation()
            } else {
                Log.d("TrackerDebug", "点击暂停，isRecording = false")
                locationClient?.stopLocation()
                lastLatLngGlobal = null
                lastTimestampGlobal = null
                Log.d("TrackerDebug", "暂停定位，清除 lastLatLng 和 lastTimestamp")
            }
        }

        Column(Modifier.fillMaxSize()) {
            AndroidView(
                factory = {
                    mapView = MapView(it)
                    mapView.onCreate(savedInstanceState)
                    val aMap = mapView.map
                    aMap.uiSettings.isMyLocationButtonEnabled = true
                    aMap.isMyLocationEnabled = true
                    mapView
                },
                modifier = Modifier.weight(1f)
            ) { mapView ->
                val aMap = mapView.map
                aMap.clear()
                if (pathPointsGlobal.isNotEmpty()) {
                    aMap.addPolyline(
                        PolylineOptions()
                            .addAll(pathPointsGlobal)
                            .color(0xAA0000FF.toInt())
                            .width(10f)
                    )
                }
                lastLatLngGlobal?.let {
                    aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 17f))
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "时间: ${formatTime(elapsedTime)}    里程: ${"%.2f".format(distanceGlobal / 1000)} 千米    步数: $stepCountGlobal",
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = {
                        stepCountGlobal = 0
                        distanceGlobal = 0.0
                        elapsedTime = 0L
                        pathPointsGlobal.clear()
                        lastLatLngGlobal = null
                        lastTimestampGlobal = null
                        isRecording = true

                        // 强制标记为第一次检测步数
                        isFirstStep = true
                    },
                    enabled = !isRecording
                ) {
                    Text("开始")
                }
                Button(
                    onClick = { isRecording = false },
                    enabled = isRecording
                ) {
                    Text("暂停")
                }
                Button(
                    onClick = {
                        if (pathPointsGlobal.isNotEmpty()) {
                            savedRecords.add(
                                Record(
                                    distanceGlobal / 1000,
                                    elapsedTime,
                                    stepCountGlobal,
                                    pathPointsGlobal.toList()
                                )
                            )
                            isRecording = false
                            elapsedTime = 0L
                            distanceGlobal = 0.0
                            stepCountGlobal = 0
                            pathPointsGlobal.clear()
                            lastLatLngGlobal = null
                            lastTimestampGlobal = null
                        }
                    },
                    enabled = pathPointsGlobal.isNotEmpty() && !isRecording
                ) {
                    Text("保存")
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "已保存记录 (${savedRecords.size})",
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.7f)
                    .padding(8.dp)
            ) {
                itemsIndexed(savedRecords) { index, record ->
                    Text(
                        text = "记录${index + 1}: 时间 ${formatTime(record.elapsedTime)}，距离 ${"%.2f".format(record.distance)} 千米，步数 ${record.stepCount}",
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) mapView.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mapView.isInitialized) mapView.onDestroy()
        locationClient?.onDestroy()
    }

    data class Record(
        val distance: Double,
        val elapsedTime: Long,
        val stepCount: Int,
        val pathPoints: List<LatLng>
    )

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun formatTime(millis: Long): String {
        val seconds = millis / 1000
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }
}
//
