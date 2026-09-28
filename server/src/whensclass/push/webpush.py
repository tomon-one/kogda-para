"""Web Push: шифрование сообщения (RFC 8291, aes128gcm) и подпись VAPID (RFC 8292).

Своё, а не pywebpush: тот тянет requests и ещё три пакета ради сотни строк,
а шифрование здесь проверено контрольным примером из самого RFC 8291
(tests/test_push.py). Отправка — httpx, как у остального сервиса.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import struct
import time
import urllib.parse

import httpx
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

# Размер записи: сообщение уходит одной записью, ему хватает с запасом.
RECORD_SIZE = 4096


def b64decode(text: str) -> bytes:
    """base64url без выравнивания, как в подписке браузера."""
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def b64encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _hkdf(salt: bytes, ikm: bytes, info: bytes, length: int) -> bytes:
    """HKDF-SHA-256 на один блок: больше 32 байт тут не нужно никогда."""
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    return hmac.new(prk, info + b"\x01", hashlib.sha256).digest()[:length]


def _public_bytes(key: ec.EllipticCurvePublicKey) -> bytes:
    return key.public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)


def encrypt(
    plaintext: bytes,
    p256dh: bytes,
    auth: bytes,
    *,
    server_key: ec.EllipticCurvePrivateKey | None = None,
    salt: bytes | None = None,
) -> bytes:
    """Тело запроса к службе рассылки: заголовок записи и шифротекст.

    `server_key` и `salt` — только для контрольного примера: в работе они
    каждый раз новые, иначе одинаковые сообщения шифровались бы одинаково.
    """
    server_key = server_key or ec.generate_private_key(ec.SECP256R1())
    salt = salt or os.urandom(16)
    receiver = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), p256dh)
    shared = server_key.exchange(ec.ECDH(), receiver)
    server_public = _public_bytes(server_key.public_key())

    ikm = _hkdf(auth, shared, b"WebPush: info\x00" + p256dh + server_public, 32)
    cek = _hkdf(salt, ikm, b"Content-Encoding: aes128gcm\x00", 16)
    nonce = _hkdf(salt, ikm, b"Content-Encoding: nonce\x00", 12)
    # Одна запись, она же последняя: разделитель 0x02 и без выравнивания.
    ciphertext = AESGCM(cek).encrypt(nonce, plaintext + b"\x02", None)
    header = salt + struct.pack("!IB", RECORD_SIZE, len(server_public)) + server_public
    return header + ciphertext


class Vapid:
    """Ключ сервера для подписи VAPID: им служба рассылки узнаёт отправителя.

    Хранится в /etc/whensclass/env как 32 байта закрытого ключа в base64url;
    открытый выводится из него и уходит браузеру при подписке.
    """

    def __init__(self, private_b64: str, subject: str):
        value = int.from_bytes(b64decode(private_b64.strip()), "big")
        self._key = ec.derive_private_key(value, ec.SECP256R1())
        self.public = b64encode(_public_bytes(self._key.public_key()))
        self.subject = subject

    def header(self, endpoint: str, now: float | None = None) -> str:
        parts = urllib.parse.urlsplit(endpoint)
        claims = {
            "aud": f"{parts.scheme}://{parts.netloc}",
            # Час, а не сутки: Apple принимает не дольше часа.
            "exp": int((now or time.time()) + 3600),
            "sub": self.subject,
        }
        head = b64encode(json.dumps({"typ": "JWT", "alg": "ES256"}, separators=(",", ":")).encode())
        body = b64encode(json.dumps(claims, separators=(",", ":")).encode())
        signing = f"{head}.{body}".encode("ascii")
        r, s = decode_dss_signature(self._key.sign(signing, ec.ECDSA(hashes.SHA256())))
        signature = b64encode(r.to_bytes(32, "big") + s.to_bytes(32, "big"))
        return f"vapid t={head}.{body}.{signature}, k={self.public}"


# Ответы службы рассылки, после которых подписка мертва: браузер отписался
# или его стёрли. Такую убираем, иначе слали бы в пустоту каждый раз.
GONE = (404, 410)


def send(
    client: httpx.Client,
    vapid: Vapid,
    endpoint: str,
    p256dh: str,
    auth: str,
    message: dict,
    ttl: int,
    urgency: str = "normal",
) -> int:
    """Отправить одно сообщение; вернуть код ответа службы рассылки.

    `urgency` high — для напоминаний: с ним служба рассылки и телефон не
    откладывают доставку до пробуждения, normal — для остального.
    """
    body = encrypt(
        json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
        b64decode(p256dh),
        b64decode(auth),
    )
    response = client.post(
        endpoint,
        content=body,
        headers={
            "Authorization": vapid.header(endpoint),
            "Content-Encoding": "aes128gcm",
            "Content-Type": "application/octet-stream",
            "TTL": str(ttl),
            "Urgency": urgency,
        },
    )
    return response.status_code
