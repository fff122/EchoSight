# EchoSight

盲人识物助手：语音说出要找的物品，摄像头识别并语音播报距离和方位。

## 运行

```powershell
py blind_assistant.py        # 或双击 run.bat
```

API Key 读取顺序：环境变量 `SENSEAUDIO_API_KEY` → 根目录 `.api_key` 文件（写入 `sk-` 开头的 Key，已 gitignore）。

## 目录说明

| 路径 | 作用 |
|---|---|
| `blind_assistant.py` | 程序入口 |
| `assistant/` | 主程序模块 |
| `assistant/config.py` | 配置参数、路径，读取 API Key |
| `assistant/labels.py` | 80 类物品的中文名、别名、真实高度 |
| `assistant/audio_api.py` | 云端 TTS / ASR 接口 |
| `assistant/speaker.py` | 异步语音播报 |
| `assistant/voice_command.py` | 语音口令监听（"找到了"、"找XX"） |
| `assistant/detector.py` | YOLO 后台推理线程 |
| `assistant/geometry.py` | 距离与方位计算 |
| `assistant/display.py` | 画面中文标注绘制 |
| `assistant/desktop.py` | 桌面版主流程 |
| `scripts/` | 测试和基准脚本（bench、接口测试） |
| `models/` | YOLO 权重文件（需自行下载，不入仓库） |

## 下载权重

在项目根目录运行，ultralytics 会自动下载缺失的权重到 `models/`：

```powershell
py -c "from ultralytics import YOLO; [YOLO(f'models/{n}.pt') for n in ('yolo26n','yolo26s','yolo26m','yolo26x','yolov8n','yolov8s','yolov8x')]"
```
| `assets/` | 示例图片 |
| `docs/` | 语音接口文档 |
