#!/usr/bin/env python3
"""
Simulador de pantalla TFT Rieju 307 (EasyConn) por Wi-Fi para probar EnduLink / motIOS desde Windows.

Uso en casa (PC y móvil en la misma red Wi-Fi o mediante ADB forward por USB):
  1. Abre EnduLink en tu Android, pulsa en la pestaña «Moto» y toca el botón «Conectar / Iniciar Transmisión».
  2. Mira la IP del móvil que muestra la app en pantalla (o usa 127.0.0.1 con adb forward).
  3. Ejecuta en PowerShell:
       python Tools/moto_falsa_wifi.py <IP_DEL_MOVIL> [salida.h264] [segundos]
       * Si pones 0 como segundos (o no lo indicas), la conexión será INDEFINIDA hasta pulsar Ctrl+C.
       * Ejemplo 60 segundos: python Tools/moto_falsa_wifi.py 192.168.1.45 salida.h264 60
       * Ejemplo indefinido:   python Tools/moto_falsa_wifi.py 192.168.1.45 salida.h264 0
  4. ¡Bloquea la pantalla del móvil a mitad de prueba y comprueba que siguen llegando tramas H.264!
  5. Reproduce el vídeo grabado con:
       ffplay -f h264 salida.h264
     o extrae una captura con:
       ffmpeg -y -i salida.h264 -frames:v 1 captura_tft.png
"""

import json
import select
import socket
import struct
import sys
import time


def connect_port(ip: str, port: int) -> socket.socket:
    s = socket.create_connection((ip, port), timeout=5.0)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    s.settimeout(5.0)
    return s


