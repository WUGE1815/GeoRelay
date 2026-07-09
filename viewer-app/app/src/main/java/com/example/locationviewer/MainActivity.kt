package com.example.locationviewer

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 位置查看端 - 主 Activity
 *
 * 功能：
 * 1. 配置服务器地址和 Token
 * 2. 手动/自动轮询获取共享位置
 * 3. 展示位置卡片，支持复制坐标、跳转地图
 * 4. 新位置到达时弹出通知提醒
 *
 * 设计原则：
 * - 所有操作均由用户主动触发
 * - 用户可以随时停止自动刷新
 * - 不需要定位权限（只看别人共享的位置）
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LocationViewer"
        private const val NOTIFICATION_CHANNEL_ID = "location_updates"
        private const val NOTIFICATION_ID = 50001
    }

    // UI 控件
    private lateinit var etServerUrl: TextInputEditText
    private lateinit var etToken: TextInputEditText
    private lateinit var etTargetDevice: TextInputEditText
    private lateinit var etPollInterval: TextInputEditText
    private lateinit var tvStatus: TextView
    private lateinit var llLocationList: LinearLayout
    private lateinit var btnRefresh: Button
    private lateinit var btnTogglePoll: Button
    private lateinit var btnSettings: ImageButton

    // OkHttp 客户端
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Handler 用于自动轮询
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false
    private var pollRunnable: Runnable? = null

    // 上次收到的位置数据（用于判断是否有新位置）
    private var lastLocationsJson = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 创建通知渠道
        createNotificationChannel()

        // 绑定 UI
        bindViews()

        // 请求通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                // Android 13+ 需要运行时请求通知权限
                // 这里简单 toast 提示，实际项目可以用 AlertDialog
                Toast.makeText(this, "请允许通知权限以接收位置更新提醒", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun bindViews() {
        etServerUrl = findViewById(R.id.etServerUrl)
        etToken = findViewById(R.id.etToken)
        etTargetDevice = findViewById(R.id.etTargetDevice)
        etPollInterval = findViewById(R.id.etPollInterval)
        tvStatus = findViewById(R.id.tvStatus)
        llLocationList = findViewById(R.id.llLocationList)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnTogglePoll = findViewById(R.id.btnTogglePoll)
        btnSettings = findViewById(R.id.btnSettings)

        // 立即刷新按钮
        btnRefresh.setOnClickListener {
            fetchLocations()
        }

        // 自动轮询开关
        btnTogglePoll.setOnClickListener {
            if (isPolling) {
                stopPolling()
            } else {
                startPolling()
            }
        }

        // 设置按钮（跳转到系统通知设置）
        btnSettings.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
            }
        }
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "位置更新通知",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "当有人共享新位置时推送通知"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 发起一次位置查询（手动刷新或自动轮询）
     */
    private fun fetchLocations() {
        val serverUrl = etServerUrl.text?.toString()?.trim() ?: ""
        val token = etToken.text?.toString()?.trim() ?: ""
        val targetDevice = etTargetDevice.text?.toString()?.trim()

        if (serverUrl.isEmpty() || token.isEmpty()) {
            setStatus("⚠️ 请填写服务器地址和 Token")
            return
        }

        if (!isNetworkAvailable()) {
            setStatus("⚠️ 无网络连接")
            Toast.makeText(this, "无网络连接", Toast.LENGTH_SHORT).show()
            return
        }

        setStatus("🔄 正在获取位置...")

        // 构建 URL
        val baseUrl = "$serverUrl/poll?token=$token"
        val url = if (!targetDevice.isNullOrEmpty()) {
            "$baseUrl&device_id=$targetDevice"
        } else {
            baseUrl
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    setStatus("❌ 请求失败: ${e.message}")
                    Toast.makeText(this@MainActivity, "请求失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                response.close()

                runOnUiThread {
                    try {
                        val gson = Gson()
                        val json = gson.fromJson(body, JsonObject::class.java)

                        if (json.get("success")?.asBoolean == true) {
                            val locations = json.getAsJsonArray("locations")
                            val locationsStr = locations.toString()

                            // 与上次比较，如果有新数据则发通知
                            if (locationsStr != lastLocationsJson && locations.size() > 0) {
                                lastLocationsJson = locationsStr
                                showLocationCards(locations)
                                setStatus("✅ 获取到 ${locations.size()} 条位置记录")

                                // 发送通知提醒
                                sendLocationNotification(locations, serverUrl, token)
                            } else if (locations.size() == 0) {
                                lastLocationsJson = ""
                                llLocationList.removeAllViews()
                                setStatus("ℹ️ 暂无位置数据")
                            } else {
                                // 数据没变，不重复显示
                                setStatus("✅ 位置无更新")
                            }
                        } else {
                            setStatus("❌ 服务器返回错误: ${json.get("error")?.asString}")
                        }
                    } catch (e: Exception) {
                        setStatus("❌ 解析数据失败: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        })
    }

    /**
     * 将位置数据渲染为卡片列表
     */
    private fun showLocationCards(locations: JsonArray) {
        llLocationList.removeAllViews()

        for (i in 0 until locations.size()) {
            val obj = locations[i] as JsonObject
            val deviceId = obj.get("device_id")?.asString ?: "未知设备"
            val lng = obj.get("lng")?.asDouble ?: 0.0
            val lat = obj.get("lat")?.asDouble ?: 0.0
            val timestamp = obj.get("timestamp")?.asString ?: ""

            // 加载卡片布局
            val cardView = LayoutInflater.from(this).inflate(R.layout.item_location, llLocationList, false)

            val tvDevice = cardView.findViewById<TextView>(R.id.tvDeviceId)
            val tvTime = cardView.findViewById<TextView>(R.id.tvTime)
            val tvCoords = cardView.findViewById<TextView>(R.id.tvCoordinates)
            val btnCopy = cardView.findViewById<Button>(R.id.btnCopyCoords)
            val btnMap = cardView.findViewById<Button>(R.id.btnOpenMap)

            tvDevice.text = deviceId
            tvTime.text = timestamp
            tvCoords.text = String.format(Locale.getDefault(), "经度: %.6f, 纬度: %.6f", lng, lat)

            // 复制坐标按钮
            btnCopy.setOnClickListener {
                val clip = ClipData.newPlainText("coordinates", "$lng, $lat")
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(clip)
                Toast.makeText(this, "坐标已复制: $lng, $lat", Toast.LENGTH_SHORT).show()
            }

            // 打开地图按钮（跳转 Google Maps）
            btnMap.setOnClickListener {
                val uri = "geo:$lng,$lat?q=$lng,$lat($deviceId)"
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                startActivity(intent)
            }

            llLocationList.addView(cardView)
        }
    }

    /**
     * 发送位置更新通知
     */
    private fun sendLocationNotification(locations: JsonArray, serverUrl: String, token: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val latest = locations[0] as JsonObject
        val deviceId = latest.get("device_id")?.asString ?: "设备"
        val lng = latest.get("lng")?.asDouble ?: 0.0
        val lat = latest.get("lat")?.asDouble ?: 0.0

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("新位置: $deviceId")
            .setContentText("经度: $lng, 纬度: $lat")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            // 点击通知打开地图
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:$lng,$lat")),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification.build())
    }

    /**
     * 开始自动轮询
     */
    private fun startPolling() {
        val intervalSec = etPollInterval.text?.toString()?.toIntOrNull() ?: 10

        isPolling = true
        btnTogglePoll.text = "⏸️ 停止自动刷新"
        btnTogglePoll.setBackgroundColor(
            ContextCompat.getColor(this, android.R.color.holo_red_light)
        )
        setStatus("🔄 自动刷新已开启，间隔 ${intervalSec}s")

        pollRunnable = object : Runnable {
            override fun run() {
                fetchLocations()
                // 定时重复执行
                handler.postDelayed(this, (intervalSec * 1000).toLong())
            }
        }

        handler.postDelayed(pollRunnable!!, intervalSec * 1000L)

        // 首次立即执行
        fetchLocations()
    }

    /**
     * 停止自动轮询
     */
    private fun stopPolling() {
        isPolling = false
        pollRunnable?.let { handler.removeCallbacks(it) }
        btnTogglePoll.text = "▶️ 开始自动刷新"
        btnTogglePoll.setBackgroundColor(
            ContextCompat.getColor(this, android.R.color.holo_blue_bright)
        )
        setStatus("⏸️ 自动刷新已停止")
    }

    /**
     * 检查网络是否可用
     */
    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 更新状态栏文本
     */
    private fun setStatus(text: String) {
        tvStatus.text = text
    }

    override fun onDestroy() {
        super.onDestroy()
        // Activity 退出时停止轮询
        stopPolling()
    }
}
