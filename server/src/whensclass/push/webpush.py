"""Web Push: шифрование сообщения (RFC 8291, aes128gcm) и подпись VAPID (RFC 8292).

Своё, а не pywebpush: тот тянет requests и ещё три пакета ради сотни строк,
а шифрование здесь проверено контрольным примером из самого RFC 8291
(tests/test_webpush.py). Отправка — httpx, как у остального сервиса.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import struct
import threading
import time
import urllib.parse
from typing import NamedTuple

import httpx
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

# Размер записи: сообщение уходит одной записью, ему хватает с запасом.
RECORD_SIZE = 4096
# Больше открытого текста в одну запись не входит (RFC 8291: 4096 минус
# заголовок, метка AES-GCM и разделитель; Apple — 3993 байта).
MAX_PLAINTEXT = 3993


class TooLarge(Exception):
    """Сообщение не влезает в запись — наша ошибка, а не подписки."""


def valid_key(raw: bytes) -> bool:
    """p256dh — точка кривой P-256, а не 65 любых байт с 0x04 впереди: такую
    подписку служба принимала и не снимала никогда."""
    try:
        ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), raw)
    except ValueError:
        return False
    return True


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

    # Токен живёт 12 часов и переиспользуется, новый — когда до конца меньше
    # часа. Apple: exp не дальше суток и «не обновлять JWT чаще раза в час»;
    # RFC 8292 советует переиспользовать. Было — новый токен на
    # каждое сообщение со сроком в час.
    LIFETIME = 12 * 3600
    RENEW_BEFORE = 3600

    def __init__(self, private_b64: str, subject: str):
        value = int.from_bytes(b64decode(private_b64.strip()), "big")
        self._key = ec.derive_private_key(value, ec.SECP256R1())
        self.public = b64encode(_public_bytes(self._key.public_key()))
        self.subject = subject
        self._tokens: dict[str, tuple[str, int]] = {}
        self._lock = threading.Lock()

    def header(self, endpoint: str, now: float | None = None) -> str:
        parts = urllib.parse.urlsplit(endpoint)
        # Origin службы рассылки: схема и хост, порт — только нестандартный.
        aud = f"{parts.scheme}://{parts.netloc}"
        moment = int(now if now is not None else time.time())
        with self._lock:
            cached = self._tokens.get(aud)
            if cached is None or cached[1] - moment < self.RENEW_BEFORE:
                cached = (self._sign(aud, moment + self.LIFETIME), moment + self.LIFETIME)
                self._tokens[aud] = cached
        return f"vapid t={cached[0]}, k={self.public}"

    def _sign(self, aud: str, exp: int) -> str:
        claims = {"aud": aud, "exp": exp, "sub": self.subject}
        head = b64encode(json.dumps({"typ": "JWT", "alg": "ES256"}, separators=(",", ":")).encode())
        body = b64encode(json.dumps(claims, separators=(",", ":")).encode())
        signing = f"{head}.{body}".encode("ascii")
        r, s = decode_dss_signature(self._key.sign(signing, ec.ECDSA(hashes.SHA256())))
        signature = b64encode(r.to_bytes(32, "big") + s.to_bytes(32, "big"))
        return f"{head}.{body}.{signature}"


# Ответы службы рассылки, после которых подписка мертва: браузер отписался
# или его стёрли. Такую убираем, иначе слали бы в пустоту каждый раз.
GONE = (404, 410)
# Стоит повторить: служба перегружена или на миг недоступна.
RETRY = (429, 500, 502, 503, 504)


class Result(NamedTuple):
    status: int
    # Причина из тела ответа — у Apple JSON с «reason»: BadJwtToken,
    # VapidPkHashMismatch, TooManyRequests…
    reason: str | None = None
    retry_after: int | None = None


def send(
    client: httpx.Client,
    vapid: Vapid,
    endpoint: str,
    p256dh: str,
    auth: str,
    message: dict,
    ttl: int,
    urgency: str = "normal",
) -> Result:
    """Отправить одно сообщение; вернуть ответ службы рассылки.

    `urgency` high — для напоминаний: с ним служба рассылки и телефон не
    откладывают доставку до пробуждения, normal — для остального.
    """
    plaintext = json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(plaintext) > MAX_PLAINTEXT:
        # Служба рассылки ответила бы 413, и напоминание пропало бы молча.
        raise TooLarge(f"{len(plaintext)} байт")
    body = encrypt(plaintext, b64decode(p256dh), b64decode(auth))
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
    reason = None
    if response.status_code >= 300:
        try:
            answer = response.json()
        except ValueError:
            answer = None
        if isinstance(answer, dict):
            reason = str(answer.get("reason") or answer.get("message") or "")[:80] or None
        else:
            # Не объект JSON (текст FCM, массив) — первые знаки как есть.
            reason = response.text[:80] or None
    retry = response.headers.get("Retry-After", "")
    return Result(response.status_code, reason, int(retry) if retry.isdigit() else None)