def read_exact(s: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise EOFError("El móvil cerró la conexión")
        buf.extend(chunk)
    return bytes(buf)


def pxc_write(s: socket.socket, cmd: int, body: bytes = b"") -> None:
    length = 16 + len(body)
    header = struct.pack("<IIII", cmd & 0xFFFFFFFF, length, (cmd ^ length) & 0xFFFFFFFF, 0)
    s.sendall(header + body)


def pxc_read(s: socket.socket) -> tuple[int, bytes]:
    cmd, length, chk, _ = struct.unpack("<IIII", read_exact(s, 16))
    if (cmd ^ length) & 0xFFFFFFFF != chk:
        raise ValueError(f"Cabecera PXC inválida: cmd={hex(cmd)} len={length}")
    return cmd, read_exact(s, length - 16)


def rv_write(s: socket.socket, cmd: int, body: bytes = b"") -> None:
    s.sendall(struct.pack("<HHI", cmd & 0xFFFF, len(body), 0) + body)


def rv_read(s: socket.socket) -> tuple[int, bytes]:
    cmd, length, _ = struct.unpack("<HHI", read_exact(s, 8))
    return cmd, read_exact(s, length)


def main() -> None:
    if len(sys.argv) < 2:
        print("Uso: python Tools/moto_falsa_wifi.py <IP_DEL_MOVIL> [salida.h264] [segundos (0=indefinido)]")
        print("Ejemplos:")
        print("  python Tools/moto_falsa_wifi.py 192.168.1.45 salida.h264 60   # 60 segundos")
        print("  python Tools/moto_falsa_wifi.py 192.168.1.45 salida.h264 0    # Indefinido (Ctrl+C para parar)")
        print("  python Tools/moto_falsa_wifi.py 127.0.0.1 salida.h264 0       # Vía USB con adb forward")
        sys.exit(1)

    phone_ip = sys.argv[1]
    out_path = sys.argv[2] if len(sys.argv) > 2 else "salida.h264"
    duration = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0
    is_infinite = (duration <= 0.0)

    dur_str = "INDEFINIDA (pulsa Ctrl+C para parar)" if is_infinite else f"{duration:.0f}s"
    print(f"[TFT Rieju 307 Sim] Conectando a móvil en {phone_ip}:10922/10921/10920 (Duración: {dur_str})...")

    c2p = connect_port(phone_ip, 10922)
    pxc_write(c2p, 0x00010000)
    print(f"  1. Canal preguntas TFT (C2P) -> respuesta {hex(pxc_read(c2p)[0])}")

    bike_info = {
        "sdkVersion": "1.0.14",
        "pxcVersion": "1.0.2",
        "uuid": "RIEJU307SIM",
        "HUID": "RIEJU307HUID01",
        "supportFunction": 128,
        "channel": "37512",
        "flavor": 51,
        "socketTimeoutPeriodWifi": 9,
        "supportSyncCorrectTime": True,
    }
    pxc_write(c2p, 0x00010010, json.dumps(bike_info).encode("utf-8"))
    cmd, body = pxc_read(c2p)
    greeting = json.loads(body.decode("utf-8"))
    signed_huid = greeting.get("encryptedHUID", "")
    print(f"  2. Presentación móvil ({hex(cmd)}): modelo={greeting.get('phoneModel')} firmaHUID={'OK' if signed_huid else 'VACIA'}")

    p2c = connect_port(phone_ip, 10922)
    pxc_write(p2c, 0x00020000)
    print(f"  3. Canal preguntas móvil (P2C) -> respuesta {hex(pxc_read(p2c)[0])}")

    cmd, body = pxc_read(p2c)
    print(f"  4. Estado inicial móvil ({hex(cmd)}): {body.decode('utf-8')}")
    pxc_write(p2c, cmd + 1)

    pxc_write(c2p, 0x00010450)
    cmd, body = pxc_read(c2p)
    print(f"  5. Hora sincronizada ({hex(cmd)}): {body.decode('utf-8')}")

    pxc_write(c2p, 0x000103E0, b'{"sn":"8DVLX36WDMMNHVP3TWGR2"}')
    pxc_read(c2p)

    for _ in range(3):
        if select.select([p2c], [], [], 1.2)[0]:
            cmd, body = pxc_read(p2c)
            pxc_write(p2c, cmd + 1)
            if cmd == 0x000201C0:
                print(f"  6. Verificación serie offline: {body.decode('utf-8')}")
                break

    ctrl = connect_port(phone_ip, 10921)
    # Petición idéntica a la Rieju 307: 592x816, fps=0 (bajo demanda), codec=2 (H.264), 4 Mbps
    cfg_payload = struct.pack("<hhiiihhi8x", 592, 816, 0, 2, 2, 0, 0, 4_194_304) + signed_huid.encode("utf-8")
    rv_write(ctrl, 0x0010, cfg_payload)
    cmd, body = rv_read(ctrl)
    print(f"  7. Configuración vídeo ({hex(cmd)}): {struct.unpack('<ihh', body[:8])}")

    rv_write(ctrl, 0x0040)
    rv_read(ctrl)

    rv_write(ctrl, 0x0070)
    cmd, _ = rv_read(ctrl)
    print(f"  8. Arranque codificador H.264 ({hex(cmd)}) -> {'LISTO' if cmd == 0x0071 else 'FALLO'}")
    if cmd != 0x0071:
        sys.exit(2)

    data = connect_port(phone_ip, 10920)
    total_frames = 0
    total_bytes = 0
    empty_polls = 0
    sec_frames = 0
    sec_bytes = 0
    t0 = time.time()
    last_beat = t0

    print(f"\nGrabando flujo H.264 en '{out_path}'... (¡Ahora puedes BLOQUEAR la pantalla del móvil!)\n")
    try:
        with open(out_path, "wb") as out_file:
            while is_infinite or (time.time() - t0 < duration):
                rv_write(data, 0x0072)
                (frame_len,) = struct.unpack("<i", read_exact(data, 4))
                if frame_len > 0:
                    chunk = read_exact(data, frame_len)
                    out_file.write(chunk)
                    total_frames += 1
                    total_bytes += frame_len
                    sec_frames += 1
                    sec_bytes += frame_len
                else:
                    empty_polls += 1
                    time.sleep(0.015)

                now = time.time()
                if now - last_beat >= 1.0:
                    elapsed = int(now - t0)
                    kbps = (sec_bytes * 8) / 1000.0
                    print(f"  [{elapsed:02d}s] {sec_frames:2d} fps | {kbps:6.0f} kbps | total: {total_frames} tramas ({total_bytes // 1024} KB)")
                    sec_frames = 0
                    sec_bytes = 0
                    last_beat = now

                    rv_write(ctrl, 0x0040)
                    rv_read(ctrl)
                    pxc_write(c2p, 0x70000000)
                    pxc_read(c2p)
                    while select.select([p2c], [], [], 0)[0]:
                        c, _ = pxc_read(p2c)
                        pxc_write(p2c, c + 1)
    except KeyboardInterrupt:
        print("\n\n[Simulador] Detenido por el usuario (Ctrl+C).")
    finally:
        for s in (data, ctrl, p2c, c2p):
            try:
                s.close()
            except OSError:
                pass

    duracion_real = int(time.time() - t0)
    print(f"\nTerminado tras {duracion_real}s: {total_frames} fotogramas H.264 ({total_bytes // 1024} KB) guardados en {out_path} (sondeos vacíos: {empty_polls}).")


if __name__ == "__main__":
    main()
