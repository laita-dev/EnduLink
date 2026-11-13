#!/usr/bin/env python3
"""
Simulador de pantalla TFT Rieju 307 (EasyConn) por Wi-Fi + generador de QR de prueba para EnduLink / motillOS.

Uso para probar solo el escaneo QR con la cámara (Android e iOS):
  python Tools/moto_falsa_wifi.py --qr
  python Tools/moto_falsa_wifi.py --qr --ssid DIRECT-HJ-5G-MIPRUEBA --pwd 87654321

Uso simulando la pantalla TFT en Wi-Fi (opcionalmente mostrando también el QR con --qr):
  1. Abre EnduLink en tu Android/iOS, pulsa en el icono de Moto y toca «Conectar y enviar mapa».
  2. Mira la IP del móvil que muestra la app en pantalla (o usa 127.0.0.1 con adb forward).
  3. Ejecuta en PowerShell:
       python Tools/moto_falsa_wifi.py <IP_DEL_MOVIL> [salida.h264] [segundos] [--qr]
       * Si pones 0 como segundos (o no lo indicas), la conexión será INDEFINIDA hasta pulsar Ctrl+C.
       * Ejemplo 60 segundos: python Tools/moto_falsa_wifi.py 192.168.1.45 salida.h264 60
       * Ejemplo indefinido:   python Tools/moto_falsa_wifi.py 192.168.1.45 salida.h264 0
  4. ¡Bloquea la pantalla del móvil a mitad de prueba y comprueba que siguen llegando tramas H.264!
  5. Reproduce el vídeo grabado con:
       ffplay -f h264 salida.h264
     o extrae una captura con:
       ffmpeg -y -i salida.h264 -frames:v 1 captura_tft.png
"""

import argparse
import json
import os
import select
import socket
import struct
import sys
import threading
import time
import urllib.parse
import zlib


# ==============================================================================
# Generador puro Python de Código QR ISO/IEC 18004 (Modo Byte, EC Level M, V1-V15)
# Sin dependencias externas: genera matriz, ASCII terminal, PNG y ventana Tkinter.
# ==============================================================================

_QR_EC_M_TABLE = {
    # version: (total_codewords, ec_per_block, g1_blocks, g1_data, g2_blocks, g2_data)
    1: (26, 10, 1, 16, 0, 0),
    2: (44, 16, 1, 28, 0, 0),
    3: (70, 26, 1, 44, 0, 0),
    4: (100, 18, 2, 32, 0, 0),
    5: (134, 24, 2, 43, 0, 0),
    6: (172, 16, 4, 27, 0, 0),
    7: (196, 18, 4, 31, 0, 0),
    8: (242, 22, 2, 38, 2, 39),
    9: (292, 22, 3, 36, 2, 37),
    10: (346, 26, 4, 43, 1, 44),
    11: (404, 30, 1, 50, 4, 51),
    12: (466, 22, 6, 36, 2, 37),
    13: (532, 22, 8, 37, 1, 38),
    14: (581, 24, 4, 40, 5, 41),
    15: (655, 24, 5, 41, 5, 42),
}

_QR_ALIGNMENT_POSITIONS = {
    1: [],
    2: [6, 18],
    3: [6, 22],
    4: [6, 26],
    5: [6, 30],
    6: [6, 34],
    7: [6, 22, 38],
    8: [6, 24, 42],
    9: [6, 26, 46],
    10: [6, 28, 50],
    11: [6, 30, 54],
    12: [6, 32, 58],
    13: [6, 34, 62],
    14: [6, 26, 46, 66],
    15: [6, 26, 48, 70],
}


def _gf_mul(x: int, y: int) -> int:
    z = 0
    for i in reversed(range(8)):
        z = (z << 1) ^ ((z >> 7) * 0x11D)
        z ^= ((y >> i) & 1) * x
    return z


def _rs_divisor(degree: int) -> list[int]:
    result = [0] * (degree - 1) + [1]
    root = 1
    for _ in range(degree):
        for j in range(degree):
            result[j] = _gf_mul(result[j], root)
            if j + 1 < degree:
                result[j] ^= result[j + 1]
        root = _gf_mul(root, 0x02)
    return result


def _rs_remainder(data: list[int], divisor: list[int]) -> list[int]:
    result = [0] * len(divisor)
    for b in data:
        factor = b ^ result.pop(0)
        result.append(0)
        for i, coef in enumerate(divisor):
            result[i] ^= _gf_mul(coef, factor)
    return result


