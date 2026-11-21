#!/usr/bin/env python3
"""
Simulador de compañero(s) de ruta para Grupos en Tiempo Real de EnduLink / motillOS.
Cero dependencias externas (100 % librería estándar de Python 3).

Permite probar los dos flujos completos sin necesitar un segundo móvil físico:
  1. El PC crea un grupo nuevo (genera código + QR escaneable en consola/PNG/Tkinter)
     y tu móvil se une escaneando el QR o introduciendo el código.
  2. Tu móvil crea un grupo y el PC se une pegando el código (ej. K7M-9P2)
     o el enlace (endulink://group/K7M9P2).

Ejemplos:
  python Tools/companero_falso_grupo.py
  python Tools/companero_falso_grupo.py --crear
  python Tools/companero_falso_grupo.py --crear --pilotos 3
  python Tools/companero_falso_grupo.py K7M-9P2
  python Tools/companero_falso_grupo.py endulink://group/K7M9P2 --nombre "Elena" --color magenta
  python Tools/companero_falso_grupo.py --self-test
"""

from __future__ import annotations

import argparse
import base64
import ctypes
from dataclasses import dataclass
import hashlib
import hmac
import json
import math
import os
import queue
import random
import select
import socket
import ssl
import struct
import sys
import threading
import time
import urllib.parse


# Reutilizar el generador QR puro de Tools/moto_falsa_wifi.py
_TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
if _TOOLS_DIR not in sys.path:
    sys.path.insert(0, _TOOLS_DIR)

from moto_falsa_wifi import encode_qr_matrix, render_qr_terminal, save_qr_png  # noqa: E402


# ==============================================================================
# Constantes de protocolo (idénticas a GroupModels / GroupCrypto / GroupTransport)
# ==============================================================================

ROOM_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
SCHEME_PREFIX = "endulink://group/"

DEFAULT_MQTT_BROKERS = [
    "wss://broker.emqx.io:8084/mqtt",
    "wss://broker.hivemq.com:8884/mqtt",
]

LAN_UDP_PORT = 19847
LAN_GPX_TCP_PORT = 19848
LAN_MAGIC = b"ELG1"

VALID_COLORS = ("cyan", "lime", "magenta", "yellow", "purple", "coral", "sky", "pink")
VALID_STATUSES = ("riding", "wait", "mech", "sos", "left")
STATUS_LABELS = {
    "riding": "En ruta",
    "wait": "Esperadme",
    "mech": "Avería",
    "sos": "SOS · Ayuda",
    "left": "Salió",
}

DEFAULT_BOT_ROSTER = [
    ("Carlos (Bot)", "lime", 38.0, 320.0, 0.0, 88),
    ("Elena (Bot)", "magenta", 32.0, 650.0, 1.8, 82),
    ("Marcos (Bot)", "yellow", 45.0, 1150.0, 3.4, 76),
    ("Sofía (Bot)", "purple", 28.0, 2200.0, 5.0, 91),
]

LONG_MAX = 9223372036854775807


def configure_console_utf8() -> None:
    """Evita UnicodeEncodeError en consolas Windows cp1252 y habilita ANSI VT100."""
    for stream_name in ("stdout", "stderr"):
        stream = getattr(sys, stream_name, None)
        if stream is not None and hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except Exception:
                pass
    if os.name == "nt":
        try:
            kernel32 = ctypes.windll.kernel32
            handle = kernel32.GetStdHandle(-11)  # STD_OUTPUT_HANDLE
            mode = ctypes.c_ulong()
            if kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
                kernel32.SetConsoleMode(handle, mode.value | 0x0004)
        except Exception:
            pass


# ==============================================================================
# Invitaciones y códigos de sala (GroupInvite)
# ==============================================================================

def _normalize_raw_token(token: str) -> str:
    return (
        token.strip()
        .replace("-", "")
        .replace(" ", "")
        .replace("_", "")
        .upper()
    )


def generate_room_code(length: int = 6) -> str:
    length = max(4, min(12, length))
    return "".join(random.choice(ROOM_ALPHABET) for _ in range(length))


def parse_room_code(raw: str) -> str | None:
    trimmed = raw.strip()
    if not trimmed:
        return None

    if "://" in trimmed:
        lower = trimmed.lower()
        if not lower.startswith(SCHEME_PREFIX):
            return None
        extracted = (
            trimmed[len(SCHEME_PREFIX) :]
            .split("?", 1)[0]
            .split("#", 1)[0]
            .strip("/")
        )
    else:
        if ":" in trimmed:
            return None
        extracted = trimmed

    clean = _normalize_raw_token(extracted)
    if not (4 <= len(clean) <= 12):
        return None
    if not all(("A" <= ch <= "Z") or ("0" <= ch <= "9") for ch in clean):
        return None
    return clean


def format_room_code(code: str) -> str:
    clean = _normalize_raw_token(code)
    if len(clean) == 6:
        return f"{clean[:3]}-{clean[3:]}"
    return clean


def build_invite_uri(code: str) -> str:
    clean = _normalize_raw_token(code)
    return f"{SCHEME_PREFIX}{clean}"


# ==============================================================================
# Criptografía E2EE compatible con GroupCrypto.kt
# ==============================================================================

def _norm_room(room_code: str) -> str:
    return parse_room_code(room_code) or room_code.strip().upper()


def derive_topic_hash(room_code: str) -> str:
    norm = _norm_room(room_code)
    digest = hashlib.sha256(f"endulink-group-topic-v1:{norm}".encode("utf-8")).digest()
    return digest[:16].hex()


def derive_room_tag8(room_code: str) -> bytes:
    norm = _norm_room(room_code)
    digest = hashlib.sha256(f"endulink-group-topic-v1:{norm}".encode("utf-8")).digest()
    return digest[:8]


def _rotl32(v: int, bits: int) -> int:
    v &= 0xFFFFFFFF
    return ((v << bits) | (v >> (32 - bits))) & 0xFFFFFFFF


def _quarter_round(x: list[int], a: int, b: int, c: int, d: int) -> None:
    x[a] = (x[a] + x[b]) & 0xFFFFFFFF
    x[d] = _rotl32(x[d] ^ x[a], 16)
    x[c] = (x[c] + x[d]) & 0xFFFFFFFF
    x[b] = _rotl32(x[b] ^ x[c], 12)
    x[a] = (x[a] + x[b]) & 0xFFFFFFFF
    x[d] = _rotl32(x[d] ^ x[a], 8)
    x[c] = (x[c] + x[d]) & 0xFFFFFFFF
    x[b] = _rotl32(x[b] ^ x[c], 7)


def chacha20_xor(key: bytes, nonce: bytes, counter: int, data: bytes) -> bytes:
    if len(key) != 32:
        raise ValueError("ChaCha20 key must be 32 bytes")
    if len(nonce) != 12:
        raise ValueError("ChaCha20 nonce must be 12 bytes")

    state = [
        0x61707865,
        0x3320646E,
        0x79622D32,
        0x6B206574,
        *struct.unpack("<8I", key),
        counter & 0xFFFFFFFF,
        *struct.unpack("<3I", nonce),
    ]

    out = bytearray(len(data))
    offset = 0
    block_counter = counter & 0xFFFFFFFF
    total = len(data)

    while offset < total:
        state[12] = block_counter
        block_counter = (block_counter + 1) & 0xFFFFFFFF
        w = state.copy()

        for _ in range(10):
            _quarter_round(w, 0, 4, 8, 12)
            _quarter_round(w, 1, 5, 9, 13)
            _quarter_round(w, 2, 6, 10, 14)
            _quarter_round(w, 3, 7, 11, 15)

            _quarter_round(w, 0, 5, 10, 15)
            _quarter_round(w, 1, 6, 11, 12)
            _quarter_round(w, 2, 7, 8, 13)
            _quarter_round(w, 3, 4, 9, 14)

        keystream = struct.pack("<16I", *(((w[i] + state[i]) & 0xFFFFFFFF) for i in range(16)))
        block_len = min(64, total - offset)
        for i in range(block_len):
            out[offset + i] = data[offset + i] ^ keystream[i]
        offset += block_len

    return bytes(out)


def encrypt_envelope(room_code: str, plaintext: bytes, nonce_override: bytes | None = None) -> bytes:
    norm = _norm_room(room_code)
    enc_key = hashlib.sha256(f"endulink-group-enc-v1:{norm}".encode("utf-8")).digest()
    mac_key = hashlib.sha256(f"endulink-group-mac-v1:{norm}".encode("utf-8")).digest()
    nonce = nonce_override if nonce_override is not None else os.urandom(12)
    if len(nonce) != 12:
        raise ValueError("Nonce must be 12 bytes")

    ciphertext = chacha20_xor(enc_key, nonce, 1, plaintext)
    body = b"\x01" + nonce + ciphertext
    tag = hmac.new(mac_key, body, hashlib.sha256).digest()[:16]
    return body + tag


