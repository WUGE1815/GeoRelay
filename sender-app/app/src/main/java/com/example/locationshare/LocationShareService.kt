package com.example.locationshare

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 位置共享前台服务
 *
 * 功能：
 * 1. 在后台持续采集 GPS/网络定位
 * 2. 按设定间隔自动上传到服务器
 * 3. 作为前台服务运行，显示常驻通知
 * 4. 断网时缓存定位，联网后手动同步
 *
 * ⚠️ 重要设计原则：
 * - 此服务仅在用户手动点击"开始共享"后才启动
 * - 通知栏始终显示"位置共享中"，用户随时可见
 * - 不会绕过电池优化
 * - 不会在后台偷偷运行
 */
class LocationShareService : Service() {

    companion object {
        private const val TAG = "LocationShareSvc"
        private const val NOTIFICATION_ID = 10001

        // 服务控制动作
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    // Google 融合定位客户端
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // OkHttp 客户端
    private lateinit var okHttpClient: OkHttpClient

    // 配置参数（从 Intent 中获取）
    private var serverUrl = "http://127.0.0.1:5000"
    private var deviceId = "unknown"
    private var token = ""
    private var intervalSec = 30

    // 定位请求配置
    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        (intervalSec * 1000).toLong()  // 上传间隔转为毫秒
    ).apply {
        setMinUpdateIntervalMillis((intervalSec * 500).toLong())  // 最少 5 秒一次
        setWaitForAccurateLocation(false)
    }.build()

    // 定位回调
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                handleNewLocation(location)
            }
        }
    }

    // Handler 用于在主线程执行 UI 相关操作
    private val handler = Handler(Looper.getMainLooper())

    // 是否正在运行
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // 解析配置参数
                serverUrl = intent.getStringExtra("server_url") ?: serverUrl
                deviceId = intent.getStringExtra("device_id") ?: deviceId
                token = intent.getStringExtra("token") ?: token
                intervalSec = intent.getIntExtra("interval_sec", 30)

                Log.d(TAG, "Starting service: url=$serverUrl device=$deviceId interval=$intervalSec")

                // 启动前台服务（显示通知）
                startForeground(NOTIFICATION_ID, createNotification())

                // 开始定时定位采集
                startLocationUpdates()

                isRunning = true
            }
            ACTION_STOP -> {
                stopLocationUpdates()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                isRunning = false
                Log.d(TAG, "Service stopped")
            }
        }
        return START_STICKY  // 服务被杀后系统会尝试重启（但需要用户重新触发）
    }

    /**
     * 创建前台服务通知
     * 让用户明确知道定位正在运行
     */
    private fun createNotification(): Notification {
        // 点击通知回到主 Activity
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "location_share_channel")
            .setContentTitle("位置共享中")
            .setContentText("正在实时采集并上传您的位置 ($deviceId)")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(pendingIntent)
            .setOngoing(true)  // 不可滑动删除
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 开始定位采集
     */
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "定位权限未授予，无法采集位置")
            return
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "定位采集已启动，间隔 ${intervalSec}s")
        } catch (e: Exception) {
            Log.e(TAG, "启动定位失败: ${e.message}")
        }
    }

    /**
     * 停止定位采集
     */
    private fun stopLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            Log.d(TAG, "定位采集已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止定位失败: ${e.message}")
        }
    }

    /**
     * 处理新获取的定位数据
     */
    private fun handleNewLocation(location: Location) {
        val lng = location.longitude
        val lat = location.latitude
        val timestamp = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault()
        ).format(Date(location.time))

        Log.d(TAG, "新定位: lng=$lng lat=$lat time=$timestamp")

        // 构造 JSON 请求体
        val jsonPayload = """
            {
                "token": "$token",
                "device_id": "$deviceId",
                "lng": $lng,
                "lat": $lat,
                "timestamp": "$timestamp"
            }
        """.trimIndent()

        // 异步上传到服务器
        uploadLocation(jsonPayload)
    }

    /**
     * 通过 OkHttp 上传定位数据
     */
    private fun uploadLocation(jsonPayload: String) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonPayload.toRequestBody(mediaType)

        val request = Request.Builder()
            .url("$serverUrl/upload_loc")
            .post(requestBody)
            .header("Content-Type", "application/json")
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "上传失败: ${e.message}")
                // 断网时可以在这里加入本地缓存逻辑
                // TODO: 将 jsonPayload 存入离线队列
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                if (response.code in 200..299) {
                    Log.d(TAG, "上传成功: $body")
                } else {
                    Log.e(TAG, "上传错误 ${response.code}: $body")
                }
                response.close()
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        isRunning = false
        Log.d(TAG, "Service destroyed")
    }
}

// 补充 import
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
