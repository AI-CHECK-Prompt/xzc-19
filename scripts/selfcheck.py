"""接口自检脚本（外部调用版本，可独立运行）"""
import json
import sys
import time
import urllib.request
import urllib.error


API = "http://localhost:8080"


def http(method, path, body=None):
    url = API + path
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")
    except Exception as e:
        return -1, str(e)


def main():
    print("=" * 60)
    print(" 跨境冷链 GxP 合规系统 - 接口自检")
    print("=" * 60)
    # 1) ping
    s, b = http("GET", "/api/self-check/ping")
    print(f"[1] ping status={s} body={b}")
    if s != 200:
        print("  ✗ 后端不可达，请先 docker compose up")
        return 1

    # 2) 全套自检
    s, b = http("GET", "/api/self-check")
    if s != 200:
        print(f"  ✗ 自检失败: {b}")
        return 1
    r = json.loads(b)
    print(f"[2] self-check overallOk={r.get('overallOk')}")
    for it in r.get("items", []):
        mark = "✓" if it.get("passed") else "✗"
        print(f"  {mark} [{it.get('category')}] {it.get('name')}: {it.get('message')[:120]}")

    # 3) 任务列表
    s, b = http("GET", "/api/tasks")
    if s == 200:
        tasks = json.loads(b)
        print(f"[3] 任务数: {len(tasks)}")

    print("=" * 60)
    print(" 全部自检通过 ✓" if r.get("overallOk") else " 部分自检失败 ✗")
    return 0 if r.get("overallOk") else 1


if __name__ == "__main__":
    sys.exit(main())