def encode_qr_matrix(text: str) -> list[list[bool]]:
    payload = text.encode("utf-8")

    chosen_version = None
    for ver in range(1, 16):
        total_cw, ec_cw, g1_b, g1_d, g2_b, g2_d = _QR_EC_M_TABLE[ver]
        data_cw = g1_b * g1_d + g2_b * g2_d
        char_count_bits = 8 if ver <= 9 else 16
        needed_bits = 4 + char_count_bits + len(payload) * 8
        if needed_bits <= data_cw * 8:
            chosen_version = ver
            break

    if chosen_version is None:
        raise ValueError(f"Texto demasiado largo para QR v1-15 ({len(payload)} bytes)")

    ver = chosen_version
    total_cw, ec_cw, g1_b, g1_d, g2_b, g2_d = _QR_EC_M_TABLE[ver]
    data_cw = g1_b * g1_d + g2_b * g2_d
    char_count_bits = 8 if ver <= 9 else 16

    # 1. Flujo de bits (Modo Byte = 0100)
    bits: list[int] = []

    def append_bits(val: int, width: int) -> None:
        for i in reversed(range(width)):
            bits.append((val >> i) & 1)

    append_bits(0b0100, 4)
    append_bits(len(payload), char_count_bits)
    for byte in payload:
        append_bits(byte, 8)

    cap_bits = data_cw * 8
    append_bits(0, min(4, cap_bits - len(bits)))
    while len(bits) % 8 != 0:
        bits.append(0)

    data_bytes = [
        sum(bits[i + b] << (7 - b) for b in range(8))
        for i in range(0, len(bits), 8)
    ]
    pad_cycle = (0xEC, 0x11)
    pad_idx = 0
    while len(data_bytes) < data_cw:
        data_bytes.append(pad_cycle[pad_idx % 2])
        pad_idx += 1

    # 2. Bloques Reed-Solomon e intercalado
    div = _rs_divisor(ec_cw)
    blocks: list[list[int]] = []
    ecc_blocks: list[list[int]] = []
    offset = 0
    num_blocks = g1_b + g2_b
    for idx in range(num_blocks):
        blk_len = g1_d if idx < g1_b else g2_d
        blk = data_bytes[offset : offset + blk_len]
        offset += blk_len
        blocks.append(blk)
        ecc_blocks.append(_rs_remainder(blk, div))

    interleaved: list[int] = []
    max_data_len = max(g1_d, g2_d if g2_b > 0 else 0)
    for i in range(max_data_len):
        for blk in blocks:
            if i < len(blk):
                interleaved.append(blk[i])
    for i in range(ec_cw):
        for ecc in ecc_blocks:
            interleaved.append(ecc[i])

    # 3. Construcción de la matriz con patrones fijos
    size = ver * 4 + 17
    modules = [[False] * size for _ in range(size)]
    is_function = [[False] * size for _ in range(size)]

    def set_fn(x: int, y: int, black: bool) -> None:
        if 0 <= x < size and 0 <= y < size:
            modules[y][x] = black
            is_function[y][x] = True

    def draw_finder(cx: int, cy: int) -> None:
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                dist = max(abs(dx), abs(dy))
                xx, yy = cx + dx, cy + dy
                if 0 <= xx < size and 0 <= yy < size:
                    set_fn(xx, yy, dist != 2 and dist != 4)

    def draw_alignment(cx: int, cy: int) -> None:
        for dy in range(-2, 3):
            for dx in range(-2, 3):
                dist = max(abs(dx), abs(dy))
                set_fn(cx + dx, cy + dy, dist != 1)

    for i in range(size):
        set_fn(6, i, i % 2 == 0)
        set_fn(i, 6, i % 2 == 0)

    draw_finder(3, 3)
    draw_finder(size - 4, 3)
    draw_finder(3, size - 4)

    align_pos = _QR_ALIGNMENT_POSITIONS[ver]
    for ay in align_pos:
        for ax in align_pos:
            if (ax == 6 and ay == 6) or (ax == 6 and ay == size - 7) or (ax == size - 7 and ay == 6):
                continue
            draw_alignment(ax, ay)

    # Módulos reservados de formato + dark module
    for i in range(9):
        if i != 6:
            set_fn(8, i, False)
            set_fn(i, 8, False)
    for i in range(8):
        set_fn(size - 1 - i, 8, False)
        set_fn(8, size - 1 - i, False)
    set_fn(8, size - 8, True)

    # Información de versión (>= 7)
    if ver >= 7:
        rem = ver
        for _ in range(12):
            rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
        vbits = (ver << 12) | rem
        for i in range(18):
            black = ((vbits >> i) & 1) != 0
            a = size - 11 + (i % 3)
            b = i // 3
            set_fn(a, b, black)
            set_fn(b, a, black)

    # 4. Inserción en zig-zag de los codewords
    all_bits: list[int] = []
    for cw in interleaved:
        for b in reversed(range(8)):
            all_bits.append((cw >> b) & 1)

    bit_idx = 0
    right = size - 1
    while right >= 1:
        if right == 6:
            right = 5
        for vert in range(size):
            for j in range(2):
                x = right - j
                upward = ((right + 1) & 2) == 0
                y = (size - 1 - vert) if upward else vert
                if not is_function[y][x] and bit_idx < len(all_bits):
                    modules[y][x] = all_bits[bit_idx] != 0
                    bit_idx += 1
        right -= 2

    def draw_format(mask: int) -> None:
        # EC Level M = 0b00
        data = (0b00 << 3) | mask
        rem = data
        for _ in range(10):
            rem = (rem << 1) ^ ((rem >> 9) * 0x537)
        fbits = ((data << 10) | rem) ^ 0x5412

        for i in range(6):
            set_fn(8, i, ((fbits >> i) & 1) != 0)
        set_fn(8, 7, ((fbits >> 6) & 1) != 0)
        set_fn(8, 8, ((fbits >> 7) & 1) != 0)
        set_fn(7, 8, ((fbits >> 8) & 1) != 0)
        for i in range(9, 15):
            set_fn(14 - i, 8, ((fbits >> i) & 1) != 0)

        for i in range(8):
            set_fn(size - 1 - i, 8, ((fbits >> i) & 1) != 0)
        for i in range(8, 15):
            set_fn(8, size - 15 + i, ((fbits >> i) & 1) != 0)
        set_fn(8, size - 8, True)

    def apply_mask(mask: int) -> None:
        for y in range(size):
            for x in range(size):
                if is_function[y][x]:
                    continue
                if mask == 0:
                    inv = (x + y) % 2 == 0
                elif mask == 1:
                    inv = y % 2 == 0
                elif mask == 2:
                    inv = x % 3 == 0
                elif mask == 3:
                    inv = (x + y) % 3 == 0
                elif mask == 4:
                    inv = (x // 3 + y // 2) % 2 == 0
                elif mask == 5:
                    inv = (x * y) % 2 + (x * y) % 3 == 0
                elif mask == 6:
                    inv = ((x * y) % 2 + (x * y) % 3) % 2 == 0
                else:
                    inv = ((x + y) % 2 + (x * y) % 3) % 2 == 0
                if inv:
                    modules[y][x] = not modules[y][x]

    def penalty_score() -> int:
        penalty = 0
        # Regla 1: 5+ módulos seguidos del mismo color
        for r in range(size):
            run_c = False
            run_len = 0
            for c in range(size):
                if modules[r][c] == run_c and run_len > 0:
                    run_len += 1
                else:
                    if run_len >= 5:
                        penalty += 3 + (run_len - 5)
                    run_c = modules[r][c]
                    run_len = 1
            if run_len >= 5:
                penalty += 3 + (run_len - 5)

        for c in range(size):
            run_c = False
            run_len = 0
            for r in range(size):
                if modules[r][c] == run_c and run_len > 0:
                    run_len += 1
                else:
                    if run_len >= 5:
                        penalty += 3 + (run_len - 5)
                    run_c = modules[r][c]
                    run_len = 1
            if run_len >= 5:
                penalty += 3 + (run_len - 5)

        # Regla 2: Bloques 2x2
        for r in range(size - 1):
            for c in range(size - 1):
                col = modules[r][c]
                if col == modules[r][c + 1] == modules[r + 1][c] == modules[r + 1][c + 1]:
                    penalty += 3

        # Regla 3: Patrones tipo finder (1:1:3:1:1 con 4 blancos a un lado)
        pat_a = [True, False, True, True, True, False, True, False, False, False, False]
        pat_b = [False, False, False, False, True, False, True, True, True, False, True]
        for r in range(size):
            for c in range(size - 10):
                row_slice = modules[r][c : c + 11]
                if row_slice == pat_a or row_slice == pat_b:
                    penalty += 40
        for c in range(size):
            for r in range(size - 10):
                col_slice = [modules[r + k][c] for k in range(11)]
                if col_slice == pat_a or col_slice == pat_b:
                    penalty += 40

        # Regla 4: Balance de oscuros respecto al 50%
        dark = sum(1 for r in modules for cell in r if cell)
        total = size * size
        k = int(abs(dark * 100 / total - 50) // 5)
        penalty += k * 10
        return penalty

    # 5. Selección óptima de máscara (0..7)
    best_mask = 0
    best_score = 10**9
    for mask in range(8):
        apply_mask(mask)
        draw_format(mask)
        score = penalty_score()
        if score < best_score:
            best_score = score
            best_mask = mask
        apply_mask(mask)

    apply_mask(best_mask)
    draw_format(best_mask)
    return modules


def save_qr_png(matrix: list[list[bool]], out_path: str, scale: int = 8, quiet: int = 4) -> None:
    size = len(matrix)
    img_size = (size + quiet * 2) * scale
    raw_rows = bytearray()
    for py in range(img_size):
        raw_rows.append(0)  # Filter byte = None
        my = py // scale - quiet
        for px in range(img_size):
            mx = px // scale - quiet
            black = (0 <= mx < size and 0 <= my < size and matrix[my][mx])
            raw_rows.append(0 if black else 255)

    def png_chunk(chunk_type: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(chunk_type + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + chunk_type + data + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", img_size, img_size, 8, 0, 0, 0, 0)  # 8-bit grayscale
    idat = zlib.compress(bytes(raw_rows), level=6)
    png_data = (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", ihdr)
        + png_chunk(b"IDAT", idat)
        + png_chunk(b"IEND", b"")
    )
    with open(out_path, "wb") as f:
        f.write(png_data)


def render_qr_terminal(matrix: list[list[bool]], quiet: int = 2) -> str:
    size = len(matrix)
    padded_w = size + quiet * 2

    def is_black(x: int, y: int) -> bool:
        mx, my = x - quiet, y - quiet
        return 0 <= mx < size and 0 <= my < size and matrix[my][mx]

    # Fondo blanco brillante ANSI (\033[107;30m) para escaneo fiable en terminales oscuras
    lines: list[str] = []
    for y in range(0, padded_w, 2):
        row_chars = ["\033[107;30m"]
        for x in range(padded_w):
            top = is_black(x, y)
            bot = is_black(x, y + 1) if (y + 1 < padded_w) else False
            if top and bot:
                row_chars.append("█")
            elif top:
                row_chars.append("▀")
            elif bot:
                row_chars.append("▄")
            else:
                row_chars.append(" ")
        row_chars.append("\033[0m")
        lines.append("".join(row_chars))
    return "\n".join(lines)


def show_qr_window(matrix: list[list[bool]], url: str, ssid: str, pwd: str, blocking: bool = True) -> None:
    def _run_tk() -> None:
        try:
            import tkinter as tk
        except Exception as exc:
            print(f"[QR] No se pudo abrir ventana Tkinter ({exc}). Usa el QR en terminal o el PNG.")
            return

        size = len(matrix)
        quiet = 4
        cell = 7 if size <= 53 else 6
        canvas_px = (size + quiet * 2) * cell

        root = tk.Tk()
        root.title("EnduLink / motillOS — QR TFT Rieju 307 (Prueba)")
        root.configure(bg="#FFFFFF")
        root.resizable(False, False)

        header = tk.Label(
            root,
            text="Escanea este QR desde EnduLink (Android o iOS)",
            font=("Segoe UI", 12, "bold"),
            bg="#FFFFFF",
            fg="#111111",
            pady=8,
        )
        header.pack()

        canvas = tk.Canvas(
            root,
            width=canvas_px,
            height=canvas_px,
            bg="#FFFFFF",
            highlightthickness=0,
        )
        canvas.pack(padx=16, pady=4)

        for y in range(size):
            for x in range(size):
                if matrix[y][x]:
                    x0 = (x + quiet) * cell
                    y0 = (y + quiet) * cell
                    canvas.create_rectangle(
                        x0, y0, x0 + cell, y0 + cell, fill="#000000", outline=""
                    )

        info = tk.Label(
            root,
            text=f"SSID: {ssid}   ·   Clave: {pwd}\n{url}",
            font=("Consolas", 9),
            bg="#FFFFFF",
            fg="#333333",
            wraplength=max(canvas_px + 20, 420),
            justify="center",
            pady=10,
        )
        info.pack(padx=12)

        root.mainloop()

    if blocking:
        _run_tk()
    else:
        t = threading.Thread(target=_run_tk, daemon=True)
        t.start()


def build_bike_qr_url(ssid: str, pwd: str, name: str, model: str, sn: str) -> str:
    params = urllib.parse.urlencode(
        {
            "modelid": model,
            "sn": sn,
            "action": "9",
            "ssid": ssid,
            "pwd": pwd,
            "auth": "WPA-PSK",
            "name": name,
        }
    )
    return f"http://www.carbit.com.cn/down6/index.html?{params}"


def emit_test_qr(args: argparse.Namespace, blocking_gui: bool) -> None:
    ssid = args.ssid
    name = args.name or (ssid[7:] if ssid.startswith("DIRECT-") else ssid)
    url = build_bike_qr_url(ssid=ssid, pwd=args.pwd, name=name, model=args.model, sn=args.sn)
    matrix = encode_qr_matrix(url)

    png_path = args.qr_out
    save_qr_png(matrix, png_path, scale=8, quiet=4)

    print("\n================ QR DE PRUEBA (MOTO TFT EASYCONN) ================")
    print(f"SSID   : {ssid}")
    print(f"Clave  : {args.pwd}")
    print(f"Modelo : {args.model} | SN: {args.sn}")
    print(f"URL QR : {url}")
    print(f"PNG    : {os.path.abspath(png_path)}")
    print("------------------------------------------------------------------")
    print(render_qr_terminal(matrix))
    print("==================================================================\n")

    if not args.no_gui:
        show_qr_window(matrix, url=url, ssid=ssid, pwd=args.pwd, blocking=blocking_gui)


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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Simulador Wi-Fi pantalla TFT Rieju 307 + generador QR de prueba para EnduLink / motillOS"
    )
    parser.add_argument("phone_ip", nargs="?", help="IP Wi-Fi del móvil (opcional si solo usas --qr)")
    parser.add_argument("out_path", nargs="?", default="salida.h264", help="Archivo H.264 de salida")
    parser.add_argument("duration", nargs="?", type=float, default=0.0, help="Duración en segundos (0 = indefinido)")
    parser.add_argument("--qr", action="store_true", help="Generar y mostrar el código QR de prueba de la moto")
    parser.add_argument("--ssid", default="DIRECT-HJ-5G-RIEJU307", help="SSID Wi-Fi incluido en el QR")
    parser.add_argument("--pwd", default="12345678", help="Contraseña Wi-Fi incluida en el QR")
    parser.add_argument("--name", default="", help="Nombre corto del equipo en el QR")
    parser.add_argument("--model", default="37512", help="modelid del QR (por defecto Rieju/Carbit 37512)")
    parser.add_argument("--sn", default="8DVLX36WDMMNHVP3TWGR2", help="Número de serie del QR")
    parser.add_argument("--qr-out", default="Tools/qr_moto_prueba.png", help="Ruta PNG donde guardar el QR")
    parser.add_argument("--no-gui", action="store_true", help="No abrir ventana Tkinter (solo consola y PNG)")
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    if args.qr and not args.phone_ip:
        emit_test_qr(args, blocking_gui=True)
        return

    if not args.phone_ip:
        print("Uso:")
        print("  Solo generar QR de prueba:")
        print("    python Tools/moto_falsa_wifi.py --qr")
        print("  Simular pantalla TFT (y opcionalmente mostrar QR con --qr):")
        print("    python Tools/moto_falsa_wifi.py <IP_DEL_MOVIL> [salida.h264] [segundos (0=indefinido)] [--qr]")
        print("Ejemplos:")
        print("  python Tools/moto_falsa_wifi.py --qr")
        print("  python Tools/moto_falsa_wifi.py 192.168.1.45 salida.h264 60")
        print("  python Tools/moto_falsa_wifi.py 192.168.1.45 salida.h264 0 --qr")
        sys.exit(1)

    if args.qr:
        emit_test_qr(args, blocking_gui=False)

    phone_ip = args.phone_ip
    out_path = args.out_path
    duration = args.duration
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
