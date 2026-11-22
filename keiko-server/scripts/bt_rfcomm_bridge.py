#!/usr/bin/env python3
"""
Puente Bluetooth RFCOMM (SPP canal 1) <-> Socket TCP del Servidor Central (keiko-server).

Util en Linux cuando se empareja el modulo HC-05 con bluetoothctl pero no se quiere
crear /dev/rfcomm0 con sudo. Usa unicamente la libreria estandar de Python 3.

Ejemplo de uso:
  1. Levantar keiko-server:
       java -jar target/keiko-server.jar --port 5000 --reset
  2. En otra terminal, correr este puente apuntando a la MAC del HC-05:
       python3 scripts/bt_rfcomm_bridge.py --mac XX:XX:XX:XX:XX:XX
"""

import argparse
import os
import select
import socket
import sys
import time


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Puente Bluetooth RFCOMM <-> TCP para Orquitas (Keiko)")
    parser.add_argument(
        "--mac",
        default=os.environ.get("HC05_MAC", ""),
        help="Direccion MAC Bluetooth del modulo HC-05 (o variable de entorno HC05_MAC)",
    )
    parser.add_argument("--channel", type=int, default=1, help="Canal RFCOMM SPP (por defecto: 1)")
    parser.add_argument("--host", default="127.0.0.1", help="Host TCP de keiko-server (por defecto: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=5000, help="Puerto TCP de keiko-server (por defecto: 5000)")
    return parser.parse_args()


def bridge_once(mac: str, channel: int, host: str, port: int) -> None:
    if not hasattr(socket, "AF_BLUETOOTH") or not hasattr(socket, "BTPROTO_RFCOMM"):
        raise RuntimeError("Este intérprete Python no tiene soporte AF_BLUETOOTH/BTPROTO_RFCOMM.")

    print(f"[BT] Conectando por RFCOMM a {mac} (canal {channel})...", flush=True)
    bt_sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    bt_sock.settimeout(8.0)
    bt_sock.connect((mac, channel))
    bt_sock.setblocking(False)
    print(f"[BT] Enlace Bluetooth establecido con {mac}.", flush=True)

    try:
        tcp_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        tcp_sock.settimeout(5.0)
        tcp_sock.connect((host, port))
        tcp_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        tcp_sock.setblocking(False)
        print(f"[TCP] Conectado a keiko-server en {host}:{port}. Reenviando tramas...", flush=True)
    except Exception:
        bt_sock.close()
        raise

    try:
        sockets = [bt_sock, tcp_sock]
        while True:
            readable, _, exceptional = select.select(sockets, [], sockets, 1.0)
            if exceptional:
                break
            for src in readable:
                data = src.recv(1024)
                if not data:
                    return
                dst = tcp_sock if src is bt_sock else bt_sock
                dst.sendall(data)
    finally:
        bt_sock.close()
        tcp_sock.close()


def main() -> int:
    args = parse_args()
    if not args.mac:
        print("Error: indicá la dirección MAC del HC-05 con --mac XX:XX:XX:XX:XX:XX o HC05_MAC.", file=sys.stderr)
        return 1

    while True:
        try:
            bridge_once(args.mac, args.channel, args.host, args.port)
            print("[PUENTE] Conexion cerrada. Reintentando en 1.5 s...", flush=True)
        except KeyboardInterrupt:
            print("\n[PUENTE] Finalizado por el usuario.")
            return 0
        except Exception as exc:
            print(f"[PUENTE] Error de enlace ({exc}). Reintentando en 1.5 s...", flush=True)
        time.sleep(1.5)


if __name__ == "__main__":
    raise SystemExit(main())
