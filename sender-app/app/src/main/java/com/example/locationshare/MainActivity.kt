package com.example.locationshare

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY
import com.google.android.material.textfield.TextInputEditText
import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 位置共享端 - 主 Activity
 *
 * 这是共享端的入口页面，用户在这里：
 * 1. 配置服务器地址、设备 ID、鉴权 Token
 * 2. 手动点击"开始共享"按钮启动定位上传服务
 * 3. 随时可以看到当前的共享状态
 *
 * 关键设计原则：
 * - 所有功能都需要用户主动操作触发
 * - 首页永久显示"位置共享中"状态指示
 * - 无隐藏后台运行
 * - 无绕过电池优化的强制手段
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LocationShare"
        private const val REQUEST_LOCATION_PERMISSION = 1001
        private const val NOTIFICATION_CHANNEL_ID = "location_share_channel"
        private const val NOTIFICATION_ID = 10001
    }

    // Google 融合定位服务客户端
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // OkHttp 客户端（连接/读取超时各 15 秒）
    private lateinit var okHttpClient: OkHttpClient

    // UI 控件引用
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var tvLastLocation: TextView
    private lateinit var tvLogs: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnManualUpload: TextInputEditText
    private lateinit var etServerUrl: TextInputEditText
    private lateinit var etDeviceId: TextInputEditText
    private lateinit var etToken: TextInputEditText
    private lateinit var etInterval: TextInputEditText

    // 共享状态标志
    private var isSharing = false
    private var shareService: LocationShareService? = null

    // 离线缓存队列（断网时暂存待上传的定位）
    private val offlineQueue = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化 Google 定位服务
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 初始化 OkHttp 客户端
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        // 创建通知渠道（Android 8.0+ 必需）
        createNotificationChannel()

        // 绑定 UI 控件
        bindViews()

        // 设置默认设备 ID
        val defaultDeviceId = generateDeviceId()
        etDeviceId.setText(defaultDeviceId)

        // 请求定位权限
        requestLocationPermissions()
    }

    /**
     * 绑定布局中的 UI 控件
     */
    private fun bindViews() {
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        tvLastLocation = findViewById(R.id.tvLastLocation)
        tvLogs = findViewById(R.id.tvLogs)
        btnToggle = findViewById(R.id.btnToggle)
        btnManualUpload = findViewById(R.id.btnManualUpload)
        etServerUrl = findViewById(R.id.etServerUrl)
        etDeviceId = findViewById(R.id.etDeviceId)
        etToken = findViewById(R.id.etToken)
        etInterval = findViewById(R.id.etInterval)

        // 开始/停止按钮点击事件
        btnToggle.setOnClickListener {
            if (isSharing) {
                stopSharing()
            } else {
                startSharing()
            }
        }

        // 手动上传按钮
        findViewById<Button>(R.id.btnManualUpload).setOnClickListener {
            uploadLocationOnce()
        }
    }

    /**
     * 创建 Android 通知渠道
     * 前台服务必须有对应的通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.share_channel_name),
                NotificationManager.IMPORTANCE_LOW  // 低重要性，不弹横幅
            ).apply {
                description = getString(R.string.share_channel_desc)
                setShowBadge(false)  // 不显示角标
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 请求定位权限
     * Android 10+ 需要 ACCESS_FINE_LOCATION 或 ACCESS_COARSE_LOCATION
     */
    private fun requestLocationPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            logMessage("✅ 所有定位权限已授予")
        } else {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_LOCATION_PERMISSION
            )
        }
    }

    /**
     * 处理权限请求结果
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                logMessage("✅ 定位权限已全部授予")
            } else {
                logMessage("❌ 定位权限被拒绝，部分功能不可用")
                Toast.makeText(this, "需要定位权限才能共享位置", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 生成默认设备 ID（基于 Android ID）
     */
    private fun generateDeviceId(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return "device_${androidId.takeLast(8)}"
    }

    /**
     * 开始位置共享
     * 用户手动点击按钮后才会启动
     */
    private fun startSharing() {
        // 检查定位权限
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "请先授予定位权限", Toast.LENGTH_SHORT).show()
            logMessage("⚠️ 缺少定位权限，无法开始共享")
            return
        }

        // 检查网络
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "当前无网络连接", Toast.LENGTH_SHORT).show()
            logMessage("⚠️ 没有可用网络")
            return
        }

        val serverUrl = etServerUrl.text?.toString()?.trim() ?: ""
        val deviceId = etDeviceId.text?.toString()?.trim() ?: ""
        val token = etToken.text?.toString()?.trim() ?: ""

        if (serverUrl.isEmpty() || deviceId.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "请填写完整的服务器地址、设备 ID 和 Token", Toast.LENGTH_SHORT).show()
            logMessage("⚠️ 配置信息不完整")
            return
        }

        // 启动前台服务
        val intervalSec = etInterval.text?.toString()?.toIntOrNull() ?: 30

        val intent = Intent(this, LocationShareService::class.java).apply {
            action = LocationShareService.ACTION_START
            putExtra("server_url", serverUrl)
            putExtra("device_id", deviceId)
            putExtra("token", token)
            putExtra("interval_sec", intervalSec)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isSharing = true
        updateUIForSharing(true)
        logMessage("✅ 位置共享已启动，间隔 ${intervalSec} 秒")
        Toast.makeText(this, "位置共享已开始", Toast.LENGTH_SHORT).show()
    }

    /**
     * 停止位置共享
     */
    private fun stopSharing() {
        val intent = Intent(this, LocationShareService::class.java).apply {
            action = LocationShareService.ACTION_STOP
        }
        startService(intent)

        isSharing = false
        updateUIForSharing(false)
        logMessage("🛑 位置共享已停止")
        Toast.makeText(this, "位置共享已停止", Toast.LENGTH_SHORT).show()
    }

    /**
     * 更新 UI 显示共享状态
     */
    private fun updateUIForSharing(sharing: Boolean) {
        if (sharing) {
            tvStatusTitle.text = "🟢 位置共享已开启"
            tvStatusTitle.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            tvStatusDetail.text = "正在实时共享您的位置..."
            btnToggle.text = "🔴 停止位置共享"
            findViewById<Button>(R.id.btnManualUpload).isEnabled = false
        } else {
            tvStatusTitle.text = "🔴 位置共享已停止"
            tvStatusTitle.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            tvStatusDetail.text = "点击按钮开始共享位置"
            btnToggle.text = "🟢 开始位置共享"
            findViewById<Button>(R.id.btnManualUpload).isEnabled = true
        }
    }

    /**
     * 手动上传一次定位
     * 即使用户未开启自动共享，也可以手动上传
     */
    private fun uploadLocationOnce() {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "无网络连接，无法上传", Toast.LENGTH_SHORT).show()
            logMessage("⚠️ 没有可用网络")
            return
        }

        logMessage("📍 正在获取定位...")
        tvStatusDetail.text = "正在获取定位..."

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "需要定位权限", Toast.LENGTH_SHORT).show()
            logMessage("❌ 缺少定位权限")
            return
        }

        // 使用 Google 融合定位获取一次坐标
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val lng = location.longitude
                val lat = location.latitude
                val timeStr = java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.getDefault()
                ).format(java.util.Date(location.time))

                tvLastLocation.text = "最后位置: 经度 $lng, 纬度 $lat"

                logMessage("📍 定位成功: $lng, $lat")

                // 构造上传请求
                val serverUrl = etServerUrl.text?.toString()?.trim() ?: "http://127.0.0.1:5000"
                val deviceId = etDeviceId.text?.toString()?.trim() ?: "unknown"
                val token = etToken.text?.toString()?.trim() ?: ""

                val payload = """
                    {
                        "token": "$token",
                        "device_id": "$deviceId",
                        "lng": $lng,
                        "lat": $lat,
                        "timestamp": "$timeStr"
                    }
                """.trimIndent()

                uploadToServer(serverUrl, payload) { success, msg ->
                    runOnUiThread {
                        if (success) {
                            logMessage("✅ $msg")
                            Toast.makeText(this, "上传成功", Toast.LENGTH_SHORT).show()
                        } else {
                            logMessage("❌ $msg")
                            Toast.makeText(this, "上传失败: $msg", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                logMessage("❌ 无法获取定位，请确保 GPS 已开启")
                Toast.makeText(this, "无法获取定位", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener {
            logMessage("❌ 定位获取失败: ${it.message}")
            Toast.makeText(this, "定位失败: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 通过 OkHttp 上传定位数据到服务器
     */
    private fun uploadToServer(serverUrl: String, jsonPayload: String, callback: (Boolean, String) -> Unit) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonPayload.toRequestBody(mediaType)

        val uploadUrl = "$serverUrl/upload_loc"
        val request = Request.Builder()
            .url(uploadUrl)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false, "网络错误: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                if (response.code in 200..299) {
                    callback(true, "上传成功: $body")
                } else {
                    callback(false, "服务器错误 ${response.code}: $body")
                }
            }
        })
    }

    /**
     * 检查网络是否可用
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
            ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 向日志区域追加消息
     */
    private fun logMessage(msg: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $msg\n"
        runOnUiThread {
            tvLogs.text = tvLogs.text.toString() + logEntry
            // 自动滚动到底部
            val parent = tvLogs.parent as ScrollView
            parent.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Activity 销毁时不自动停止服务
        // 用户需要手动点击"停止共享"按钮
    }
}

// 补充 Date import
import java.util.Date
