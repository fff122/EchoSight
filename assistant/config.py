# -*- coding: utf-8 -*-
"""全局配置：SenseAudio 接口、检测参数、录音与路径。

API Key 优先读环境变量 SENSEAUDIO_API_KEY，没有时用内置的备用 Key。
"""
import os
from pathlib import Path

# ---------------- 路径 ----------------
ROOT = Path(__file__).resolve().parent.parent
MODELS_DIR = ROOT / "models"
ASSETS_DIR = ROOT / "assets"
TMP_WAV = ROOT / "_tts_tmp.wav"

# ---------------- SenseAudio 云端接口 ----------------
BASE = "https://api.senseaudio.cn"


ENC_KEY_FILE = Path(__file__).parent / "api_key.enc"


def _decrypt_key():
    """读加密文件，提示输入密码解密；最多试 3 次。"""
    import getpass
    from .crypto_util import decrypt

    blob = ENC_KEY_FILE.read_bytes()
    for attempt in range(3):
        password = getpass.getpass("请输入解密密钥: ").strip()
        try:
            key = decrypt(blob, password).decode("utf-8").strip()
        except UnicodeDecodeError:
            key = ""
        if key.startswith("sk-"):
            return key
        print("密钥错误，请重试。" if attempt < 2 else "")
    raise SystemExit("解密失败，程序退出。")


def _load_api_key():
    """Key 来源优先级：
    1. 环境变量 SENSEAUDIO_API_KEY
    2. 根目录 .api_key 明文文件（本地开发用，已 gitignore）
    3. 加密文件 assistant/api_key.enc（运行时输入密码解密）
    """
    key = os.environ.get("SENSEAUDIO_API_KEY")
    if key:
        return key.strip()
    key_file = ROOT / ".api_key"
    if key_file.exists():
        return key_file.read_text(encoding="utf-8").strip()
    if ENC_KEY_FILE.exists():
        return _decrypt_key()
    return ""


API_KEY = _load_api_key()
HEADERS = {"Authorization": f"Bearer {API_KEY}"}
TTS_MODEL = "sensenova-tts-2.0"
ASR_MODEL = "senseaudio-asr-1.5-260319"
VOICE_ID = "female_0033_b"

# ---------------- 目标检测 ----------------
MODEL_PATH = MODELS_DIR / "yolo26m.pt"   # m：精度/速度平衡
IMGSZ = 416                   # 推理分辨率（越小越快）
CONF = 0.3
FX_FACTOR = 0.85
DEFAULT_HEIGHT = 0.3

# ---------------- 录音 / 播报节奏 ----------------
RECORD_SEC = 3
SAMPLE_RATE = 16000
LOSE_PROMPT_INTERVAL = 8      # 找不到目标时两次提示的最小间隔（秒）
FOUND_REPORT_INTERVAL = 6     # 找到目标时两次播报的最小间隔（秒）

# ---------------- 语音指令监听（本地能量检测 VAD） ----------------
VAD_CALIBRATE_SEC = 1.0       # 启动时采集环境噪声的时长
VAD_BLOCK_SEC = 0.03          # 每块音频长度（秒）
VAD_NOISE_FACTOR = 5          # 触发阈值 = 环境噪声 RMS × 倍数
VAD_MIN_RMS = 300             # 触发阈值下限（int16，0~32767）
VAD_START_BLOCKS = 2          # 连续多少块超阈值判定为开始说话
VAD_END_BLOCKS = 18           # 连续多少块低于阈值判定为说完（约0.5秒）
VAD_MIN_SEC = 0.3             # 语音段最短时长，短于此时长视为噪声
VAD_MAX_SEC = 6.0             # 语音段最长时长，到点强制截断识别
VAD_TAIL_SEC = 0.4            # 播报结束后再多屏蔽这么久，防回声尾音