def decrypt_envelope(room_code: str, envelope: bytes) -> bytes | None:
    if len(envelope) < 1 + 12 + 16:
        return None
    if envelope[0] != 1:
        return None

    norm = _norm_room(room_code)
    enc_key = hashlib.sha256(f"endulink-group-enc-v1:{norm}".encode("utf-8")).digest()
    mac_key = hashlib.sha256(f"endulink-group-mac-v1:{norm}".encode("utf-8")).digest()

    body = envelope[:-16]
    received_tag = envelope[-16:]
    expected_tag = hmac.new(mac_key, body, hashlib.sha256).digest()[:16]
    if not hmac.compare_digest(received_tag, expected_tag):
        return None

    nonce = envelope[1:13]
    ciphertext = envelope[13:-16]
    return chacha20_xor(enc_key, nonce, 1, ciphertext)


# ==============================================================================
# Códec binario MQTT 3.1.1 (espejo de MqttCodec.kt)
# ==============================================================================

def _write_mqtt_utf8(s: str) -> bytes:
    raw = s.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def _wrap_mqtt_packet(header_byte: int, body: bytes) -> bytes:
    out = bytearray([header_byte & 0xFF])
    rem = len(body)
    while True:
        digit = rem % 128
        rem //= 128
        if rem > 0:
            digit |= 0x80
        out.append(digit)
        if rem == 0:
            break
    out.extend(body)
    return bytes(out)


class MqttCodec:
    @staticmethod
    def encode_connect(client_id: str, keep_alive_seconds: int = 60) -> bytes:
        body = (
            _write_mqtt_utf8("MQTT")
            + struct.pack(">BBH", 0x04, 0x02, keep_alive_seconds)
            + _write_mqtt_utf8(client_id)
        )
        return _wrap_mqtt_packet(0x10, body)

    @staticmethod
    def encode_subscribe(packet_id: int, topic_filter: str) -> bytes:
        body = struct.pack(">H", packet_id & 0xFFFF) + _write_mqtt_utf8(topic_filter) + b"\x00"
        return _wrap_mqtt_packet(0x82, body)

    @staticmethod
    def encode_publish(topic: str, payload: bytes, retain: bool) -> bytes:
        body = _write_mqtt_utf8(topic) + payload
        header = 0x31 if retain else 0x30
        return _wrap_mqtt_packet(header, body)

    @staticmethod
    def encode_pingreq() -> bytes:
        return b"\xC0\x00"

    @staticmethod
    def encode_disconnect() -> bytes:
        return b"\xE0\x00"


@dataclass
class MqttConnAck:
    return_code: int


@dataclass
class MqttSubAck:
    packet_id: int


@dataclass
class MqttPublish:
    topic: str
    payload: bytes
    retain: bool


@dataclass
class MqttPingResp:
    pass


@dataclass
class MqttOther:
    packet_type: int


MqttPacket = MqttConnAck | MqttSubAck | MqttPublish | MqttPingResp | MqttOther


class MqttStreamDecoder:
    def __init__(self) -> None:
        self._buffer = bytearray()

    def reset(self) -> None:
        self._buffer.clear()

    def feed(self, chunk: bytes) -> list[MqttPacket]:
        if chunk:
            self._buffer.extend(chunk)

        packets: list[MqttPacket] = []
        cursor = 0
        buf = self._buffer
        buf_len = len(buf)

        while cursor < buf_len:
            if buf_len - cursor < 2:
                break

            header_byte = buf[cursor]
            multiplier = 1
            rem_len = 0
            len_bytes = 0
            need_more = False

            while True:
                idx = cursor + 1 + len_bytes
                if idx >= buf_len:
                    need_more = True
                    break
                encoded = buf[idx]
                rem_len += (encoded & 0x7F) * multiplier
                len_bytes += 1
                if (encoded & 0x80) == 0:
                    break
                multiplier *= 128
                if len_bytes >= 4:
                    break

            if need_more:
                break

            total_len = 1 + len_bytes + rem_len
            if buf_len - cursor < total_len:
                break

            body_start = cursor + 1 + len_bytes
            body_end = body_start + rem_len
            pkt = self._parse_packet(header_byte, buf, body_start, body_end)
            if pkt is not None:
                packets.append(pkt)
            cursor += total_len

        if cursor > 0:
            del buf[:cursor]

        return packets

    @staticmethod
    def _parse_packet(header_byte: int, data: bytearray, start: int, end: int) -> MqttPacket | None:
        packet_type = (header_byte >> 4) & 0x0F
        if packet_type == 2:  # CONNACK
            if end - start < 2:
                return None
            return MqttConnAck(return_code=data[start + 1])
        if packet_type == 3:  # PUBLISH
            if end - start < 2:
                return None
            topic_len = (data[start] << 8) | data[start + 1]
            topic_start = start + 2
            topic_end = topic_start + topic_len
            if topic_end > end:
                return None
            topic = bytes(data[topic_start:topic_end]).decode("utf-8", errors="replace")
            qos = (header_byte >> 1) & 0x03
            payload_start = topic_end + 2 if qos > 0 else topic_end
            if payload_start > end:
                return None
            payload = bytes(data[payload_start:end])
            retain = (header_byte & 0x01) != 0
            return MqttPublish(topic=topic, payload=payload, retain=retain)
        if packet_type == 9:  # SUBACK
            if end - start < 2:
                return None
            pid = (data[start] << 8) | data[start + 1]
            return MqttSubAck(packet_id=pid)
        if packet_type == 13:  # PINGRESP
            return MqttPingResp()
        return MqttOther(packet_type=packet_type)


# ==============================================================================
# Cliente WebSocket RFC 6455 puro en librería estándar (ws:// y wss://)
# ==============================================================================

class SimpleWebSocketClient:
    def __init__(self, url: str, subprotocol: str = "mqtt", timeout: float = 10.0) -> None:
        self.url = url
        self.subprotocol = subprotocol
        self.timeout = timeout
        self._sock: socket.socket | None = None
        self._rx_buf = bytearray()
        self._send_lock = threading.Lock()
        self._closed = False

    def connect(self) -> None:
        parsed = urllib.parse.urlparse(self.url)
        scheme = parsed.scheme.lower()
        if scheme not in ("ws", "wss"):
            raise ValueError(f"Esquema WebSocket no soportado: {self.url}")

        host = parsed.hostname
        if not host:
            raise ValueError(f"Host inválido en URL: {self.url}")
        port = parsed.port or (443 if scheme == "wss" else 80)
        path = parsed.path or "/"
        if parsed.query:
            path = f"{path}?{parsed.query}"

        raw_sock = socket.create_connection((host, port), timeout=self.timeout)
        raw_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        if scheme == "wss":
            ctx = ssl.create_default_context()
            sock: socket.socket = ctx.wrap_socket(raw_sock, server_hostname=host)
        else:
            sock = raw_sock

        sock.settimeout(self.timeout)
        self._sock = sock

        ws_key = base64.b64encode(os.urandom(16)).decode("ascii")
        host_header = host if (scheme == "wss" and port == 443) or (scheme == "ws" and port == 80) else f"{host}:{port}"
        req = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host_header}\r\n"
            f"Upgrade: websocket\r\n"
            f"Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {ws_key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n"
            f"Sec-WebSocket-Protocol: {self.subprotocol}\r\n"
            f"User-Agent: EnduLink-Sim/1.0\r\n"
            f"\r\n"
        ).encode("ascii")
        sock.sendall(req)

        # Leer respuesta HTTP preservando bytes sobrantes tras \r\n\r\n
        raw_resp = bytearray()
        while True:
            sep_idx = raw_resp.find(b"\r\n\r\n")
            if sep_idx != -1:
                header_bytes = bytes(raw_resp[:sep_idx])
                leftover = bytes(raw_resp[sep_idx + 4 :])
                if leftover:
                    self._rx_buf.extend(leftover)
                break
            chunk = sock.recv(4096)
            if not chunk:
                raise EOFError("Conexión cerrada durante handshake WebSocket")
            raw_resp.extend(chunk)
            if len(raw_resp) > 32768:
                raise ValueError("Cabecera HTTP Upgrade demasiado grande")

        status_line = header_bytes.split(b"\r\n", 1)[0].decode("latin-1", errors="replace")
        if " 101 " not in status_line:
            raise ConnectionError(f"Handshake WebSocket rechazado: {status_line}")

        sock.settimeout(1.0)

    def _read_exact(self, n: int) -> bytes:
        while len(self._rx_buf) < n:
            if self._sock is None or self._closed:
                raise EOFError("WebSocket cerrado")
            try:
                chunk = self._sock.recv(max(4096, n - len(self._rx_buf)))
            except socket.timeout:
                if self._closed:
                    raise EOFError("WebSocket cerrado")
                continue
            if not chunk:
                raise EOFError("EOF en WebSocket")
            self._rx_buf.extend(chunk)
        out = bytes(self._rx_buf[:n])
        del self._rx_buf[:n]
        return out

    def send_binary(self, payload: bytes) -> None:
        self.send_frame(0x02, payload)

    def send_frame(self, opcode: int, payload: bytes) -> None:
        if self._sock is None or self._closed:
            raise EOFError("WebSocket cerrado")
        mask_key = os.urandom(4)
        length = len(payload)

        header = bytearray()
        header.append(0x80 | (opcode & 0x0F))
        if length <= 125:
            header.append(0x80 | length)
        elif length <= 65535:
            header.append(0x80 | 126)
            header.extend(struct.pack(">H", length))
        else:
            header.append(0x80 | 127)
            header.extend(struct.pack(">Q", length))
        header.extend(mask_key)

        masked = bytearray(length)
        for i, b in enumerate(payload):
            masked[i] = b ^ mask_key[i & 3]

        frame = bytes(header) + bytes(masked)
        with self._send_lock:
            if self._sock is None or self._closed:
                raise EOFError("WebSocket cerrado")
            self._sock.sendall(frame)

    def recv_message(self) -> bytes | None:
        fragments: list[bytes] = []
        while not self._closed:
            try:
                b1, b2 = self._read_exact(2)
            except EOFError:
                return None

            fin = (b1 & 0x80) != 0
            opcode = b1 & 0x0F
            masked = (b2 & 0x80) != 0
            length = b2 & 0x7F

            if length == 126:
                (length,) = struct.unpack(">H", self._read_exact(2))
            elif length == 127:
                (length,) = struct.unpack(">Q", self._read_exact(8))

            mask_key = self._read_exact(4) if masked else b""
            payload = self._read_exact(length) if length > 0 else b""
            if masked and payload:
                payload = bytes(b ^ mask_key[i & 3] for i, b in enumerate(payload))

            if opcode == 0x08:  # Close
                return None
            if opcode == 0x09:  # Ping -> Pong
                try:
                    self.send_frame(0x0A, payload)
                except Exception:
                    return None
                continue
            if opcode == 0x0A:  # Pong
                continue

            fragments.append(payload)
            if fin:
                return b"".join(fragments)
        return None

    def close(self) -> None:
        self._closed = True
        sock = self._sock
        self._sock = None
        if sock is not None:
            try:
                sock.close()
            except Exception:
                pass


