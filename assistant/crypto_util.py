# -*- coding: utf-8 -*-
"""基于密码的对称加解密（仅用标准库）。

文件格式：salt(16 字节) || 密文
密钥流由 PBKDF2-HMAC-SHA256（10 万次）分块派生，与明文异或。
用途是避免 API Key 以明文进入仓库，并非高强度机密保护。
"""
import hashlib
import os

SALT_LEN = 16
ITERATIONS = 100_000
KEY_BLOCK = 32


def _keystream(password: str, salt: bytes, length: int):
    needed = (length + KEY_BLOCK - 1) // KEY_BLOCK
    out = b""
    for i in range(needed):
        out += hashlib.pbkdf2_hmac(
            "sha256", password.encode("utf-8"),
            salt + i.to_bytes(4, "big"), ITERATIONS, KEY_BLOCK)
    return out[:length]


def encrypt(plaintext: bytes, password: str) -> bytes:
    salt = os.urandom(SALT_LEN)
    stream = _keystream(password, salt, len(plaintext))
    return salt + bytes(a ^ b for a, b in zip(plaintext, stream))


def decrypt(blob: bytes, password: str) -> bytes:
    salt, ciphertext = blob[:SALT_LEN], blob[SALT_LEN:]
    stream = _keystream(password, salt, len(ciphertext))
    return bytes(a ^ b for a, b in zip(ciphertext, stream))
