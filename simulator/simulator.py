"""
跨境冷链 GxP 合规系统 - 温控记录仪模拟器
=========================================
- 多设备并发上报（默认 100 台，可配置）
- 真实数据特性：温度随机波动、门偶发开关、轨迹逐步漂移、可注入超温事件
- 通过 HTTP 直接对接后端 IngestController（默认 http://localhost:8080）
- 也支持通过 MQTT（EMQX 1883）上报
"""
import argparse
import json
import random
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from concurrent.futures import ThreadPoolExecutor, as_completed
import urllib.request
import urllib.error


def now_utc():
    return datetime.now(timezone.utc)


def iso(t):
    return t.replace(microsecond=int(t.microsecond / 1000) * 1000).isoformat()


class DeviceSimulator:
    def __init__(self, device_no, task_no, form="COLD", base_temp=5.0,
                 inject_overheat_at=None, inject_underheat_at=None,
                 door_open_every=None, http_url="http://localhost:8080"):
        self.device_no = device_no
        self.task_no = task_no
        self.form = form
        self.base_temp = base_temp
        self.inject_overheat_at = inject_overheat_at
        self.inject_underheat_at = inject_underheat_at
        self.door_open_every = door_open_every
        self.http_url = http_url
        self.seq = 0
        self.lat = 31.23 + random.uniform(-1.0, 1.0)
        self.lng = 121.47 + random.uniform(-1.0, 1.0)
        self.lock = threading.Lock()
        self._running = True
        # 温度区间
        self.ranges = {
            "COLD": (2.0, 8.0),
            "FROZEN": (-25.0, -15.0),
            "NORMAL": (15.0, 25.0),
        }

    def stop(self):
        self._running = False

    def _next_sample(self, tick):
        with self.lock:
            self.seq += 1
            t = self.base_temp + random.gauss(0, 0.3)
            # 注入超温/超冷事件
            if self.inject_overheat_at is not None and tick == self.inject_overheat_at:
                t = 12.5
                print(f"  [设备{self.device_no}] 注入超温 {t}℃ @ tick={tick}")
            if self.inject_underheat_at is not None and tick == self.inject_underheat_at:
                t = -1.0
                print(f"  [设备{self.device_no}] 注入超冷 {t}℃ @ tick={tick}")
            door = False
            if self.door_open_every is not None and tick > 0 and tick % self.door_open_every == 0:
                door = True
            humidity = 50 + random.uniform(-3, 3)
            now = now_utc() + timedelta(seconds=tick * 30)
            return {
                "deviceNo": self.device_no,
                "taskNo": self.task_no,
                "seqNo": self.seq,
                "sampleAt": iso(now),
                "temperature": round(t, 3),
                "humidity": round(humidity, 2),
                "doorOpen": door,
                "latitude": round(self.lat + random.uniform(-0.001, 0.001), 6),
                "longitude": round(self.lng + random.uniform(-0.001, 0.001), 6),
                "driverEvent": None,
            }

    def _next_track(self, tick):
        # 轨迹点：每 5 个 tick 一个
        if tick % 5 != 0:
            return None
        self.lat += random.uniform(-0.01, 0.01)
        self.lng += random.uniform(-0.01, 0.01)
        return {
            "deviceNo": self.device_no,
            "taskNo": self.task_no,
            "seqNo": tick // 5,
            "sampleAt": iso(now_utc() + timedelta(seconds=tick * 30)),
            "latitude": round(self.lat, 6),
            "longitude": round(self.lng, 6),
            "speedKmh": round(random.uniform(40, 90), 2),
            "heading": round(random.uniform(0, 360), 2),
        }

    def post_batch(self, samples, tracks):
        body = json.dumps({"samples": samples, "tracks": tracks}).encode("utf-8")
        req = urllib.request.Request(
            self.http_url + "/api/ingest",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, r.read().decode("utf-8", errors="ignore")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", errors="ignore")
        except Exception as e:
            return -1, str(e)

    def run(self, ticks=20, batch_every=5):
        """模拟器主循环。每 batch_every 个 tick 一次上报。"""
        samples = []
        tracks = []
        for tick in range(ticks):
            if not self._running:
                break
            samples.append(self._next_sample(tick))
            tr = self._next_track(tick)
            if tr:
                tracks.append(tr)
            if len(samples) >= batch_every:
                status, body = self.post_batch(samples, tracks)
                if status == 200:
                    print(f"  [设备{self.device_no}] tick={tick} 上报成功 samples={len(samples)} tracks={len(tracks)}")
                else:
                    print(f"  [设备{self.device_no}] tick={tick} 上报失败 status={status} body={body[:200]}")
                samples = []
                tracks = []
        # 收尾
        if samples or tracks:
            status, body = self.post_batch(samples, tracks)
            print(f"  [设备{self.device_no}] 收尾上报 status={status}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--http-url", default="http://localhost:8080")
    ap.add_argument("--devices", type=int, default=100, help="设备数")
    ap.add_argument("--ticks", type=int, default=20, help="每设备采样次数")
    ap.add_argument("--batch-every", type=int, default=5, help="每多少 tick 一次上报")
    ap.add_argument("--workers", type=int, default=20, help="并发 worker 数")
    ap.add_argument("--task-no", default=None)
    ap.add_argument("--inject-overheat-devices", type=int, default=0,
                    help="多少设备注入超温")
    ap.add_argument("--inject-underheat-devices", type=int, default=0)
    ap.add_argument("--door-open-every", type=int, default=None)
    args = ap.parse_args()

    task_no = args.task_no or f"SIM-{uuid.uuid4().hex[:8]}"
    print(f"【模拟器】目标 {args.http_url} 设备={args.devices} tick={args.ticks} task={task_no}")

    # 1) 创建任务
    body = json.dumps({
        "taskNo": task_no,
        "origin": "上海浦东",
        "destination": "德国法兰克福",
        "originCountry": "CN",
        "destCountry": "DE",
        "driverName": "模拟司机",
        "vehicleNo": "SIM-VEH-001",
        "status": "IN_TRANSIT",
    }).encode("utf-8")
    try:
        req = urllib.request.Request(
            args.http_url + "/api/tasks",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=5) as r:
            print(f"  任务创建 status={r.status}")
    except Exception as e:
        print(f"  任务创建失败: {e}（继续）")

    sims = []
    overheat_set = set(random.sample(range(args.devices),
                                      min(args.inject_overheat_devices, args.devices)))
    underheat_set = set(random.sample(range(args.devices),
                                       min(args.inject_underheat_devices, args.devices)))
    for i in range(args.devices):
        sims.append(DeviceSimulator(
            device_no=f"SIM-DEVICE-{i:04d}",
            task_no=task_no,
            form="COLD",
            base_temp=5.0,
            inject_overheat_at=5 if i in overheat_set else None,
            inject_underheat_at=8 if i in underheat_set else None,
            door_open_every=args.door_open_every,
            http_url=args.http_url,
        ))

    t0 = time.time()
    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = [ex.submit(s.run, args.ticks, args.batch_every) for s in sims]
        for f in as_completed(futs):
            try:
                f.result()
            except Exception as e:
                print(f"  设备异常: {e}")
    print(f"【模拟器】完成 耗时 {time.time() - t0:.1f}s")


if __name__ == "__main__":
    main()
