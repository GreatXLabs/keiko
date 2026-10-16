#!/usr/bin/env python3
"""
Cluster Client & Remote Orchestrator - Proyecto Orquitas (Keiko)
Cliente programático en Python sobre WebSockets para controlar el cluster de entrenamiento
distribuido en Dokploy (wss://neuroph.aguilucho.ar/ws) de forma autónoma.
"""

import sys
import os
import json
import uuid
import time
import base64
import asyncio
import argparse
from typing import Dict, Any, Optional, List

try:
    import websockets
except ImportError:
    print("[ERROR] websockets no instalado. Instalar con: pip install websockets", file=sys.stderr)
    sys.exit(1)

DEFAULT_SERVER_URI = os.getenv("NEUROPH_SERVER_URI", "wss://neuroph.aguilucho.ar/ws")
DEFAULT_ADMIN_TOKEN = os.getenv("NEUROPH_ADMIN_TOKEN", "secret-orquitas-2026")

# Timeouts controlados para no bloquear al orquestador ante un cluster caído o sin workers.
DEFAULT_CONNECT_TIMEOUT = 15   # segundos para el handshake WebSocket
DEFAULT_REQUEST_TIMEOUT = 30   # segundos para cada request/response


class ClusterUnavailableError(RuntimeError):
    """El cluster remoto no está disponible, no responde o no tiene workers activos."""


