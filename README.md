# EchoSight

盲人识物助手：语音说出要找的物品，摄像头识别并语音播报距离和方位。
运行：`py blind_assistant.py`，启动时输入解密密钥。

## 目录说明

| 路径 | 作用 |
|---|---|
| `blind_assistant.py` | 程序入口 |
| `assistant/` | 主程序模块 |
| `assistant/config.py` | 配置参数、路径，启动时解密 API Key |
| `assistant/crypto_util.py` | 加解密工具 |
| `assistant/api_key.enc` | 加密后的 API Key |
| `assistant/labels.py` | 80 类物品的中文名、别名、真实高度 |
| `assistant/audio_api.py` | 云端 TTS / ASR 接口 |
| `assistant/speaker.py` | 异步语音播报 |
| `assistant/voice_command.py` | 语音口令监听（"找到了"、"找XX"） |
| `assistant/detector.py` | YOLO 后台推理线程 |
| `assistant/geometry.py` | 距离与方位计算 |
| `assistant/display.py` | 画面中文标注绘制 |
| `assistant/desktop.py` | 桌面版主流程 |
| `scripts/` | 测试和基准脚本（bench、接口测试、加密 Key） |
| `models/` | YOLO 权重文件（需自行下载，不入仓库） |
| `assets/` | 示例图片 |
| `docs/` | 语音接口文档 |