# ==============================================================================
# Transportes del grupo (MQTT WSS + LAN UDP/TCP)
# ==============================================================================

class MqttWsTransport:
    def __init__(
        self,
        room_code: str,
        own_bot_ids: set[str],
        brokers: list[str],
        on_connection_changed,
        on_connected_ready,
        on_position_received,
        on_member_left,
        on_track_received,
    ) -> None:
        self.room_code = room_code
        self.own_bot_ids = own_bot_ids
        self.brokers = brokers
        self.topic_prefix = f"endulink/g1/{derive_topic_hash(room_code)}"
        self.on_connection_changed = on_connection_changed
        self.on_connected_ready = on_connected_ready
        self.on_position_received = on_position_received
        self.on_member_left = on_member_left
        self.on_track_received = on_track_received

        self._stop_event = threading.Event()
        self._ws_lock = threading.Lock()
        self._ws: SimpleWebSocketClient | None = None
        self._connected = False
        self._out_q: queue.Queue[tuple[str, bytes, bool]] = queue.Queue(maxsize=128)
        self._thread: threading.Thread | None = None

    @property
    def connected(self) -> bool:
        return self._connected

    def start(self) -> None:
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run_loop, name="mqtt-ws-loop", daemon=True)
        self._thread.start()

    def publish_position(self, payload_dict: dict) -> None:
        member_id = payload_dict.get("id", "")
        if not member_id:
            return
        raw_json = json.dumps(payload_dict, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        encrypted = encrypt_envelope(self.room_code, raw_json)
        self._enqueue(f"{self.topic_prefix}/m/{member_id}", encrypted, True)

    def publish_shared_track(self, track_dict: dict) -> None:
        raw_json = json.dumps(track_dict, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        encrypted = encrypt_envelope(self.room_code, raw_json)
        self._enqueue(f"{self.topic_prefix}/gpx", encrypted, True)

    def _enqueue(self, topic: str, payload: bytes, retain: bool) -> None:
        if self._stop_event.is_set():
            return
        try:
            self._out_q.put_nowait((topic, payload, retain))
        except queue.Full:
            try:
                self._out_q.get_nowait()
            except queue.Empty:
                pass
            try:
                self._out_q.put_nowait((topic, payload, retain))
            except queue.Full:
                pass

    def leave_and_stop(self, bot_ids: list[str]) -> None:
        ws = self._ws
        if ws is not None and self._connected:
            for bid in bot_ids:
                leave_payload = {
                    "v": 1,
                    "id": bid,
                    "n": "",
                    "c": "cyan",
                    "la": 0.0,
                    "lo": 0.0,
                    "st": "left",
                    "sq": LONG_MAX,
                }
                raw = json.dumps(leave_payload, separators=(",", ":")).encode("utf-8")
                enc = encrypt_envelope(self.room_code, raw)
                try:
                    ws.send_binary(MqttCodec.encode_publish(f"{self.topic_prefix}/m/{bid}", enc, retain=False))
                    ws.send_binary(MqttCodec.encode_publish(f"{self.topic_prefix}/m/{bid}", b"", retain=True))
                except Exception:
                    pass
            try:
                ws.send_binary(MqttCodec.encode_disconnect())
            except Exception:
                pass
            time.sleep(0.2)
        self.stop()

    def stop(self) -> None:
        self._stop_event.set()
        self._set_connected(False, "")
        with self._ws_lock:
            if self._ws is not None:
                self._ws.close()
                self._ws = None

    def _set_connected(self, value: bool, broker_url: str) -> None:
        if self._connected != value:
            self._connected = value
            self.on_connection_changed(value, broker_url)

    def _run_loop(self) -> None:
        backoff = [2.0, 4.0, 8.0, 15.0, 30.0]
        attempt = 0

        while not self._stop_event.is_set():
            broker_url = self.brokers[attempt % len(self.brokers)]
            ws = SimpleWebSocketClient(broker_url, subprotocol="mqtt", timeout=10.0)
            was_connected = False
            session_stop = threading.Event()

            try:
                ws.connect()
                with self._ws_lock:
                    self._ws = ws

                client_id = f"el_sim_{random.randint(10000, 99999)}"
                ws.send_binary(MqttCodec.encode_connect(client_id, keep_alive_seconds=60))

                decoder = MqttStreamDecoder()
                connack_ok = False
                deadline = time.monotonic() + 10.0

                while time.monotonic() < deadline and not self._stop_event.is_set():
                    msg = ws.recv_message()
                    if msg is None:
                        break
                    for pkt in decoder.feed(msg):
                        if isinstance(pkt, MqttConnAck):
                            if pkt.return_code == 0:
                                connack_ok = True
                            else:
                                raise ConnectionError(f"MQTT CONNACK error code={pkt.return_code}")
                    if connack_ok:
                        break

                if not connack_ok:
                    raise TimeoutError("MQTT CONNACK timeout")

                ws.send_binary(MqttCodec.encode_subscribe(1, f"{self.topic_prefix}/#"))
                was_connected = True
                attempt = 0
                self._set_connected(True, broker_url)
                self.on_connected_ready()

                def _writer_loop() -> None:
                    while not self._stop_event.is_set() and not session_stop.is_set():
                        try:
                            topic, payload, retain = self._out_q.get(timeout=0.5)
                        except queue.Empty:
                            continue
                        try:
                            ws.send_binary(MqttCodec.encode_publish(topic, payload, retain))
                        except Exception:
                            session_stop.set()
                            break

                def _ping_loop() -> None:
                    while not session_stop.wait(35.0):
                        if self._stop_event.is_set():
                            break
                        try:
                            ws.send_binary(MqttCodec.encode_pingreq())
                        except Exception:
                            session_stop.set()
                            break

                threading.Thread(target=_writer_loop, name="mqtt-writer", daemon=True).start()
                threading.Thread(target=_ping_loop, name="mqtt-ping", daemon=True).start()

                while not self._stop_event.is_set() and not session_stop.is_set():
                    msg = ws.recv_message()
                    if msg is None:
                        break
                    for pkt in decoder.feed(msg):
                        if isinstance(pkt, MqttPublish):
                            self._handle_publish(pkt.topic, pkt.payload)
            except Exception as exc:
                if not self._stop_event.is_set():
                    print(f"[Online MQTT] Desconectado de {broker_url}: {exc}")
            finally:
                session_stop.set()
                self._set_connected(False, broker_url)
                with self._ws_lock:
                    if self._ws is ws:
                        self._ws = None
                ws.close()

            if self._stop_event.is_set():
                break
            wait_s = 2.0 if was_connected else backoff[min(attempt, len(backoff) - 1)]
            attempt += 1
            self._stop_event.wait(wait_s)

    def _handle_publish(self, topic: str, payload: bytes) -> None:
        if not topic.startswith(self.topic_prefix):
            return
        suffix = topic[len(self.topic_prefix) :]
        if suffix.startswith("/m/"):
            sender_id = suffix[3:]
            if sender_id in self.own_bot_ids:
                return
            if not payload:
                self.on_member_left(sender_id)
                return
            plain = decrypt_envelope(self.room_code, payload)
            if plain is None:
                return
            try:
                parsed = json.loads(plain.decode("utf-8"))
            except Exception:
                return
            self.on_position_received(parsed, "4G/5G")
        elif suffix == "/gpx" and payload:
            plain = decrypt_envelope(self.room_code, payload)
            if plain is None:
                return
            try:
                parsed = json.loads(plain.decode("utf-8"))
            except Exception:
                return
            self.on_track_received(parsed, "4G/5G")


def _discover_local_lan_ip() -> str | None:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        if ip and not ip.startswith("127."):
            return ip
    except Exception:
        pass
    finally:
        s.close()
    return None


class LanUdpTransport:
    def __init__(
        self,
        room_code: str,
        own_bot_ids: set[str],
        extra_unicast_ip: str | None,
        on_status_changed,
        on_position_received,
        on_track_received,
    ) -> None:
        self.room_code = room_code
        self.room_tag8 = derive_room_tag8(room_code)
        self.own_bot_ids = own_bot_ids
        self.extra_unicast_ip = extra_unicast_ip
        self.on_status_changed = on_status_changed
        self.on_position_received = on_position_received
        self.on_track_received = on_track_received

        self._stop_event = threading.Event()
        self._udp_sock: socket.socket | None = None
        self._gpx_srv: socket.socket | None = None
        self._active = False
        self._local_ip = _discover_local_lan_ip()
        self._hosted_track_id: str | None = None
        self._hosted_encrypted_track: bytes | None = None
        self._fetched_lock = threading.Lock()
        self._fetched_track_ids: set[str] = set()

    @property
    def active(self) -> bool:
        return self._active

    def start(self) -> None:
        self._stop_event.clear()
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        rx_enabled = False
        try:
            sock.bind(("", LAN_UDP_PORT))
            rx_enabled = True
        except OSError as exc:
            print(f"[Wi-Fi Local] Puerto UDP {LAN_UDP_PORT} ocupado ({exc}); usando modo solo envío LAN.")
            try:
                sock.bind(("", 0))
            except OSError:
                pass
        sock.settimeout(1.0)
        self._udp_sock = sock
        self._active = True
        self.on_status_changed(True, rx_enabled)

        if rx_enabled:
            threading.Thread(target=self._rx_loop, name="lan-udp-rx", daemon=True).start()

    def _broadcast_targets(self) -> list[str]:
        targets = ["255.255.255.255"]
        if self._local_ip:
            parts = self._local_ip.split(".")
            if len(parts) == 4:
                directed = f"{parts[0]}.{parts[1]}.{parts[2]}.255"
                if directed not in targets:
                    targets.append(directed)
        if self.extra_unicast_ip and self.extra_unicast_ip not in targets:
            targets.append(self.extra_unicast_ip)
        return targets

    def publish_position(self, payload_dict: dict) -> None:
        sock = self._udp_sock
        if sock is None or self._stop_event.is_set():
            return
        enriched = dict(payload_dict)
        if self._hosted_track_id is not None:
            enriched["gpxId"] = self._hosted_track_id
            enriched["gpxPort"] = LAN_GPX_TCP_PORT

        raw_json = json.dumps(enriched, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        encrypted = encrypt_envelope(self.room_code, raw_json)
        packet = LAN_MAGIC + self.room_tag8 + encrypted
        for target in self._broadcast_targets():
            try:
                sock.sendto(packet, (target, LAN_UDP_PORT))
            except OSError:
                pass

    def publish_shared_track(self, track_dict: dict) -> None:
        raw_json = json.dumps(track_dict, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        self._hosted_encrypted_track = encrypt_envelope(self.room_code, raw_json)
        self._hosted_track_id = track_dict.get("id")
        self._ensure_gpx_server_started()

    def leave_and_stop(self, bot_ids: list[str]) -> None:
        for bid in bot_ids:
            self.publish_position(
                {
                    "v": 1,
                    "id": bid,
                    "n": "",
                    "c": "cyan",
                    "la": 0.0,
                    "lo": 0.0,
                    "st": "left",
                    "sq": LONG_MAX,
                }
            )
        time.sleep(0.1)
        self.stop()

    def stop(self) -> None:
        self._stop_event.set()
        self._active = False
        self.on_status_changed(False, False)
        if self._udp_sock is not None:
            try:
                self._udp_sock.close()
            except Exception:
                pass
            self._udp_sock = None
        if self._gpx_srv is not None:
            try:
                self._gpx_srv.close()
            except Exception:
                pass
            self._gpx_srv = None

    def _rx_loop(self) -> None:
        sock = self._udp_sock
        if sock is None:
            return
        while not self._stop_event.is_set():
            try:
                data, addr = sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                break

            if len(data) < 12 + 29:
                continue
            if data[:4] != LAN_MAGIC or data[4:12] != self.room_tag8:
                continue

            plain = decrypt_envelope(self.room_code, data[12:])
            if plain is None:
                continue
            try:
                payload = json.loads(plain.decode("utf-8"))
            except Exception:
                continue

            sender_id = payload.get("id", "")
            if sender_id and sender_id not in self.own_bot_ids:
                self.on_position_received(payload, "Wi-Fi Local")

            for peer in payload.get("peers", []) or []:
                pid = peer.get("id", "")
                if pid and pid not in self.own_bot_ids:
                    self.on_position_received(peer, "Wi-Fi Local")

            adv_gpx_id = payload.get("gpxId")
            adv_gpx_port = payload.get("gpxPort")
            if adv_gpx_id and adv_gpx_port and adv_gpx_id != self._hosted_track_id:
                with self._fetched_lock:
                    is_new = adv_gpx_id not in self._fetched_track_ids
                    if is_new:
                        self._fetched_track_ids.add(adv_gpx_id)
                if is_new:
                    threading.Thread(
                        target=self._fetch_lan_gpx,
                        args=(addr[0], int(adv_gpx_port), adv_gpx_id),
                        daemon=True,
                    ).start()

    def _ensure_gpx_server_started(self) -> None:
        if self._gpx_srv is not None:
            return
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            srv.bind(("", LAN_GPX_TCP_PORT))
            srv.listen(8)
            srv.settimeout(1.0)
        except OSError as exc:
            print(f"[Wi-Fi Local GPX] No se pudo abrir TCP {LAN_GPX_TCP_PORT}: {exc}")
            srv.close()
            return
        self._gpx_srv = srv

        def _accept_loop() -> None:
            while not self._stop_event.is_set() and self._gpx_srv is srv:
                try:
                    client, _ = srv.accept()
                except socket.timeout:
                    continue
                except OSError:
                    break
                try:
                    client.settimeout(4.0)
                    blob = self._hosted_encrypted_track
                    if blob:
                        client.sendall(struct.pack("<i", len(blob)) + blob)
                except OSError:
                    pass
                finally:
                    try:
                        client.close()
                    except OSError:
                        pass

        threading.Thread(target=_accept_loop, name="lan-gpx-srv", daemon=True).start()

    def _fetch_lan_gpx(self, host_ip: str, port: int, track_id: str) -> None:
        sock = None
        try:
            sock = socket.create_connection((host_ip, port), timeout=4.0)
            sock.settimeout(4.0)
            len_bytes = self._read_exact(sock, 4)
            (length,) = struct.unpack("<i", len_bytes)
            if 1 <= length <= 2_000_000:
                encrypted = self._read_exact(sock, length)
                plain = decrypt_envelope(self.room_code, encrypted)
                if plain is not None:
                    track = json.loads(plain.decode("utf-8"))
                    self.on_track_received(track, "Wi-Fi Local")
                    return
        except Exception:
            pass
        finally:
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass
        with self._fetched_lock:
            self._fetched_track_ids.discard(track_id)

    @staticmethod
    def _read_exact(sock: socket.socket, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                raise EOFError("EOF TCP")
            buf.extend(chunk)
        return bytes(buf)


# ==============================================================================
# Geometría, simulación de pilotos y generación de tracks GPX compartidos
# ==============================================================================

def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_000.0
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2.0) ** 2 + math.cos(p1) * math.cos(p2) * (math.sin(dl / 2.0) ** 2)
    return 2.0 * r * math.atan2(math.sqrt(a), math.sqrt(max(0.0, 1.0 - a)))


def bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def offset_lat_lon(lat: float, lon: float, north_m: float, east_m: float) -> tuple[float, float]:
    dlat = north_m / 111_320.0
    cos_lat = max(0.15, math.cos(math.radians(lat)))
    dlon = east_m / (111_320.0 * cos_lat)
    return lat + dlat, lon + dlon


def build_sample_shared_track(
    center_lat: float,
    center_lon: float,
    sender_id: str,
    sender_name: str,
    track_name: str = "Ruta Trail Prueba Bot",
) -> dict:
    """Construye un WireSharedTrackPayload idéntico a CompactGroupTrackCodec.encodeRoute()."""
    num_pts = 60
    radius_m = 560.0
    raw_points: list[tuple[float, float, int]] = []
    for i in range(num_pts):
        ang = (2.0 * math.pi * i) / (num_pts - 1)
        wobble = 1.0 + 0.18 * math.sin(3.0 * ang)
        north = radius_m * wobble * math.cos(ang)
        east = radius_m * wobble * math.sin(ang)
        la, lo = offset_lat_lon(center_lat, center_lon, north, east)
        ele = int(round(680.0 + 55.0 * math.sin(ang) + 15.0 * math.cos(2.0 * ang)))
        raw_points.append((la, lo, ele))

    lat_deltas: list[int] = []
    lon_deltas: list[int] = []
    ele_deltas: list[int] = []
    prev_la = prev_lo = prev_el = 0

    for idx, (la, lo, el) in enumerate(raw_points):
        cla = int(round(la * 100_000.0))
        clo = int(round(lo * 100_000.0))
        if idx == 0:
            lat_deltas.append(cla)
            lon_deltas.append(clo)
            ele_deltas.append(el)
        else:
            lat_deltas.append(cla - prev_la)
            lon_deltas.append(clo - prev_lo)
            ele_deltas.append(el - prev_el)
        prev_la, prev_lo, prev_el = cla, clo, el

    w1_la, w1_lo = offset_lat_lon(center_lat, center_lon, radius_m * 0.95, 0.0)
    w2_la, w2_lo = offset_lat_lon(center_lat, center_lon, -radius_m * 0.4, radius_m * 0.85)
    now_ms = int(time.time() * 1000)

    return {
        "v": 1,
        "id": f"{now_ms}_{random.randint(1000, 9999)}",
        "n": track_name[:80],
        "sid": sender_id,
        "sn": sender_name[:32],
        "ts": now_ms,
        "clr": "orange",
        "latE5": lat_deltas,
        "lonE5": lon_deltas,
        "eleM": ele_deltas,
        "wpts": [
            {
                "n": "Fuente del Pinar",
                "la": int(round(w1_la * 100_000.0)),
                "lo": int(round(w1_lo * 100_000.0)),
                "el": 730,
                "sy": "water",
            },
            {
                "n": "Mirador Enduro",
                "la": int(round(w2_la * 100_000.0)),
                "lo": int(round(w2_lo * 100_000.0)),
                "el": 705,
                "sy": "flag",
            },
        ],
    }


@dataclass
class SimulatedRider:
    member_id: str
    name: str
    color: str
    speed_kmh: float
    radius_m: float
    phase_rad: float
    battery_pct: int
    lat: float = 40.4168
    lon: float = -3.7038
    alt_m: float = 690.0
    heading_deg: float = 0.0
    status: str = "riding"
    status_message: str = ""
    seq: int = 0
    paused: bool = False
    manual_offset_m: tuple[float, float] | None = None

    def step(self, dt_s: float, anchor_lat: float, anchor_lon: float) -> None:
        if self.manual_offset_m is not None:
            new_lat, new_lon = offset_lat_lon(
                anchor_lat, anchor_lon, self.manual_offset_m[0], self.manual_offset_m[1]
            )
            self.lat = new_lat
            self.lon = new_lon
            return

        if self.paused or self.status in ("wait", "mech", "sos"):
            return

        speed_ms = max(1.0, self.speed_kmh / 3.6)
        angular_v = speed_ms / max(60.0, self.radius_m)
        self.phase_rad = (self.phase_rad + angular_v * dt_s) % (2.0 * math.pi)

        north = self.radius_m * math.cos(self.phase_rad)
        east = self.radius_m * math.sin(self.phase_rad) * (1.0 + 0.25 * math.cos(self.phase_rad))
        new_lat, new_lon = offset_lat_lon(anchor_lat, anchor_lon, north, east)

        dist = haversine_m(self.lat, self.lon, new_lat, new_lon)
        if dist > 0.5:
            self.heading_deg = round(bearing_deg(self.lat, self.lon, new_lat, new_lon), 1)
        self.lat = new_lat
        self.lon = new_lon
        self.alt_m = round(680.0 + 45.0 * math.sin(self.phase_rad), 1)

    def build_wire_payload(self) -> dict:
        self.seq += 1
        actual_speed = 0.0 if (self.paused or self.status in ("wait", "mech", "sos")) else self.speed_kmh
        return {
            "v": 1,
            "id": self.member_id,
            "n": self.name[:24],
            "c": self.color,
            "la": round(self.lat, 6),
            "lo": round(self.lon, 6),
            "al": round(self.alt_m, 1),
            "sp": round(actual_speed, 1),
            "hd": round(self.heading_deg, 1),
            "bt": self.battery_pct,
            "st": self.status,
            "sm": self.status_message[:60],
            "sq": self.seq,
            "ts": int(time.time() * 1000),
        }


# ==============================================================================
# Visualización QR de Grupo (Consola + PNG + Ventana Tkinter)
# ==============================================================================

_tk_lock = threading.Lock()
_tk_window_open = False


def show_group_qr_window(matrix: list[list[bool]], room_display: str, invite_uri: str, subtitle: str) -> None:
    global _tk_window_open
    with _tk_lock:
        if _tk_window_open:
            return
        _tk_window_open = True

    def _run_tk() -> None:
        global _tk_window_open
        try:
            import tkinter as tk
        except Exception as exc:
            print(f"[QR] No se pudo abrir ventana Tkinter ({exc}). Usa el QR en terminal o el PNG.")
            with _tk_lock:
                _tk_window_open = False
            return

        try:
            size = len(matrix)
            quiet = 4
            cell = 8 if size <= 45 else 6
            canvas_px = (size + quiet * 2) * cell

            root = tk.Tk()
            root.title(f"EnduLink Grupo — Sala {room_display}")
            root.configure(bg="#FFFFFF")
            root.resizable(False, False)

            tk.Label(
                root,
                text="Escanea este QR en EnduLink → Grupo → Escanear QR",
                font=("Segoe UI", 11, "bold"),
                bg="#FFFFFF",
                fg="#111111",
                pady=8,
            ).pack(padx=12)

            tk.Label(
                root,
                text=room_display,
                font=("Consolas", 22, "bold"),
                bg="#FFFFFF",
                fg="#0066CC",
            ).pack()

            canvas = tk.Canvas(
                root,
                width=canvas_px,
                height=canvas_px,
                bg="#FFFFFF",
                highlightthickness=0,
            )
            canvas.pack(padx=16, pady=6)

            for y in range(size):
                for x in range(size):
                    if matrix[y][x]:
                        x0 = (x + quiet) * cell
                        y0 = (y + quiet) * cell
                        canvas.create_rectangle(x0, y0, x0 + cell, y0 + cell, fill="#000000", outline="")

            tk.Label(
                root,
                text=f"{invite_uri}\n{subtitle}",
                font=("Consolas", 9),
                bg="#FFFFFF",
                fg="#333333",
                justify="center",
                pady=10,
            ).pack(padx=12)

            root.mainloop()
        finally:
            with _tk_lock:
                _tk_window_open = False

    threading.Thread(target=_run_tk, name="qr-tk", daemon=True).start()


def emit_group_qr(room_code: str, qr_out_path: str, show_gui: bool, subtitle: str) -> None:
    room_display = format_room_code(room_code)
    invite_uri = build_invite_uri(room_code)
    matrix = encode_qr_matrix(invite_uri)
    save_qr_png(matrix, qr_out_path, scale=8, quiet=4)

    print("\n================ QR INVITACIÓN GRUPO ENDULINK ================")
    print(f"Código sala : {room_display}  (sin guion: {room_code})")
    print(f"Enlace URI  : {invite_uri}")
    print(f"PNG         : {os.path.abspath(qr_out_path)}")
    print("--------------------------------------------------------------")
    print(render_qr_terminal(matrix))
    print("==============================================================\n")

    if show_gui:
        show_group_qr_window(matrix, room_display=room_display, invite_uri=invite_uri, subtitle=subtitle)


# ==============================================================================
# Auto-test integrado (--self-test)
# ==============================================================================

def run_self_test() -> int:
    print("Ejecutando --self-test de companero_falso_grupo.py...")

    # 1. Vectores criptográficos estándar (idénticos a GroupCryptoTest.kt)
    assert hashlib.sha256(b"abc").hexdigest() == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    mac = hmac.new(b"Jefe", b"what do ya want for nothing?", hashlib.sha256).hexdigest()
    assert mac == "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"

    key = bytes(range(32))
    nonce = bytes.fromhex("000000000000004a00000000")
    pt = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
    ct = chacha20_xor(key, nonce, 1, pt)
    assert ct[:16].hex() == "6e2e359a2568f98041ba0728dd0d6981"
    assert chacha20_xor(key, nonce, 1, ct) == pt
    print("  [OK] Vectores SHA-256, HMAC-SHA256 y ChaCha20 RFC 8439")

    # 2. Invitaciones, topic hash y envoltorio E2EE
    assert parse_room_code("K7M-9P2") == "K7M9P2"
    assert parse_room_code("endulink://group/k7m9p2?x=1") == "K7M9P2"
    assert parse_room_code("http://www.carbit.com.cn/down6") is None
    assert format_room_code("K7M9P2") == "K7M-9P2"
    assert derive_topic_hash("K7M-9P2") == derive_topic_hash("k7m9p2")
    assert len(derive_topic_hash("K7M9P2")) == 32
    assert len(derive_room_tag8("K7M9P2")) == 8

    secret = b'{"id":"m_1","la":40.4168,"lo":-3.7038}'
    sealed = encrypt_envelope("K7M-9P2", secret)
    assert decrypt_envelope("k7m9p2", sealed) == secret
    assert decrypt_envelope("WRONG9", sealed) is None
    tampered = bytearray(sealed)
    tampered[15] ^= 0x01
    assert decrypt_envelope("K7M-9P2", bytes(tampered)) is None
    tampered_mac = bytearray(sealed)
    tampered_mac[-1] ^= 0x80
    assert decrypt_envelope("K7M-9P2", bytes(tampered_mac)) is None
    print("  [OK] Invitaciones, topicHash, roomTag8 y E2EE ChaCha20+HMAC")

    # 3. Códec binario MQTT 3.1.1 (incluso fragmentado byte a byte)
    dec = MqttStreamDecoder()
    big_payload = b"X" * 240
    stream = (
        MqttCodec.encode_connect("el_test", 60)
        + MqttCodec.encode_subscribe(7, "endulink/g1/abc/#")
        + MqttCodec.encode_publish("endulink/g1/abc/m/bot1", big_payload, retain=True)
        + MqttCodec.encode_pingreq()
        + MqttCodec.encode_disconnect()
    )
    assert len(stream) > 260
    connack_bytes = b"\x20\x02\x00\x00"
    suback_bytes = b"\x90\x03\x00\x07\x00"
    pub_bytes = MqttCodec.encode_publish("endulink/g1/abc/m/bot1", big_payload, retain=True)
    pingresp_bytes = b"\xD0\x00"
    rx_stream = connack_bytes + suback_bytes + pub_bytes + pingresp_bytes
    decoded_pkts: list[MqttPacket] = []
    for b in rx_stream:
        decoded_pkts.extend(dec.feed(bytes([b])))
    assert isinstance(decoded_pkts[0], MqttConnAck) and decoded_pkts[0].return_code == 0
    assert isinstance(decoded_pkts[1], MqttSubAck) and decoded_pkts[1].packet_id == 7
    assert isinstance(decoded_pkts[2], MqttPublish) and decoded_pkts[2].retain is True
    assert decoded_pkts[2].payload == big_payload
    assert isinstance(decoded_pkts[3], MqttPingResp)
    print("  [OK] Códec MQTT 3.1.1 y decodificador incremental")

    # 4. Loopback real WebSocket RFC 6455 + MQTT + cifrado de grupo
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    port = srv.getsockname()[1]

    def _ws_server_echo() -> None:
        conn, _ = srv.accept()
        try:
            raw = bytearray()
            while b"\r\n\r\n" not in raw:
                raw.extend(conn.recv(4096))
            headers = raw.split(b"\r\n\r\n", 1)[0].decode("latin-1")
            ws_key = ""
            for line in headers.split("\r\n"):
                if line.lower().startswith("sec-websocket-key:"):
                    ws_key = line.split(":", 1)[1].strip()
            accept_val = base64.b64encode(
                hashlib.sha1((ws_key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")).digest()
            ).decode("ascii")
            conn.sendall(
                (
                    "HTTP/1.1 101 Switching Protocols\r\n"
                    "Upgrade: websocket\r\n"
                    "Connection: Upgrade\r\n"
                    f"Sec-WebSocket-Accept: {accept_val}\r\n"
                    "Sec-WebSocket-Protocol: mqtt\r\n\r\n"
                ).encode("ascii")
            )

            def read_ws_frame() -> bytes:
                b1, b2 = conn.recv(2)
                ln = b2 & 0x7F
                if ln == 126:
                    (ln,) = struct.unpack(">H", conn.recv(2))
                mk = conn.recv(4) if (b2 & 0x80) else b""
                data = bytearray()
                while len(data) < ln:
                    data.extend(conn.recv(ln - len(data)))
                if mk:
                    return bytes(data[i] ^ mk[i & 3] for i in range(ln))
                return bytes(data)

            def send_ws_frame(payload: bytes) -> None:
                hdr = bytearray([0x82])
                if len(payload) <= 125:
                    hdr.append(len(payload))
                else:
                    hdr.append(126)
                    hdr.extend(struct.pack(">H", len(payload)))
                conn.sendall(bytes(hdr) + payload)

            srv_dec = MqttStreamDecoder()
            # CONNECT -> CONNACK
            srv_dec.feed(read_ws_frame())
            send_ws_frame(b"\x20\x02\x00\x00")
            # SUBSCRIBE -> SUBACK
            srv_dec.feed(read_ws_frame())
            send_ws_frame(b"\x90\x03\x00\x01\x00")
            # PUBLISH -> Echo PUBLISH
            pub_frame = read_ws_frame()
            send_ws_frame(pub_frame)
        finally:
            conn.close()
            srv.close()

    threading.Thread(target=_ws_server_echo, daemon=True).start()
    ws = SimpleWebSocketClient(f"ws://127.0.0.1:{port}/mqtt", subprotocol="mqtt", timeout=3.0)
    ws.connect()
    ws.send_binary(MqttCodec.encode_connect("sim_loopback", 60))
    dec2 = MqttStreamDecoder()
    pkts1 = dec2.feed(ws.recv_message() or b"")
    assert isinstance(pkts1[0], MqttConnAck)
    ws.send_binary(MqttCodec.encode_subscribe(1, "endulink/g1/test/#"))
    pkts2 = dec2.feed(ws.recv_message() or b"")
    assert isinstance(pkts2[0], MqttSubAck)
    rider = SimulatedRider(member_id="sim_1", name="Carlos", color="lime", speed_kmh=40.0, radius_m=200.0, phase_rad=0.0, battery_pct=88)
    enc_pos = encrypt_envelope("K7M9P2", json.dumps(rider.build_wire_payload()).encode("utf-8"))
    ws.send_binary(MqttCodec.encode_publish("endulink/g1/test/m/sim_1", enc_pos, retain=True))
    pkts3 = dec2.feed(ws.recv_message() or b"")
    assert isinstance(pkts3[0], MqttPublish)
    decrypted_pos = json.loads(decrypt_envelope("K7M9P2", pkts3[0].payload).decode("utf-8"))
    assert decrypted_pos["id"] == "sim_1" and decrypted_pos["c"] == "lime"
    ws.close()
    print("  [OK] Loopback real WebSocket RFC 6455 + MQTT + WirePositionPayload")

    # 5. Track GPX compartido y QR matrix
    track = build_sample_shared_track(40.4168, -3.7038, "sim_1", "Carlos")
    assert len(track["latE5"]) == 60 and len(track["lonE5"]) == 60 and len(track["eleM"]) == 60
    acc_la = 0
    for d in track["latE5"]:
        acc_la += d
    assert abs(acc_la / 100_000.0 - 40.4168) < 0.02
    qr_mat = encode_qr_matrix(build_invite_uri("K7M9P2"))
    assert len(qr_mat) >= 21 and "█" in render_qr_terminal(qr_mat)
    print("  [OK] Codificación delta de Track GPX y generación QR")

    print("Todos los checks de --self-test han pasado correctamente.")
    return 0


# ==============================================================================
# Coordinación de sesión interactiva
# ==============================================================================

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Simulador de compañero(s) de ruta para Grupos en Tiempo Real de EnduLink / motillOS"
    )
    parser.add_argument(
        "codigo",
        nargs="?",
        help="Código de sala (ej. K7M-9P2) o enlace endulink://group/... para unirse al grupo de tu móvil",
    )
    parser.add_argument("--crear", action="store_true", help="Crear una sala nueva y mostrar su QR/código")
    parser.add_argument("--unirse", default="", help="Código o enlace de sala existente al que unirse")
    parser.add_argument("--modo", choices=("hybrid", "online", "lan"), default="hybrid", help="Transporte a usar (por defecto: hybrid)")
    parser.add_argument("--broker", default="", help="URL ws:// o wss:// de broker MQTT personalizado")
    parser.add_argument("--lan-ip", default="", help="IP opcional del móvil para envío unicast UDP LAN")
    parser.add_argument("--nombre", default="", help="Nombre del piloto simulado principal")
    parser.add_argument("--color", choices=VALID_COLORS, default="lime", help="Color del piloto principal")
    parser.add_argument("--pilotos", type=int, default=1, help="Número de compañeros simulados (1..4)")
    parser.add_argument("--lat", type=float, default=40.4168, help="Latitud inicial por defecto")
    parser.add_argument("--lon", type=float, default=-3.7038, help="Longitud inicial por defecto")
    parser.add_argument("--fijo", action="store_true", help="No reanclar los bots a la posición real del móvil")
    parser.add_argument("--intervalo", type=float, default=3.0, help="Intervalo de publicación en segundos")
    parser.add_argument("--qr-out", default="Tools/qr_grupo_prueba.png", help="Ruta PNG donde guardar el QR")
    parser.add_argument("--no-gui", action="store_true", help="No abrir ventana Tkinter con el QR")
    parser.add_argument("--self-test", action="store_true", help="Ejecutar pruebas unitarias integradas y salir")
    return parser.parse_args()


def choose_room_interactively() -> tuple[str, bool]:
    print("\n=== Simulador de Compañero de Grupo EnduLink ===")
    print("  [1] Crear grupo nuevo (genera código + QR para unirte desde el móvil)")
    print("  [2] Unirse a un grupo creado en tu móvil (pegar código o enlace)")
    choice = input("Elige opción [1]: ").strip()
    if choice == "2":
        while True:
            raw = input("Introduce código (ej. K7M-9P2) o enlace endulink://group/...: ").strip()
            parsed = parse_room_code(raw)
            if parsed:
                return parsed, False
            print("  Código no válido. Usa 4-12 caracteres alfanuméricos o un enlace endulink://group/...")
    return generate_room_code(), True


def print_help() -> None:
    print(
        "\nComandos disponibles en vivo:\n"
        "  estado (s)     Ver sala, conexiones, bots y compañeros reales recibidos\n"
        "  ok / ruta      Poner piloto principal en estado 'En ruta'\n"
        "  espera [nota]  Enviar alerta 'Esperadme'\n"
        "  averia [nota]  Enviar alerta 'Avería'\n"
        "  sos [nota]     Enviar alerta crítica 'SOS · Ayuda'\n"
        "  cerca          Colocar piloto principal a ~120 m del móvil (pin visible en TFT)\n"
        "  lejos          Colocar piloto principal a ~2.4 km del móvil (radar de borde TFT)\n"
        "  aqui           Recentrar ruta de los bots alrededor del móvil\n"
        "  pausa / mover  Detener o reanudar movimiento circular\n"
        "  vel <kmh>      Cambiar velocidad del piloto principal\n"
        "  gpx [nombre]   Compartir un track GPX de prueba con el grupo\n"
        "  qr             Mostrar QR y código de invitación en consola/ventana\n"
        "  ayuda (h)      Mostrar esta ayuda\n"
        "  salir (q)      Salir enviando aviso de abandono limpio\n"
    )


def main() -> None:
    configure_console_utf8()
    args = parse_args()

    if args.self_test:
        sys.exit(run_self_test())

    raw_join = args.unirse.strip() or (args.codigo or "").strip()
    if args.crear:
        room_code = generate_room_code()
        created_by_pc = True
    elif raw_join:
        parsed = parse_room_code(raw_join)
        if not parsed:
            print(f"Error: código o enlace de sala inválido: {raw_join}")
            sys.exit(1)
        room_code = parsed
        created_by_pc = False
    else:
        room_code, created_by_pc = choose_room_interactively()

    num_bots = max(1, min(4, args.pilotos))
    bots: list[SimulatedRider] = []
    for i in range(num_bots):
        def_name, def_color, def_speed, def_radius, def_phase, def_batt = DEFAULT_BOT_ROSTER[i]
        name = args.nombre.strip()[:24] if (i == 0 and args.nombre.strip()) else def_name
        color = args.color if i == 0 else def_color
        slug = "".join(ch for ch in name.lower() if ch.isalnum())[:8] or f"bot{i+1}"
        member_id = f"sim_{slug}_{random.randint(1000, 9999)}"
        bots.append(
            SimulatedRider(
                member_id=member_id,
                name=name,
                color=color,
                speed_kmh=def_speed,
                radius_m=def_radius,
                phase_rad=def_phase,
                battery_pct=def_batt,
                lat=args.lat,
                lon=args.lon,
            )
        )

    own_bot_ids = {b.member_id for b in bots}
    state_lock = threading.Lock()
    anchor = {"lat": args.lat, "lon": args.lon, "auto_anchored": False}
    remote_peers: dict[str, dict] = {}
    stop_event = threading.Event()

    use_online = args.modo in ("hybrid", "online")
    use_lan = args.modo in ("hybrid", "lan")
    brokers = ([args.broker.strip()] + DEFAULT_MQTT_BROKERS) if args.broker.strip() else list(DEFAULT_MQTT_BROKERS)

    mqtt_transport: MqttWsTransport | None = None
    lan_transport: LanUdpTransport | None = None

    def publish_all_bots() -> None:
        with state_lock:
            payloads = [b.build_wire_payload() for b in bots]
        for p in payloads:
            if mqtt_transport is not None:
                mqtt_transport.publish_position(p)
            if lan_transport is not None:
                lan_transport.publish_position(p)

    def on_online_changed(connected: bool, broker_url: str) -> None:
        if connected:
            print(f"[Online MQTT] Conectado a {broker_url}")
        elif not stop_event.is_set() and broker_url:
            print(f"[Online MQTT] Reconectando...")

    def on_lan_changed(active: bool, rx_ok: bool) -> None:
        if active:
            rx_txt = f"RX+TX UDP {LAN_UDP_PORT}" if rx_ok else f"solo TX UDP {LAN_UDP_PORT}"
            print(f"[Wi-Fi Local] Activo ({rx_txt})")

    def on_position_received(payload: dict, source_label: str) -> None:
        pid = payload.get("id", "")
        if not pid or pid in own_bot_ids:
            return
        st = payload.get("st", "riding")
        if st == "left":
            with state_lock:
                removed = remote_peers.pop(pid, None)
            if removed is not None:
                print(f"[Grupo] {removed.get('n', pid)} salió del grupo.")
            return

        la = float(payload.get("la", 0.0))
        lo = float(payload.get("lo", 0.0))
        now = time.time()

        is_new_peer = False
        status_changed = False
        anchored_now = False

        with state_lock:
            prev = remote_peers.get(pid)
            is_new_peer = prev is None
            status_changed = prev is not None and (
                prev.get("st") != st or prev.get("sm", "") != payload.get("sm", "")
            )
            entry = dict(payload)
            entry["_src"] = source_label
            entry["_rx_ts"] = now
            remote_peers[pid] = entry

            if not args.fijo and not anchor["auto_anchored"] and (abs(la) > 0.001 or abs(lo) > 0.001):
                anchor["lat"] = la
                anchor["lon"] = lo
                anchor["auto_anchored"] = True
                for b in bots:
                    b.step(0.0, la, lo)
                anchored_now = True

        pname = payload.get("n") or pid
        if is_new_peer:
            print(f"\n[Grupo] ¡Compañero detectado!: {pname} ({source_label}) en ({la:.5f}, {lo:.5f})")
        if anchored_now:
            print(f"[Auto-ancla] Bots centrados cerca de tu móvil ({la:.5f}, {lo:.5f}). Ya deberías verlos en mapa y TFT.")
        if status_changed:
            st_lbl = STATUS_LABELS.get(st, st)
            note = payload.get("sm", "")
            extra = f" — «{note}»" if note else ""
            print(f"[Alerta compañero] {pname}: {st_lbl}{extra}")

    def on_member_left(member_id: str) -> None:
        with state_lock:
            removed = remote_peers.pop(member_id, None)
        if removed is not None:
            print(f"[Grupo] {removed.get('n', member_id)} salió del grupo.")

    def on_track_received(track: dict, source_label: str) -> None:
        tname = track.get("n", "Sin nombre")
        sname = track.get("sn", "Compañero")
        pts = len(track.get("latE5", []))
        print(f"\n[Track GPX recibido] '{tname}' de {sname} ({pts} puntos, vía {source_label})")

    if use_online:
        mqtt_transport = MqttWsTransport(
            room_code=room_code,
            own_bot_ids=own_bot_ids,
            brokers=brokers,
            on_connection_changed=on_online_changed,
            on_connected_ready=publish_all_bots,
            on_position_received=on_position_received,
            on_member_left=on_member_left,
            on_track_received=on_track_received,
        )
        mqtt_transport.start()

    if use_lan:
        lan_transport = LanUdpTransport(
            room_code=room_code,
            own_bot_ids=own_bot_ids,
            extra_unicast_ip=args.lan_ip.strip() or None,
            on_status_changed=on_lan_changed,
            on_position_received=on_position_received,
            on_track_received=on_track_received,
        )
        lan_transport.start()

    room_display = format_room_code(room_code)
    bot_names = ", ".join(f"{b.name} ({b.color})" for b in bots)
    print(f"\n[Sala {room_display}] Pilotos simulados: {bot_names}")
    print(f"[Sala {room_display}] Modo: {args.modo} | Intervalo: {args.intervalo:.1f}s | Auto-ancla: {'NO (--fijo)' if args.fijo else 'SÍ'}")

    if created_by_pc:
        emit_group_qr(
            room_code=room_code,
            qr_out_path=args.qr_out,
            show_gui=(not args.no_gui),
            subtitle=f"Pilotos: {bot_names}",
        )

    print_help()

    def _sim_loop() -> None:
        interval = max(1.0, args.intervalo)
        last_t = time.monotonic()
        publish_all_bots()
        while not stop_event.wait(interval):
            now_t = time.monotonic()
            dt = max(0.2, now_t - last_t)
            last_t = now_t
            with state_lock:
                a_lat = anchor["lat"]
                a_lon = anchor["lon"]
                for b in bots:
                    b.step(dt, a_lat, a_lon)
            publish_all_bots()

    threading.Thread(target=_sim_loop, name="sim-loop", daemon=True).start()

    try:
        while not stop_event.is_set():
            try:
                raw_cmd = input("grupo> ").strip()
            except EOFError:
                break
            if not raw_cmd:
                continue

            parts = raw_cmd.split(maxsplit=1)
            cmd = parts[0].lower()
            arg = parts[1].strip() if len(parts) > 1 else ""

            if cmd in ("salir", "exit", "quit", "q"):
                break
            elif cmd in ("ayuda", "help", "h", "?"):
                print_help()
            elif cmd in ("estado", "status", "s"):
                mqtt_st = "OK" if (mqtt_transport and mqtt_transport.connected) else ("OFF" if not mqtt_transport else "Conectando")
                lan_st = "OK" if (lan_transport and lan_transport.active) else "OFF"
                with state_lock:
                    a_lat, a_lon = anchor["lat"], anchor["lon"]
                    print(f"\n--- Sala {room_display} | MQTT: {mqtt_st} | LAN: {lan_st} | Ancla: ({a_lat:.5f}, {a_lon:.5f}) ---")
                    for b in bots:
                        print(f"  [BOT] {b.name} ({b.color}) -> {STATUS_LABELS.get(b.status, b.status)} | {b.speed_kmh:.0f} km/h | ({b.lat:.5f}, {b.lon:.5f}) | seq={b.seq}")
                    if not remote_peers:
                        print("  [MÓVIL] Aún no se ha recibido ningún compañero real en esta sala.")
                    else:
                        now = time.time()
                        for pid, p in remote_peers.items():
                            la, lo = float(p.get("la", 0.0)), float(p.get("lo", 0.0))
                            dist = haversine_m(bots[0].lat, bots[0].lon, la, lo)
                            age = int(max(0, now - p.get("_rx_ts", now)))
                            st_lbl = STATUS_LABELS.get(p.get("st", "riding"), p.get("st", ""))
                            print(f"  [REAL] {p.get('n', pid)} ({p.get('_src')}) -> {st_lbl} | {dist:.0f} m de {bots[0].name} | {p.get('sp', 0)} km/h | bat {p.get('bt', -1)}% | hace {age}s")
                    print("")
            elif cmd in ("ok", "ruta", "riding"):
                with state_lock:
                    bots[0].status = "riding"
                    bots[0].status_message = ""
                publish_all_bots()
                print(f"[{bots[0].name}] Estado -> En ruta")
            elif cmd in ("espera", "wait"):
                with state_lock:
                    bots[0].status = "wait"
                    bots[0].status_message = arg
                publish_all_bots()
                print(f"[{bots[0].name}] Alerta -> Esperadme" + (f" ({arg})" if arg else ""))
            elif cmd in ("averia", "mech"):
                with state_lock:
                    bots[0].status = "mech"
                    bots[0].status_message = arg or "Pinchazo / revisión"
                publish_all_bots()
                print(f"[{bots[0].name}] Alerta -> Avería ({bots[0].status_message})")
            elif cmd == "sos":
                with state_lock:
                    bots[0].status = "sos"
                    bots[0].status_message = arg or "Necesito ayuda"
                publish_all_bots()
                print(f"[{bots[0].name}] Alerta CRÍTICA -> SOS ({bots[0].status_message})")
            elif cmd == "cerca":
                with state_lock:
                    bots[0].manual_offset_m = (90.0, 80.0)
                    bots[0].step(0.0, anchor["lat"], anchor["lon"])
                publish_all_bots()
                print(f"[{bots[0].name}] Movido a ~120 m del centro (visible en mapa y TFT).")
            elif cmd == "lejos":
                with state_lock:
                    bots[0].manual_offset_m = (1800.0, 1600.0)
                    bots[0].step(0.0, anchor["lat"], anchor["lon"])
                publish_all_bots()
                print(f"[{bots[0].name}] Movido a ~2.4 km del centro (prueba de radar de borde TFT).")
            elif cmd == "aqui":
                with state_lock:
                    if remote_peers:
                        first = next(iter(remote_peers.values()))
                        anchor["lat"] = float(first.get("la", anchor["lat"]))
                        anchor["lon"] = float(first.get("lo", anchor["lon"]))
                    for b in bots:
                        b.manual_offset_m = None
                        b.step(0.0, anchor["lat"], anchor["lon"])
                publish_all_bots()
                print(f"[Bots] Recentrados en ({anchor['lat']:.5f}, {anchor['lon']:.5f}) y en movimiento.")
            elif cmd == "pausa":
                with state_lock:
                    for b in bots:
                        b.paused = True
                publish_all_bots()
                print("[Bots] Movimiento pausado.")
            elif cmd == "mover":
                with state_lock:
                    for b in bots:
                        b.paused = False
                        b.manual_offset_m = None
                publish_all_bots()
                print("[Bots] Movimiento reanudado.")
            elif cmd == "vel":
                try:
                    new_v = max(0.0, min(180.0, float(arg)))
                    with state_lock:
                        bots[0].speed_kmh = new_v
                    publish_all_bots()
                    print(f"[{bots[0].name}] Velocidad -> {new_v:.1f} km/h")
                except ValueError:
                    print("Uso: vel <kmh> (ej. vel 55)")
            elif cmd == "gpx":
                with state_lock:
                    a_lat, a_lon = anchor["lat"], anchor["lon"]
                    sender = bots[0]
                t_name = arg or f"Ruta Trail {room_display}"
                track = build_sample_shared_track(a_lat, a_lon, sender.member_id, sender.name, track_name=t_name)
                if mqtt_transport is not None:
                    mqtt_transport.publish_shared_track(track)
                if lan_transport is not None:
                    lan_transport.publish_shared_track(track)
                publish_all_bots()
                print(f"[GPX] Compartido '{t_name}' ({len(track['latE5'])} puntos) con el grupo.")
            elif cmd == "qr":
                emit_group_qr(
                    room_code=room_code,
                    qr_out_path=args.qr_out,
                    show_gui=(not args.no_gui),
                    subtitle=f"Pilotos: {bot_names}",
                )
            else:
                print(f"Comando desconocido: '{cmd}'. Escribe 'ayuda' para ver opciones.")
    except KeyboardInterrupt:
        print("\n[Simulador] Interrumpido por usuario (Ctrl+C).")
    finally:
        print("[Simulador] Enviando aviso de salida limpia...")
        stop_event.set()
        bot_id_list = [b.member_id for b in bots]
        if mqtt_transport is not None:
            mqtt_transport.leave_and_stop(bot_id_list)
        if lan_transport is not None:
            lan_transport.leave_and_stop(bot_id_list)
        print("[Simulador] Cerrado.")


if __name__ == "__main__":
    main()