class ClusterClient:
    def __init__(self, uri: str = DEFAULT_SERVER_URI, token: str = DEFAULT_ADMIN_TOKEN):
        self.uri = uri
        self.token = token
        self.ws = None

    async def connect(self):
        try:
            self.ws = await websockets.connect(
                self.uri,
                max_size=100 * 1024 * 1024,
                ping_interval=20,
                ping_timeout=20,
                open_timeout=DEFAULT_CONNECT_TIMEOUT,
            )
            auth_msg = {
                "id": str(uuid.uuid4()),
                "type": "AUTH_REQUEST",
                "timestamp": int(time.time() * 1000),
                "payload": self.token
            }
            await self.ws.send(json.dumps(auth_msg))
            raw_resp = await asyncio.wait_for(self.ws.recv(), timeout=DEFAULT_REQUEST_TIMEOUT)
            resp = json.loads(raw_resp)
            if resp.get("payload") != "AUTH_OK":
                raise ClusterUnavailableError(f"Autenticación fallida con el cluster: {resp}")
        except ClusterUnavailableError:
            await self.close()
            raise
        except (asyncio.TimeoutError, OSError, ConnectionError) as e:
            await self.close()
            raise ClusterUnavailableError(f"No se pudo conectar al cluster ({self.uri}): {e}") from e
        except Exception as e:
            await self.close()
            raise ClusterUnavailableError(f"Conexión WebSocket fallida ({self.uri}): {e}") from e

    async def close(self):
        if self.ws:
            await self.ws.close()
            self.ws = None

    async def send_request(self, msg_type: str, payload: Any = "") -> Dict[str, Any]:
        if not self.ws:
            await self.connect()
        msg_id = str(uuid.uuid4())
        payload_str = payload if isinstance(payload, str) else json.dumps(payload)
        req = {
            "id": msg_id,
            "type": msg_type,
            "timestamp": int(time.time() * 1000),
            "payload": payload_str
        }
        await self.ws.send(json.dumps(req))
        try:
            raw_resp = await asyncio.wait_for(self.ws.recv(), timeout=DEFAULT_REQUEST_TIMEOUT)
        except (asyncio.TimeoutError, OSError, ConnectionError) as e:
            raise ClusterUnavailableError(f"El cluster no respondió a '{msg_type}': {e}") from e
        resp = json.loads(raw_resp)
        if resp.get("type") == "ERROR_RESPONSE":
            raise RuntimeError(f"Error del servidor cluster: {resp.get('error')}")
        return resp

    async def get_status(self) -> Dict[str, Any]:
        resp = await self.send_request("ADMIN_GET_STATUS")
        payload = resp.get("payload", "{}")
        return json.loads(payload) if isinstance(payload, str) else payload

    async def create_project(self, project_name: str) -> Dict[str, Any]:
        resp = await self.send_request("ADMIN_CREATE_PROJECT", project_name)
        payload = resp.get("payload", "{}")
        return json.loads(payload) if isinstance(payload, str) else payload

    async def list_projects(self) -> List[Dict[str, Any]]:
        resp = await self.send_request("ADMIN_LIST_PROJECTS")
        payload = resp.get("payload", "[]")
        return json.loads(payload) if isinstance(payload, str) else payload

    async def upload_dataset(self, csv_path: str, dataset_name: str,
                             input_count: int = 4, project_id: str = "default-project",
                             classes: Optional[List[str]] = None) -> str:
        if not os.path.exists(csv_path):
            raise FileNotFoundError(f"No se encontró el archivo CSV: {csv_path}")

        with open(csv_path, "r", encoding="utf-8") as f:
            lines = [l.strip() for l in f if l.strip()]

        if not lines:
            raise ValueError("El archivo CSV está vacío")

        header_line = lines[0]
        headers = [h.strip() for h in header_line.split(",")]
        total_cols = len(headers)
        num_rows = len(lines) - 1

        if classes is None:
            classes = ["ESPACIO_LIBRE", "LOBITO", "MURO", "OBSTACULO", "SALIDA"]

        dataset_id = f"ds-{uuid.uuid4().hex[:8]}"
        input_cols = list(range(input_count))
        output_cols = [total_cols - 1]

        column_configs = []
        for i in range(input_count):
            h_name = headers[i] if i < len(headers) else f"sensor_{i}"
            column_configs.append({
                "index": i,
                "columnIndex": i,
                "name": h_name,
                "columnName": h_name,
                "role": "INPUT",
                "normalization": "MIN_MAX_0_1",
                "normalizationType": "MIN_MAX_0_1"
            })
        column_configs.append({
            "index": total_cols - 1,
            "columnIndex": total_cols - 1,
            "name": headers[-1] if headers else "class_label",
            "columnName": headers[-1] if headers else "class_label",
            "role": "OUTPUT",
            "normalization": "NONE",
            "normalizationType": "NONE"
        })

        csv_content = "\n".join(lines)
        meta = {
            "id": dataset_id,
            "name": dataset_name,
            "projectId": project_id,
            "csvContent": csv_content,
            "numRows": num_rows,
            "hasHeader": True,
            "taskType": "CLASSIFICATION",
            "classLabels": classes,
            "inputColumns": input_cols,
            "outputColumns": output_cols,
            "columnConfigs": column_configs,
            "createdAt": int(time.time() * 1000)
        }

        resp = await self.send_request("ADMIN_UPLOAD_DATASET", meta)
        return dataset_id

    async def start_campaign(self, dataset_id: str, dataset_name: str,
                             campaign_name: str,
                             heuristic: str = "GUIDED",
                             min_layers: int = 1, max_layers: int = 2,
                             min_neurons: int = 8, max_neurons: int = 32,
                             learning_rate_min: float = 0.2, learning_rate_max: float = 0.5,
                             momentum_min: float = 0.4, momentum_max: float = 0.7,
                             max_tasks: int = 20, max_iterations: int = 800,
                             batch_size: int = 14,
                             project_id: str = "default-project") -> str:
        campaign_id = f"camp-{uuid.uuid4().hex[:8]}"
        cfg = {
            "campaignId": campaign_id,
            "name": campaign_name,
            "projectId": project_id,
            "datasetId": dataset_id,
            "datasetName": dataset_name,
            "heuristicType": heuristic.upper(),
            "minHiddenLayers": min_layers,
            "maxHiddenLayers": max_layers,
            "minNeuronsPerLayer": min_neurons,
            "maxNeuronsPerLayer": max_neurons,
            "allowedTransferFunctions": ["SIGMOID"],
            "learningRateMin": learning_rate_min,
            "learningRateMax": learning_rate_max,
            "momentumMin": momentum_min,
            "momentumMax": momentum_max,
            "maxIterations": max_iterations,
            "targetError": 0.0001,
            "patience": 250,
            "populationOrBatchSize": batch_size,
            "maxTotalTasks": max_tasks,
            "trainTestRatio": 0.70,
            "splitSeed": 42,
            "enableAugmentation": True,
            "augmentationFactor": 1,
            "augmentationNoise": 0.02,
            "createdAt": int(time.time() * 1000),
            "status": "RUNNING"
        }
        await self.send_request("ADMIN_START_CAMPAIGN", cfg)
        return campaign_id

    async def get_leaderboard(self, campaign_id: Optional[str] = None, dataset_id: Optional[str] = None) -> List[Dict[str, Any]]:
        if dataset_id:
            resp = await self.send_request("ADMIN_GET_DATASET_LEADERBOARD", dataset_id)
        elif campaign_id:
            resp = await self.send_request("ADMIN_GET_LEADERBOARD", campaign_id)
        else:
            resp = await self.send_request("ADMIN_GET_LEADERBOARD", "")
        payload = resp.get("payload", "[]")
        return json.loads(payload) if isinstance(payload, str) else payload

    async def wait_campaign(self, campaign_id: str, timeout_sec: int = 300, poll_interval: int = 4) -> List[Dict[str, Any]]:
        start_time = time.time()
        print(f"[ClusterClient] Monitoreando avance de campaña {campaign_id} (timeout {timeout_sec}s)...")
        consecutive_no_workers = 0
        while (time.time() - start_time) < timeout_sec:
            status = await self.get_status()
            active_camps = status.get("activeCampaigns", [])
            is_active = any(c.get("campaignId") == campaign_id for c in active_camps)
            pending = status.get("pendingTasks", 0)
            running = status.get("runningTasks", 0)
            completed = status.get("completedTasks", 0)
            workers = status.get("activeWorkers", 0)
            busy_slots = status.get("busySlots", 0)
            total_slots = status.get("totalSlots", 0)

            print(f"  -> Workers: {workers} ({busy_slots}/{total_slots} slots ocupados) | Pendientes: {pending} | Ejecutando: {running} | Completadas: {completed}")

            # Si la campaña sigue activa pero no hay workers capaces de procesarla,
            # abortar pronto para que el orquestador active el fallback local.
            if is_active and workers == 0:
                consecutive_no_workers += 1
                if consecutive_no_workers >= 3:
                    raise ClusterUnavailableError(
                        f"El cluster no tiene workers activos para procesar la campaña {campaign_id}."
                    )
            else:
                consecutive_no_workers = 0

            if not is_active and running == 0 and pending == 0:
                print(f"[ClusterClient] Campaña {campaign_id} completada.")
                break

            await asyncio.sleep(poll_interval)

        return await self.get_leaderboard(campaign_id=campaign_id)

    async def download_best_model(self, campaign_id: str, output_path: str) -> Dict[str, Any]:
        lb = await self.get_leaderboard(campaign_id=campaign_id)
        if not lb:
            raise RuntimeError(f"No hay modelos registrados en el leaderboard para la campaña {campaign_id}")

        best = lb[0]
        task_id = best.get("taskId")
        print(f"[ClusterClient] Descargando mejor modelo: Tarea [{task_id}] (Accuracy: {best.get('metrics', {}).get('accuracy', 0.0):.2f}%, RMSE: {best.get('metrics', {}).get('rmse', 1.0):.4f})...")

        req_payload = {
            "campaignId": campaign_id,
            "taskId": task_id
        }
        resp = await self.send_request("ADMIN_DOWNLOAD_MODEL", req_payload)
        b64_content = resp.get("payload", "")
        if not b64_content:
            raise RuntimeError("El servidor no devolvió contenido Base64 para el modelo")

        raw_bytes = base64.b64decode(b64_content)
        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        with open(output_path, "wb") as f:
            f.write(raw_bytes)

        print(f"[ClusterClient] Modelo .nnet guardado exitosamente en: {output_path} ({len(raw_bytes)} bytes)")
        return best

