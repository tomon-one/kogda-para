"""Web Push: шифрование по RFC 8291 и подпись VAPID (RFC 8292)."""

import json

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives import hmac as chmac
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from whensclass.push import webpush
from whensclass.push.webpush import b64decode, b64encode


def _private(b64: str) -> ec.EllipticCurvePrivateKey:
    return ec.derive_private_key(int.from_bytes(b64decode(b64), "big"), ec.SECP256R1())


# --- RFC 8291, приложение A ----------------------------------------------------


RFC_PLAINTEXT = b"When I grow up, I want to be a watermelon"


RFC_AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"


RFC_UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"


RFC_UA_PUBLIC = (
    "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
)


RFC_SALT = "DGv6ra1nlYgDCS1FRnbzlw"


RFC_AUTH = "BTBZMqHH6r4Tts7J_aSIgg"


RFC_BODY = (
    "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmY"
    "WAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSg"
    "Sxsj_Qulcy4a-fN"
)


def test_encryption_matches_rfc_8291_example():
    body = webpush.encrypt(
        RFC_PLAINTEXT,
        b64decode(RFC_UA_PUBLIC),
        b64decode(RFC_AUTH),
        server_key=_private(RFC_AS_PRIVATE),
        salt=b64decode(RFC_SALT),
    )
    assert b64encode(body) == RFC_BODY


def _decrypt(body: bytes, ua_private: ec.EllipticCurvePrivateKey, auth: bytes) -> bytes:
    """Расшифровка стороной браузера — по тексту RFC, независимо от encrypt()."""
    salt, rs, idlen = body[:16], int.from_bytes(body[16:20], "big"), body[20]
    server_public = body[21:21 + idlen]
    ciphertext = body[21 + idlen:]
    assert rs == 4096 and idlen == 65
    shared = ua_private.exchange(
        ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), server_public)
    )
    ua_public = ua_private.public_key().public_bytes(
        webpush.Encoding.X962, webpush.PublicFormat.UncompressedPoint
    )

    def hkdf(salt: bytes, ikm: bytes, info: bytes, length: int) -> bytes:
        h = chmac.HMAC(salt, hashes.SHA256())
        h.update(ikm)
        prk = h.finalize()
        h = chmac.HMAC(prk, hashes.SHA256())
        h.update(info + b"\x01")
        return h.finalize()[:length]

    ikm = hkdf(auth, shared, b"WebPush: info\x00" + ua_public + server_public, 32)
    cek = hkdf(salt, ikm, b"Content-Encoding: aes128gcm\x00", 16)
    nonce = hkdf(salt, ikm, b"Content-Encoding: nonce\x00", 12)
    record = AESGCM(cek).decrypt(nonce, ciphertext, None)
    assert record.endswith(b"\x02")
    return record[:-1]


def test_encryption_round_trip_with_fresh_keys():
    ua = ec.generate_private_key(ec.SECP256R1())
    ua_public = ua.public_key().public_bytes(
        webpush.Encoding.X962, webpush.PublicFormat.UncompressedPoint
    )
    auth = b"0123456789abcdef"
    message = json.dumps({"t": "changes", "lines": [["2026-09-29", "вт, 29 сентября: отменили"]]},
                         ensure_ascii=False).encode()
    first = webpush.encrypt(message, ua_public, auth)
    second = webpush.encrypt(message, ua_public, auth)
    assert _decrypt(first, ua, auth) == message
    # Соль и ключ сервера каждый раз новые: одно и то же сообщение не
    # шифруется одинаково.
    assert first[:16] != second[:16] and first != second


def test_vapid_header_is_a_valid_es256_jwt():
    key = ec.generate_private_key(ec.SECP256R1())
    private = b64encode(key.private_numbers().private_value.to_bytes(32, "big"))
    vapid = webpush.Vapid(private, "https://kogda-para-nsk.ru")
    header = vapid.header("https://web.push.apple.com/QGuQyavXutnMtjIJaD4R/abc", now=1_800_000_000)
    assert header.startswith("vapid t=")
    token, k = header[len("vapid t="):].split(", k=")
    assert k == vapid.public
    head, body, signature = token.split(".")
    assert json.loads(b64decode(head)) == {"typ": "JWT", "alg": "ES256"}
    claims = json.loads(b64decode(body))
    # 12 часов: Apple — не дальше суток и не обновлять чаще раза в час.
    assert claims == {"aud": "https://web.push.apple.com", "exp": 1_800_043_200,
                      "sub": "https://kogda-para-nsk.ru"}
    raw = b64decode(signature)
    der = encode_dss_signature(int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big"))
    ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), b64decode(k)).verify(
        der, f"{head}.{body}".encode(), ec.ECDSA(hashes.SHA256())
    )


def test_vapid_token_is_reused_until_an_hour_is_left():
    key = ec.generate_private_key(ec.SECP256R1())
    vapid = webpush.Vapid(b64encode(key.private_numbers().private_value.to_bytes(32, "big")),
                          "https://kogda-para-nsk.ru")
    apple = "https://web.push.apple.com/a"
    first = vapid.header(apple, now=1_800_000_000)
    assert vapid.header(apple + "b", now=1_800_000_000 + 10 * 3600) == first
    # Другая служба — свой aud и свой токен.
    assert vapid.header("https://fcm.googleapis.com/fcm/send/x", now=1_800_000_000) != first
    # До конца меньше часа — новый.
    assert vapid.header(apple, now=1_800_000_000 + 11 * 3600 + 1) != first
