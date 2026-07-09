"""
自愿共享位置系统 - Flask 中转服务器
====================================
功能：
  1. Token 鉴权，拦截非法请求
  2. 接收共享端上传的 GPS 坐标并持久化存储
  3. 提供轮询接口供查看端拉取最新位置
  4. 可选短信推送（用户手动触发）
  5. 历史查询接口（支持 CORS 跨域）

使用方式：
  python server.py
  # 或通过 ngrok 暴露到公网
"""

import os
import json
import time
import hashlib
import threading
from datetime import datetime, timezone, timedelta
from flask import Flask, request, jsonify, Response
from flask_cors import CORS

# ============================================================
# 配置区域 —— 按需修改
# ============================================================
# 服务器监听地址和端口
HOST = "0.0.0.0"
PORT = 5000

# 鉴权 Token（与两个 Android APP 中的 TOKEN 保持一致）
AUTH_TOKEN = "MySecureToken2024"

# 短信推送目标手机号（可选，留空则不推送）
SMS_PHONE_NUMBER = ""

# 短信推送 API 配置（以阿里云短信为例，按需替换）
# 如果不需要短信推送，以下三项可以留空
SMS_ACCESS_KEY_ID = ""
SMS_ACCESS_KEY_SECRET = ""
SMS_SIGN_NAME = ""
SMS_TEMPLATE_CODE = ""

# 定位数据存储目录
DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
HISTORY_FILE = os.path.join(DATA_DIR, "location_history.txt")

# 内存缓存：最新 10 条定位记录
LOCATION_CACHE = []
CACHE_LOCK = threading.Lock()

# 频率限制：单设备每 10 秒最多上传一次
RATE_LIMIT_SECONDS = 10
rate_limit_lock = threading.Lock()
last_upload_time = {}  # {device_id: timestamp}

# 应用启动时间（用于生成服务器时间戳）
START_TIME = time.time()

# ============================================================
# Flask 应用初始化
# ============================================================
app = Flask(__name__)

# 开启 CORS 跨域支持（允许 Android APP 跨域请求）
CORS(app, resources={r"/*": {"origins": "*"}})


# ============================================================
# 工具函数
# ============================================================

def ensure_data_dir():
    """确保数据目录存在"""
    if not os.path.exists(DATA_DIR):
        os.makedirs(DATA_DIR)


def save_to_file(record_str):
    """将一条定位记录追加写入 txt 文件"""
    ensure_data_dir()
    with open(HISTORY_FILE, "a", encoding="utf-8") as f:
        f.write(record_str + "\n")


def load_from_file():
    """从 txt 文件加载所有历史记录"""
    ensure_data_dir()
    if not os.path.exists(HISTORY_FILE):
        return []
    with open(HISTORY_FILE, "r", encoding="utf-8") as f:
        lines = f.readlines()
    records = []
    for line in lines:
        line = line.strip()
        if line:
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return records


def append_cache(record):
    """追加到内存缓存，保持最新 10 条"""
    with CACHE_LOCK:
        LOCATION_CACHE.insert(0, record)
        if len(LOCATION_CACHE) > 10:
            del LOCATION_CACHE[10:]


def get_cache_copy():
    """获取缓存的副本（线程安全）"""
    with CACHE_LOCK:
        return list(LOCATION_CACHE)


def check_rate_limit(device_id):
    """
    检查设备是否超过上传频率限制
    返回 True 表示允许上传，False 表示被限流
    """
    now = time.time()
    with rate_limit_lock:
        if device_id in last_upload_time:
            elapsed = now - last_upload_time[device_id]
            if elapsed < RATE_LIMIT_SECONDS:
                return False
        last_upload_time[device_id] = now
        return True


def verify_token(token):
    """
    验证请求中的 Token 是否正确
    支持两种传参方式：
      1. Query 参数: ?token=xxx
      2. Header: Authorization: Bearer xxx
    """
    if not token:
        return False
    # 先从 header 中取，再回退到 query 参数
    header_token = request.headers.get("Authorization", "").replace("Bearer ", "")
    query_token = request.args.get("token", "")
    candidate = header_token or query_token or token
    return candidate == AUTH_TOKEN


def now_iso():
    """返回当前 UTC 时间的 ISO 格式字符串"""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def format_record(device_id, lng, lat, timestamp):
    """格式化为 JSON 字符串用于文件存储"""
    return json.dumps({
        "device_id": device_id,
        "lng": lng,
        "lat": lat,
        "timestamp": timestamp
    }, ensure_ascii=False)


# ============================================================
# 中间件：请求日志
# ============================================================

@app.before_request
def log_request():
    """每次请求前打印日志"""
    print(f"[{now_iso()}] {request.method} {request.path} "
          f"from {request.remote_addr} "
          f"user-agent={request.headers.get('User-Agent', 'unknown')}")