async def main_cli():
    parser = argparse.ArgumentParser(description="CLI de Control Autónomo de Cluster Neuroph-Train")
    parser.add_argument("--uri", default=DEFAULT_SERVER_URI, help="WebSocket URI del cluster")
    parser.add_argument("--token", default=DEFAULT_ADMIN_TOKEN, help="Token secreto de Administrador")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # status
    subparsers.add_parser("status", help="Consulta estado de workers, slots y tareas")

    # list-projects
    subparsers.add_parser("list-projects", help="Lista proyectos existentes")

    # upload
    upload_p = subparsers.add_parser("upload", help="Sube un dataset sintético CSV")
    upload_p.add_argument("--csv", required=True, help="Ruta al CSV")
    upload_p.add_argument("--name", required=True, help="Nombre del dataset")
    upload_p.add_argument("--inputs", type=int, default=4, help="Cantidad de sensores de entrada")
    upload_p.add_argument("--project", default="default-project", help="ID del proyecto")

    # start-campaign
    camp_p = subparsers.add_parser("start-campaign", help="Lanza una campaña heurística de entrenamiento")
    camp_p.add_argument("--dataset-id", required=True, help="ID del dataset cargado")
    camp_p.add_argument("--dataset-name", default="Dataset Webots", help="Nombre del dataset")
    camp_p.add_argument("--name", default="Campaña Autonoma", help="Nombre de campaña")
    camp_p.add_argument("--heuristic", default="GUIDED", choices=["GUIDED", "EVOLUTIONARY"])
    camp_p.add_argument("--min-layers", type=int, default=1)
    camp_p.add_argument("--max-layers", type=int, default=2)
    camp_p.add_argument("--min-neurons", type=int, default=8)
    camp_p.add_argument("--max-neurons", type=int, default=32)
    camp_p.add_argument("--max-tasks", type=int, default=10)

    # download-best
    dl_p = subparsers.add_parser("download-best", help="Descarga el mejor modelo .nnet de una campaña")
    dl_p.add_argument("--campaign-id", required=True, help="ID de campaña")
    dl_p.add_argument("--out", required=True, help="Ruta de destino del archivo .nnet")

    # leaderboard
    lb_p = subparsers.add_parser("leaderboard", help="Muestra la tabla de líderes")
    lb_p.add_argument("--campaign-id", default=None)
    lb_p.add_argument("--dataset-id", default=None)

    args = parser.parse_args()
    client = ClusterClient(uri=args.uri, token=args.token)

    try:
        await client.connect()
        if args.command == "status":
            st = await client.get_status()
            print(json.dumps(st, indent=2))
        elif args.command == "list-projects":
            prjs = await client.list_projects()
            print(json.dumps(prjs, indent=2))
        elif args.command == "upload":
            ds_id = await client.upload_dataset(args.csv, args.name, input_count=args.inputs, project_id=args.project)
            print(f"DATASET_ID={ds_id}")
        elif args.command == "start-campaign":
            camp_id = await client.start_campaign(
                dataset_id=args.dataset_id, dataset_name=args.dataset_name,
                campaign_name=args.name, heuristic=args.heuristic,
                min_layers=args.min_layers, max_layers=args.max_layers,
                min_neurons=args.min_neurons, max_neurons=args.max_neurons,
                max_tasks=args.max_tasks
            )
            print(f"CAMPAIGN_ID={camp_id}")
        elif args.command == "leaderboard":
            lb = await client.get_leaderboard(campaign_id=args.campaign_id, dataset_id=args.dataset_id)
            print(json.dumps(lb, indent=2))
        elif args.command == "download-best":
            best = await client.download_best_model(args.campaign_id, args.out)
            print("BEST_MODEL:", json.dumps(best, indent=2))
    finally:
        await client.close()

if __name__ == "__main__":
    asyncio.run(main_cli())
