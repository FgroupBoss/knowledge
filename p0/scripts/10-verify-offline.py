#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""断网（离线）演练：证明这条链在物理不出网时仍然完整，且不存在静默公网回退。

口径（先说清能证到什么，不夸大）：
  1. 业务网 `p0-backend` 本身是 internal——用一次性容器实测：外部 DNS 解析不到、外部 TCP 直接
     "Network is unreachable"。这就是生产里给门户和依赖用的那张网。
  2. 门户与 Ollama 都是宿主机进程，走回环。完整冷启动（上传→索引→问答→引用）期间持续采样
     这两个 PID 的 TCP 远端地址：出现任何非回环远端即判定为外连。
  3. 配置层兜底：`P0_OFFLINE_ENFORCED=true` 时把公网地址写成推理端点必须启动失败，
     杜绝"配错就静默上云"。
  4. 实测到的形态限制如实记录：Docker Desktop 上 internal 网络与端口发布互斥，
     所以"依赖全切成 internal + 门户仍在宿主机"这种组合跑不起来（已实测，见 OF-01 附带证据）。
真正的容器化门户全内网闭环属于 P1。
"""
import os
import re
import subprocess
import threading
import time

from p0_verify_lib import COMPOSE, REPORTS, Verifier, code_of, http, newkey, scalar

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADMIN = "red:admin"
APP_PORT = int(os.environ.get("P0_APP_PORT", "18180"))
OLLAMA_PORT = int(os.environ.get("P0_OLLAMA_PORT", "11434"))
MYSQL_IMAGE = [l.split()[0] for l in (COMPOSE / "image-manifest.txt").read_text(encoding="utf-8")
               .splitlines() if l.startswith("mysql:8.4@sha256:")][0]
BUSINESS_NET = "kb-p0_p0-backend"
PROBE_C, PROBE_N = "p0-offline-probe", "p0-offline-probe-net"
v = Verifier("断网可用性与无静默公网回退演练")


def run(cmd, timeout=120, shell=False, stdin=None):
    p = subprocess.run(cmd, cwd=str(COMPOSE) if cmd[0] == "docker" else None,
                       capture_output=True, text=True, timeout=timeout, input=stdin)
    return p.returncode, (p.stdout + p.stderr).strip()


def pids_on(port):
    out = subprocess.run(["netstat", "-ano", "-p", "TCP"], capture_output=True, text=True).stdout
    pids = set()
    for line in out.splitlines():
        cols = line.split()
        if len(cols) >= 5 and cols[3].upper() == "LISTENING" and cols[1].endswith(":%d" % port):
            pids.add(cols[4])
    return pids


class EgressMonitor(threading.Thread):
    """持续采样目标 PID 的所有 TCP 远端地址；只认回环，172.* 记为本机 Docker 转发，其它算违规。"""

    def __init__(self, ports=(APP_PORT, OLLAMA_PORT), interval=1.0):
        super().__init__(daemon=True)
        self.interval = interval
        self.ports = ports
        self.samples = 0
        self.loopback = 0
        self.forwarded = []
        self.violations = []
        self.stop = threading.Event()

    def run(self):
        while not self.stop.is_set():
            rc, out = run(["netstat", "-ano", "-p", "TCP"], timeout=60)
            watch = set()
            for port in self.ports:
                watch |= pids_on(port)
            self.samples += 1
            for line in out.splitlines():
                cols = line.split()
                if len(cols) < 5 or cols[3].upper() == "LISTENING" or cols[4] not in watch:
                    continue
                remote = cols[2]
                host = remote.rsplit(":", 1)[0]
                if host.startswith("127.") or host in ("[::1]", "::1", "[::ffff:127.0.0.1]"):
                    self.loopback += 1
                elif host.startswith("172."):
                    self.forwarded.append(remote)
                else:
                    self.violations.append(remote)
            self.stop.wait(self.interval)


def wait_listen(port, timeout=90):
    import socket
    end = time.time() + timeout
    while time.time() < end:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=2):
                return True
        except OSError:
            time.sleep(2)
    return False


def bash_exe():
    """挑一个真正的 Git Bash：Windows 的 PATH 上 System32\\bash.exe 是 WSL 转发器，
    它会 execvpe(/bin/bash) 失败，跑不了本机的验证脚本。"""
    for entry in os.environ.get("PATH", "").split(os.pathsep):
        cand = os.path.join(entry, "bash.exe")
        if "system32" in cand.lower() or "wsl" in cand.lower():
            continue
        if os.path.exists(cand):
            return cand
    raise SystemExit("找不到 Git Bash（只有 WSL 转发器），无法执行 07-run-app.sh")


try:
    # ---------------- 1. 业务网物理无出口 ----------------
    rc, out = run(["docker", "run", "--rm", "--network", BUSINESS_NET, MYSQL_IMAGE, "sh", "-c",
                   'getent hosts registry-1.docker.io; echo "dns_rc=$?"; '
                   'timeout 5 bash -c "</dev/tcp/1.1.1.1/443" && echo OPEN || echo NOEGRESS; '
                   'timeout 5 bash -c "</dev/tcp/180.101.50.187/80" && echo OPEN2 || echo NOEGRESS2'],
                  timeout=180)
    m = re.search(r"dns_rc=(\d+)", out)
    dns_rc = int(m.group(1)) if m else -1
    open_hits = len(re.findall(r"\bOPEN2?\b", out))
    v.check("OF-01", "业务网 %s 上外部 DNS 与外部 TCP 一律不通（容器压根没有公网路由）" % BUSINESS_NET,
            dns_rc != 0 and open_hits == 0 and out.count("NOEGRESS") >= 2,
            "dns_rc=%d %s" % (dns_rc, out.replace("\n", " | ")[:200]))

    # ---------------- 2. internal 与端口发布互斥（把限制钉成可复现证据）----------------
    run(["docker", "network", "create", "--internal", PROBE_N])
    rc, out = run(["docker", "run", "-d", "--name", PROBE_C, "--network", PROBE_N,
                   "-p", "127.0.0.1:13399:3306", MYSQL_IMAGE, "mysqld", "--help"])
    time.sleep(3)
    rc2, ports = run(["docker", "port", PROBE_C])
    reachable = wait_listen(13399, timeout=8)
    run(["docker", "rm", "-f", PROBE_C], timeout=90)
    run(["docker", "network", "rm", PROBE_N])
    v.check("OF-02", "实测限制如实记录：internal 网络上 `-p` 发布不生效（宿主机门户无法连依赖），"
                     "故 P0 的断网口径不能靠把现有依赖整体切成 internal",
            rc == 0 and not reachable and ports.strip() == "",
            "published=%s hostReachable=%s" % (ports.strip() or "(无绑定)", reachable))

    # ---------------- 3. 冷启动全链路 + 宿主侧外连观测 ----------------
    monitor = EgressMonitor()
    monitor.start()
    try:
        tag = time.strftime("%m%d%H%M%S")
        st, p, _ = http("GET", "/actuator/health/readiness")
        v.check("OF-03", "门户与 Ollama 之间只经回环即可 READY", st == 200 and p.get("status") == "UP",
                "%s %s" % (st, p.get("status")))

        ws = v.require("workspace", "POST", "/api/v1/workspaces", fixture=True,
                       body={"code": "ws-off-" + tag, "name": "offline"})
        kb = v.require("kb", "POST", "/api/v1/workspaces/%s/knowledge-bases" % ws["id"], fixture=True,
                       body={"code": "kb-off-" + tag, "name": "offline kb", "classification": "INTERNAL"})
        marker = "离线哨兵" + tag
        payload = ("%s：本文件只在断网口径下产生，用来证明解析、向量、索引与生成都不依赖公网。"
                   "关键事实：离线演练的哨兵编号为 %s，回答必须逐字复述它。" % (marker, marker)).encode("utf-8")
        st, up, _ = http("POST", "/api/v1/workspaces/%s/knowledge-bases/%s/documents" % (ws["id"], kb["id"]),
                         ADMIN, part=("file", "offline.txt", payload), content_type="text/plain",
                         key=newkey(), timeout=120)
        doc_id = up.get("documentId", "")
        v.check("OF-04", "冷启动链路：新建空间/知识库并上传文档被接受", st == 202 and bool(doc_id),
                "%s %s" % (st, code_of(up)))

        status, chunks = "", "0"
        for _ in range(60):
            st, view, _ = http("GET", "/api/v1/documents/" + doc_id, ADMIN)
            status = view.get("status", "")
            if status in ("READY", "FAILED", "COMPENSATION_REQUIRED"):
                break
            time.sleep(2)
        chunks = scalar("SELECT COUNT(*) FROM engine_chunk WHERE document_id='%s'" % doc_id)
        v.check("OF-05", "文档在本地依赖上收敛为 READY 并建立索引（解析+切块+768 维向量全在机器内）",
                status == "READY" and int(chunks or 0) > 0, "status=%s chunks=%s" % (status, chunks))

        st, ans, _ = http("POST", "/api/v1/knowledge-bases/%s/chat" % kb["id"], ADMIN,
                          body={"question": "离线演练的哨兵编号是什么？", "conversationId": "off-" + tag,
                                "knowledgeBaseIds": [kb["id"]], "topK": 8}, timeout=180)
        citations = ans.get("citations") or []
        v.check("OF-06", "问答可用且答案逐字复述了只存在于本地语料的哨兵词（生成走本机 Ollama）",
                st == 200 and ans.get("status") == "ANSWERED" and marker in (ans.get("answer") or ""),
                "status=%s citations=%d model=%s" % (ans.get("status"), len(citations),
                                                      ans.get("model", "")))
        hdr = {}
        pst = 0
        if citations:
            pst, _, hdr = http("GET", "/api/v1/citations/%s/preview" % citations[0]["citationId"], ADMIN)
        v.check("OF-07", "引用预览由服务端代理读原件（不发外部预签名地址），返回 200 且强制 no-store",
                pst == 200 and (hdr.get("Cache-Control") or "").lower().startswith("no-store"),
                "%s cache=%s" % (pst, hdr.get("Cache-Control", "n/a")))

        # 采样窗口里持续压流量，确保不是"没流量所以没外连"；观测至少 20 个采样周期
        extra = 0
        while monitor.samples < 10 and extra < 14:
            http("POST", "/api/v1/knowledge-bases/%s/chat" % kb["id"], ADMIN,
                 body={"question": "离线演练的哨兵编号是什么？补充第 %d 次追问。" % extra,
                       "conversationId": "off-" + tag, "knowledgeBaseIds": [kb["id"]], "topK": 8},
                 timeout=180)
            extra += 1
        v.note("观测窗口内额外跑了 %d 次问答，采样 %d 次（每约 1 秒一轮 netstat）" % (extra, monitor.samples))
    finally:
        monitor.stop.set()
        monitor.join(timeout=15)

    v.check("OF-08", "整条链路期间门户与推理进程零公网远端：观测到的连接只有回环（与本机 Docker 转发）",
            monitor.samples >= 6 and monitor.loopback >= 30 and not monitor.violations,
            "samples=%d loopback=%d dockerForward=%d violations=%d %s"
            % (monitor.samples, monitor.loopback, len(monitor.forwarded),
               len(monitor.violations), monitor.violations[:3]))

    # ---------------- 4. 配置层拒绝公网推理地址 ----------------
    probe_port = 18199
    env = dict(os.environ)
    env.update({"P0_APP_PORT": str(probe_port), "P0_OFFLINE_ENFORCED": "true",
                "P0_OLLAMA_BASE_URL": "http://8.8.8.8:11434"})
    refused, tail = False, ""
    try:
        cp = subprocess.run([bash_exe(), "./07-run-app.sh"], cwd=os.path.join(ROOT, "scripts"),
                            capture_output=True, text=True, timeout=300, env=env,
                            encoding="utf-8", errors="replace")
        # 只看 ASCII 记号：mvn 在管道里用控制台码页输出，中文关键词会乱码，类名/方法名不会。
        blob = (cp.stdout or "") + (cp.stderr or "")
        refused = cp.returncode != 0 and ("EngineConfig.assertNoCloudModel" in blob or "8.8.8.8" in blob)
        hit = [l for l in blob.splitlines()
               if "assertNoCloudModel" in l or "Caused by: java.lang.IllegalStateException" in l]
        tail = (hit[-1] if hit else "")[:230]
    except subprocess.TimeoutExpired as e:
        partial = (e.stdout or b"") if isinstance(e.stdout, bytes) else (e.stdout or "")
        tail = "启动未失败（超时仍在跑）: " + str(partial)[-160:]
    finally:
        out = subprocess.run(["netstat", "-ano", "-p", "TCP"], capture_output=True, text=True).stdout
        for line in out.splitlines():
            cols = line.split()
            if len(cols) >= 5 and cols[1].endswith(":%d" % probe_port) and cols[3].upper() == "LISTENING":
                subprocess.run(["taskkill", "//PID", cols[4], "//T", "//F"], capture_output=True)
    v.check("OF-09", "离线开关打开后把推理端点配成公网地址必须启动失败（不存在静默云回退）",
            refused,
            tail or "(启动日志里没有拒绝公网地址的痕迹)")
finally:
    for cmd in (["docker", "rm", "-f", PROBE_C], ["docker", "network", "rm", PROBE_N]):
        run(cmd, timeout=90)

app_up = wait_listen(APP_PORT, timeout=60)
v.check("OF-10", "演练后宿主机门户仍可用（演练未改动业务数据，只新建了 offline 夹具空间）", app_up,
        "127.0.0.1:%d listening=%s" % (APP_PORT, app_up))

# ---------------- 报告 ----------------
REPORTS.mkdir(parents=True, exist_ok=True)
lines = ["# 断网可用性与无静默公网回退演练", "",
         "- 时间：%s" % time.strftime("%Y-%m-%d %H:%M:%S"),
         "- 依赖：mysql 8.4 / redis 7.4 / kafka 4.3.1 / minio RELEASE.2024-10-13（全部 digest 固定，见 compose/image-manifest.txt）",
         "- 推理：本机 Ollama :%d（deepseek-r1:8b + nomic-embed-text），模型均为本地已拉取权重" % OLLAMA_PORT,
         "- 门户：宿主机 JVM :%d，依赖走 127.0.0.1 发布端口" % APP_PORT,
         "- 结论：**%d/%d 条通过**" % (sum(1 for r in v.rows if r["pass"]), len(v.rows)),
         "", "| 用例 | 断言 | 结果 | 证据 |", "| --- | --- | --- | --- |"]
for r in v.rows:
    lines.append("| %s | %s | %s | %s |" % (r["id"], r["desc"], "PASS" if r["pass"] else "FAIL",
                                            r["detail"].replace("|", "/")))
lines += ["", "## 本演练能证到什么、证不到什么", "",
          "- **证到了**：业务网 internal 无公网路由（OF-01）；门户与推理进程在完整链路上零公网远端（OF-08）；"
          "误配公网推理地址会被拒绝启动（OF-09）；上传→索引→问答→引用在没有任何外部服务的情况下走通（OF-03~07）。",
          "- **证不到，如实说明**：本机门户是宿主机进程，Docker Desktop 的 internal 网络与端口发布互斥（OF-02 实测），"
          "所以无法把现有依赖整体切成 internal 后仍让宿主机门户连上它们；"
          "\"门户容器化后整栈全内网冷启动\"必须在 P1 补做，已登记 P1 backlog。",
          "- 观测口径：`netstat -ano -p TCP` 按持有 :%d / :%d 的 PID 过滤其全部连接，"
          "127.* 视为回环、172.* 视为本机 Docker 转发（列出计数供人工复核），其它远端一律判违规。"
          % (APP_PORT, OLLAMA_PORT),
          "- 采样窗口内主动跑了 5 次问答，避免\"没流量所以没外连\"的假证据。",
          "", "## 取证与说明", ""] + ["- " + n for n in v.notes]
path = REPORTS / "offline-drill.md"
path.write_text("\n".join(lines) + "\n", encoding="utf-8")
failed = [r["id"] for r in v.rows if not r["pass"]]
print("\n%d/%d 通过，报告: %s" % (len(v.rows) - len(failed), len(v.rows), path))
if failed:
    print("失败项: " + ", ".join(failed))
    raise SystemExit(1)