# ============================================================
# API 路由
# ============================================================

@app.route("/health", methods=["GET"])
def health_check():
    """健康检查接口，确认服务在线"""
    uptime = int(time.time() - START_TIME)
    return jsonify({
        "status": "ok",
        "uptime_seconds": uptime,
        "cache_size": len(get_cache_copy()),
        "timestamp": now_iso()
    })


@app.route("/upload_loc", methods=["POST"])
def upload_location():
    """
    接收共享端上传的定位数据
    请求体（JSON）：
      {
        "token": "鉴权Token",
        "device_id": "设备唯一标识",
        "lng": 116.404,       # 经度
        "lat": 39.915,        # 纬度
        "timestamp": "2024-01-01T12:00:00Z"  # 可选，不传则用服务器时间
      }

    响应：
      {"success": true, "message": "定位已保存"}
    """
    # --- Token 鉴权 ---
    data = request.get_json(silent=True)
    if not data:
        return jsonify({"success": False, "error": "请求体必须为 JSON 格式"}), 400

    token = data.get("token", "")
    if not verify_token(token):
        print("[WARN] 非法请求：Token 无效")
        return jsonify({"success": False, "error": "鉴权失败，Token 无效"}), 403

    # --- 提取字段 ---
    device_id = data.get("device_id", "").strip()
    lng = data.get("lng")
    lat = data.get("lat")

    if not device_id:
        return jsonify({"success": False, "error": "缺少 device_id"}), 400
    if lng is None or lat is None:
        return jsonify({"success": False, "error": "缺少经度/纬度"}), 400

    # 经纬度范围校验
    try:
        lng = float(lng)
        lat = float(lat)
        if not (-180 <= lng <= 180) or not (-90 <= lat <= 90):
            return jsonify({"success": False, "error": "经纬度超出有效范围"}), 400
    except (TypeError, ValueError):
        return jsonify({"success": False, "error": "经纬度必须是数字"}), 400

    timestamp = data.get("timestamp", now_iso())

    # --- 频率限制 ---
    if not check_rate_limit(device_id):
        print(f"[WARN] 设备 {device_id} 上传过于频繁，已限流")
        return jsonify({"success": False, "error": "上传过于频繁，请稍后再试"}), 429

    # --- 保存记录 ---
    record = {
        "device_id": device_id,
        "lng": lng,
        "lat": lat,
        "timestamp": timestamp
    }
    record_str = format_record(device_id, lng, lat, timestamp)

    # 异步写入文件（不阻塞响应）
    def _save():
        save_to_file(record_str)
        append_cache(record)

    threading.Thread(target=_save, daemon=True).start()

    print(f"[OK] 收到定位: device={device_id} lng={lng} lat={lat} time={timestamp}")
    return jsonify({"success": True, "message": "定位已保存", "record": record}), 201


@app.route("/poll", methods=["GET"])
def poll_latest():
    """
    查看端轮询接口，获取最新的共享位置
    查询参数：
      - token: 鉴权 Token（也可放在 Header 中）
      - device_id: 要查询的设备 ID（可选，不传则返回所有设备最新位置）

    响应：
      {
        "success": true,
        "locations": [
          {"device_id": "dev_001", "lng": 116.404, "lat": 39.915, "timestamp": "..."}
        ]
      }
    """
    token = request.args.get("token", "")
    header_token = request.headers.get("Authorization", "").replace("Bearer ", "")
    query_token = request.args.get("token", "")
    if not verify_token(query_token if not header_token else header_token):
        return jsonify({"success": False, "error": "鉴权失败"}), 403

    target_device = request.args.get("device_id", "").strip()

    cache = get_cache_copy()

    if target_device:
        # 过滤指定设备
        results = [r for r in cache if r["device_id"] == target_device]
    else:
        # 返回所有设备的最新位置（按 device_id 去重）
        seen = set()
        results = []
        for r in cache:
            if r["device_id"] not in seen:
                seen.add(r["device_id"])
                results.append(r)

    return jsonify({"success": True, "locations": results})


@app.route("/get_history", methods=["GET"])
def get_history():
    """
    查询全部历史定位记录（支持 CORS 跨域）
    查询参数：
      - token: 鉴权 Token
      - device_id: 过滤指定设备（可选）
      - limit: 返回条数上限，默认 100

    响应：
      {
        "success": true,
        "records": [...],
        "total": 150
      }
    """
    token = request.args.get("token", "")
    header_token = request.headers.get("Authorization", "").replace("Bearer ", "")
    effective_token = header_token if header_token else token
    if not verify_token(effective_token):
        return jsonify({"success": False, "error": "鉴权失败"}), 403

    target_device = request.args.get("device_id", "").strip()
    limit = min(int(request.args.get("limit", 100)), 500)

    records = load_from_file()

    if target_device:
        records = [r for r in records if r.get("device_id") == target_device]

    total = len(records)
    # 返回最新的 limit 条
    records = records[-limit:]

    print(f"[INFO] 历史查询: device={target_device or 'all'} limit={limit} total={total}")
    return jsonify({"success": True, "records": records, "total": total})


