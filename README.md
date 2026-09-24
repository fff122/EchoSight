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
| `android/` | 手机 App 工程（Kotlin） |

## 手机 App（Android）

手机本地运行 YOLO 模型，无需电脑；语音识别/合成仍走 SenseAudio 云端。

**使用方式：**

1. **按住**屏幕下方大按钮说话（如"找杯子"、"换手机"），**松手**自动识别并切换目标
2. 摄像头持续扫描，语音播报：方位（正前方/左前方/右前方/左侧/右侧）、高低（头部以上/视线高度/腰部以下）、距离（米 + 步数）、行动指引（往哪转、往前走几步）
3. 说"找到了"进入安静待命；再次按住说话即可换新目标

**下载 APK：**

每次推送到 `main`，GitHub Actions 自动编译。到
[Actions 页面](https://github.com/fff122/EchoSight/actions)
点开最新一次构建，在底部 Artifacts 下载 `EchoSight-apk`，解压得到 `app-release.apk`，传到手机安装（需开启"允许安装未知来源应用"）。

> 注意：编译前需在仓库 Settings → Secrets and variables → Actions 添加
> Secret `SENSEAUDIO_API_KEY`（值为 `sk-` 开头的 Key），否则 APK 没有语音功能。
> 本地构建可在 `android/app/api_key.txt` 放入 Key（已 gitignore）。

**重新导出端侧模型（更换模型时）：**

```powershell
py -c "from ultralytics import YOLO; m=YOLO('models/yolo26n.pt'); m.export(format='onnx', imgsz=320, simplify=True)"
copy models\yolo26n.onnx android\app\src\main\assets\
```
