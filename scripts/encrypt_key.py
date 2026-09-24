# -*- coding: utf-8 -*-
"""把 SenseAudio API Key 加密保存为 assistant/api_key.enc
运行后依次隐藏输入：API Key 明文、加密密码。请勿把密码提交到仓库。
"""
import getpass
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from assistant.crypto_util import encrypt

out = Path(__file__).resolve().parent.parent / "assistant" / "api_key.enc"

api_key = getpass.getpass("请输入 API Key 明文: ").strip()
password = getpass.getpass("请输入加密密码: ")
if not api_key or not password:
    raise SystemExit("Key 和密码都不能为空")

out.write_bytes(encrypt(api_key.encode("utf-8"), password))
print(f"已写出加密文件: {out}")