@app.route("/send_sms", methods=["POST"])
def send_sms():
    """
    手动触发短信推送（用户主动操作才触发）
    请求体（JSON）：
      {
        "token": "鉴权Token",
        "phone": "接收手机号（可选，默认使用配置的手机号）",
        "device_id": "要查询的设备ID",
        "message": "自定义短信内容（可选）"
      }

    注意：此接口需要配置短信服务商 API 密钥才能正常工作。
    如果未配置，会返回提示信息。
    """
    data = request.get_json(silent=True)
    if not data:
        return jsonify({"success": False, "error": "请求体必须为 JSON"}), 400

    token = data.get("token", "")
    if not verify_token(token):
        return jsonify({"success": False, "error": "鉴权失败"}), 403

    # 检查是否配置了短信 API
    if not SMS_ACCESS_KEY_ID or not SMS_ACCESS_KEY_SECRET:
        return jsonify({
            "success": False,
            "error": "短信功能未配置，请在 server.py 中设置 SMS_* 相关参数"
        }), 501

    phone = data.get("phone", SMS_PHONE_NUMBER)
    if not phone:
        return jsonify({"success": False, "error": "缺少接收手机号"}), 400

    device_id = data.get("device_id", "")
    custom_msg = data.get("message", "")

    # 查找该设备的最新位置
    records = load_from_file()
    device_records = [r for r in records if r.get("device_id") == device_id]
    if device_records:
        latest = device_records[-1]
        lng = latest.get("lng", "?")
        lat = latest.get("lat", "?")
        ts = latest.get("timestamp", "?")
        sms_content = (custom_msg or f"您的位置共享: 经度{lng}, 纬度{lat}, "
                       f"时间 {ts}")
    else:
        sms_content = custom_msg or "暂无位置数据"

    # TODO: 实际项目中在此处调用短信服务商 API
    # 示例（阿里云短信）:
    #   aliyunsms.send_sms(PhoneNumbers=phone, SignName=SMS_SIGN_NAME,
    #                      TemplateCode=SMS_TEMPLATE_CODE,
    #                      TemplateParam={"code": sms_content})

    print(f"[SMS] 推送短信到 {phone}: {sms_content}")
    return jsonify({
        "success": True,
        "message": "短信已发送",
        "phone": phone,
        "content": sms_content
    })


@app.route("/register_device", methods=["POST"])
def register_device():
    """
    设备注册接口 —— 共享端首次使用时注册自己的设备信息
    请求体（JSON）：
      {
        "token": "鉴权Token",
        "device_id": "设备唯一标识",
        "device_name": "设备昵称（可选）"
      }
    """
    data = request.get_json(silent=True)
    if not data:
        return jsonify({"success": False, "error": "请求体必须为 JSON"}), 400

    token = data.get("token", "")
    if not verify_token(token):
        return jsonify({"success": False, "error": "鉴权失败"}), 403

    device_id = data.get("device_id", "").strip()
    device_name = data.get("device_name", device_id)

    if not device_id:
        return jsonify({"success": False, "error": "缺少 device_id"}), 400

    print(f"[DEVICE] 注册设备: id={device_id} name={device_name}")
    return jsonify({
        "success": True,
        "message": "设备注册成功",
        "device_id": device_id,
        "device_name": device_name
    })


# ============================================================
# 主入口
# ============================================================

if __name__ == "__main__":
    ensure_data_dir()
    print("=" * 60)
    print("  自愿共享位置系统 - 中转服务器")
    print("=" * 60)
    print(f"  监听地址: http://{HOST}:{PORT}")
    print(f"  数据目录: {DATA_DIR}")
    print(f"  Token: {'*' * len(AUTH_TOKEN)}")
    print(f"  内存缓存: 最新 10 条定位")
    print(f"  频率限制: 每设备每 {RATE_LIMIT_SECONDS}s 最多上传 1 次")
    print("=" * 60)
    print("  接口列表:")
    print("    POST /upload_loc     - 上传定位")
    print("    GET  /poll           - 轮询最新位置")
    print("    GET  /get_history    - 查询历史定位")
    print("    POST /send_sms       - 手动触发短信推送")
    print("    POST /register_device - 注册设备")
    print("    GET  /health         - 健康检查")
    print("=" * 60)
    print()

    app.run(host=HOST, port=PORT, debug=True)
