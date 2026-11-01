"""P0 验证脚本共用件：HTTP 调用、MySQL 只读取证、结果记录。仅供 p0/scripts/0?-verify-*.py 使用。"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("P0_BASE_URL", "http://127.0.0.1:18180")
COMPOSE = ROOT / "compose"
REPORTS = ROOT / "evaluation" / "reports"
FIXTURE_TOKEN = (COMPOSE / "secrets" / "fixture_token.txt").read_text(encoding="utf-8").strip()
UPLOAD_MAX_BYTES = int(os.environ.get("P0_UPLOAD_MAX_BYTES", "104857600"))


def http(method, path, subject=None, fixture=False, body=None, part=None, key=None, raw=None,
         content_type=None, timeout=60):
    """返回 (status, payload, headers)。part=(field, filename, bytes)；raw 用于任意请求体。"""
    headers = {}
    if subject:
        headers["X-P0-Subject"] = subject
    if fixture:
        headers["X-P0-Fixture-Token"] = FIXTURE_TOKEN
    if key:
        headers["Idempotency-Key"] = key
    data = None
    if part is not None:
        boundary = "----p0" + uuid.uuid4().hex
        field, filename, payload = part
        data = (("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
                 "Content-Type: %s\r\n\r\n" % (boundary, field, filename,
                                               content_type or "application/octet-stream")).encode("utf-8")
                + payload + ("\r\n--%s--\r\n" % boundary).encode("utf-8"))
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
    elif raw is not None:
        data = raw
        headers["Content-Type"] = content_type or "application/octet-stream"
    elif body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, parse(resp.read().decode("utf-8", "replace")), dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, parse(e.read().decode("utf-8", "replace")), dict(e.headers)
    except urllib.error.URLError as e:
        return 0, {"error": str(e.reason)}, {}


def parse(raw):
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"raw": raw[:200]}


def code_of(payload):
    return payload.get("code", "") if isinstance(payload, dict) else ""


def newkey():
    return str(uuid.uuid4())


class Verifier:
    def __init__(self, title):
        self.title = title
        self.rows = []
        self.notes = []

    def check(self, cid, desc, cond, detail=""):
        ok = bool(cond)
        self.rows.append({"id": cid, "desc": desc, "pass": ok, "detail": str(detail)})
        print(("PASS  " if ok else "FAIL  ") + cid + "  " + desc + "  [" + str(detail) + "]")
        return ok

    def require(self, desc, method, path, subject="red:admin", fixture=False, **kw):
        status, payload, _ = http(method, path, subject, fixture, **kw)
        if status >= 300:
            print("SETUP FAILED: " + desc + " -> " + str(status) + " "
                  + json.dumps(payload, ensure_ascii=False))
            sys.exit(2)
        return payload

    def note(self, text):
        self.notes.append(str(text))
        print("NOTE  " + str(text))

    def report(self, filename, extra=None):
        REPORTS.mkdir(parents=True, exist_ok=True)
        passed = sum(1 for r in self.rows if r["pass"])
        lines = ["# " + self.title, "",
                 "- 生成时间：%s" % time.strftime("%Y-%m-%d %H:%M:%S"),
                 "- 目标服务：%s" % BASE,
                 "- 结论：**%d/%d 条通过**" % (passed, len(self.rows)),
                 "", "| 用例 | 断言 | 结果 | 证据 |", "| --- | --- | --- | --- |"]
        for r in self.rows:
            lines.append("| %s | %s | %s | %s |" % (r["id"], r["desc"],
                                                    "PASS" if r["pass"] else "FAIL",
                                                    r["detail"].replace("|", "/")))
        lines += ["", "## 取证与说明"]
        lines += ["- " + n for n in self.notes]
        if extra:
            lines += ["", extra]
        path = REPORTS / filename
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print("\n%d/%d 通过，报告: %s" % (passed, len(self.rows), path))
        failed = [r["id"] for r in self.rows if not r["pass"]]
        if failed:
            print("失败项: " + ", ".join(failed))
            sys.exit(1)


def sql(query):
    """只读取证。口令走环境变量，不进命令行，也不出现在任何输出或报告里。"""
    pwd = (COMPOSE / "secrets" / "mysql_app_password.txt").read_text(encoding="utf-8").strip()
    user = (COMPOSE / "secrets" / "mysql_app_user.txt").read_text(encoding="utf-8").strip()
    db = (COMPOSE / "secrets" / "mysql_database.txt").read_text(encoding="utf-8").strip()
    proc = subprocess.run(["docker", "compose", "exec", "-T", "-e", "MYSQL_PWD", "mysql",
                           "mysql", "-N", "-B", "-u", user, db, "-e", query],
                          cwd=str(COMPOSE), env=dict(os.environ, MYSQL_PWD=pwd),
                          capture_output=True, text=True, timeout=90)
    if proc.returncode != 0:
        raise RuntimeError("sql failed: " + proc.stderr.strip()[:200])
    return proc.stdout.strip()


def scalar(query, default=0):
    out = sql(query)
    if not out:
        return default
    return out.split()[0]


def sse(path, subject, body, timeout=180):
    """读取 SSE 流，返回 (status, events, headers)。events 为 [{event, id, data}] 按到达顺序。"""
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json",
                                          "X-P0-Subject": subject, "Accept": "text/event-stream"},
                                 method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            events = []
            current = {}
            for line in resp.read().decode("utf-8", "replace").split("\n"):
                line = line.rstrip("\r")
                if line.startswith(":"):
                    continue
                if not line.strip():
                    if current:
                        events.append(current)
                        current = {}
                    continue
                if ":" not in line:
                    continue
                field, _, value = line.partition(":")
                value = value[1:] if value.startswith(" ") else value
                if field == "event":
                    current["event"] = value
                elif field == "id":
                    current["id"] = value
                elif field == "data":
                    current["data"] = (current.get("data", "") + value)
            if current:
                events.append(current)
            return resp.status, events, dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, [{"event": "http-error", "data": str(e.read()[:200])}], dict(e.headers)
    except urllib.error.URLError as e:
        return 0, [{"event": "connection-error", "data": str(e.reason)}], {}
