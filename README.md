# 自愿共享位置系统 — 完整项目

> 本项目仅用于编程技术学习，仅部署在本人自有设备上。
> 所有定位功能必须用户主动开启、界面实时显示定位状态、无隐藏后台静默监控逻辑。

## 项目结构

```
软件追踪/
├── backend/                    # Flask 中转服务器
│   ├── server.py               # 主服务代码（含所有 API 接口）
│   ├── requirements.txt        # Python 依赖
│   └── data/                   # 定位数据存储目录（自动生成）
│
├── sender-app/                 # 位置共享端 APP（Android/Kotlin）
│   ├── app/
│   │   ├── build.gradle.kts    # 构建配置
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/.../
│   │       │   ├── MainActivity.kt              # 主界面
│   │       │   ├── LocationShareService.kt      # 前台定位服务
│   │       │   └── BootReceiver.kt              # 开机广播
│   │       └── res/                              # 资源文件
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── viewer-app/                 # 位置查看端 APP（Android/Kotlin）
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/.../
│   │       │   └── MainActivity.kt              # 查看端主界面
│   │       └── res/
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
└── docs/                       # 文档
    ├── 01_项目整体开发流程.md
    ├── 05_联调排错优化方案.md
    └── 06_部署操作手册.md
```

## 快速开始

### 1. 启动后端服务

```bash
cd backend
pip install -r requirements.txt
python server.py
```

服务默认监听 `http://0.0.0.0:5000`

### 2. 暴露到公网（可选）

```bash
ngrok http 5000
```

记下 ngrok 给出的 HTTPS 地址。

### 3. 编译安装 APP

在 Android Studio 中分别打开 `sender-app` 和 `viewer-app` 项目，
点击 Run (▶) 安装到真机或模拟器。

### 4. 使用流程

1. **共享端**：填写服务器地址 → 授予定位权限 → 点击"开始位置共享"
2. **查看端**：填写相同服务器地址 → 输入共享端设备 ID → 点击"立即刷新"
3. 查看端显示共享端的位置卡片，可一键复制坐标或跳转地图

## API 接口列表

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/upload_loc` | 上传定位数据 |
| GET | `/poll` | 轮询最新位置 |
| GET | `/get_history` | 查询历史定位 |
| POST | `/send_sms` | 手动触发短信推送 |
| POST | `/register_device` | 注册设备 |
| GET | `/health` | 健康检查 |

## 合规声明

- ✅ 所有定位功能需用户手动开启，界面实时显示状态
- ✅ 无隐藏后台静默监控逻辑
- ✅ 无绕过电池优化的设计
- ✅ 开机不自动开启定位
- ❌ 禁止未经他人许可安装、追踪、监控他人设备
- ❌ 禁止用于任何非法监控行为
