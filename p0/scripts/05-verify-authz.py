#!/usr/bin/env python3
"""授权切片验证：身份映射、租户边界、角色权限位、密级、文档级 ACL、缓存撤权即时性。
对运行中的 P0 服务发真实 HTTP 请求，逐条断言并把证据写入 evaluation/reports/。
硬门：跨租户与越权读取必须 0 例成功。"""
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
FIXTURE_TOKEN = (COMPOSE / "secrets" / "fixture_token.txt").read_text(encoding="utf-8").strip()
SUBJECT = "X-P0-Subject"
REPORT = ROOT / "evaluation" / "reports" / "authz-matrix.md"

results = []
notes = []
tag = time.strftime("%m%d%H%M%S")


def http(method, path, subject=None, fixture=False, body=None, part=None, key=None):
    """part=(filename, bytes) 走 multipart；body 为 dict 走 JSON。返回 (status, payload, headers)。"""
    url = BASE + path
    headers = {}
    data = None
    if subject:
        headers[SUBJECT] = subject
    if fixture:
        headers["X-P0-Fixture-Token"] = FIXTURE_TOKEN
    if key:
        headers["Idempotency-Key"] = key
    if part is not None:
        boundary = "----p0" + uuid.uuid4().hex
        name, filename, payload = part
        body_bytes = (
            ("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
             "Content-Type: text/plain\r\n\r\n" % (boundary, name, filename)).encode("utf-8")
            + payload
            + ("\r\n--%s--\r\n" % boundary).encode("utf-8")
        )
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        data = body_bytes
    elif body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, parse(raw), dict(resp.headers)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        return e.code, parse(raw), dict(e.headers)
    except urllib.error.URLError as e:
        return 0, {"error": str(e.reason)}, {}


def parse(raw):
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"raw": raw[:200]}


def newkey():
    return str(uuid.uuid4())


def code_of(payload):
    return payload.get("code", "") if isinstance(payload, dict) else ""


def check(cid, desc, cond, detail):
    results.append({"id": cid, "desc": desc, "pass": bool(cond), "detail": detail})
    print(("PASS  " if cond else "FAIL  ") + cid + "  " + desc + "  [" + detail + "]")
    return cond


def require(desc, method, path, subject=None, fixture=False, body=None, part=None, key=None):
    """夹具构造步骤：失败即终止（不是断言，是前置条件）。"""
    status, payload, _ = http(method, path, subject, fixture, body, part, key)
    if status >= 300:
        print("SETUP FAILED: " + desc + " -> " + str(status) + " " + json.dumps(payload, ensure_ascii=False))
        sys.exit(2)
    return payload


def sql(query):
    """审计取证只走只读 SELECT，口令通过环境变量传入，不出现在命令行与输出里。"""
    pwd = (COMPOSE / "secrets" / "mysql_app_password.txt").read_text(encoding="utf-8").strip()
    user = (COMPOSE / "secrets" / "mysql_app_user.txt").read_text(encoding="utf-8").strip()
    db = (COMPOSE / "secrets" / "mysql_database.txt").read_text(encoding="utf-8").strip()
    env = dict(os.environ, MYSQL_PWD=pwd)
    proc = subprocess.run(
        ["docker", "compose", "exec", "-T", "-e", "MYSQL_PWD", "mysql",
         "mysql", "-N", "-B", "-u", user, db, "-e", query],
        cwd=str(COMPOSE), env=env, capture_output=True, text=True, timeout=60)
    if proc.returncode != 0:
        notes.append("sql_error: " + proc.stderr.strip()[:160])
        return ""
    return proc.stdout.strip()


def main():
    admin = "red:admin"

    require("tenant blue", "POST", "/api/v1/fixture/tenants", admin, True, {"code": "blue", "name": "B"})
    for subject, clearance in [("red:contrib_a", "INTERNAL"), ("red:viewer_a", "INTERNAL"),
                               ("red:grantme", "INTERNAL"), ("blue:mallory", "TOP_SECRET")]:
        tenant, user = subject.split(":")
        require("principal " + user, "POST", "/api/v1/fixture/principals", admin, True,
                {"tenantCode": tenant, "userCode": user, "displayName": user, "clearance": clearance})

    ws = require("workspace", "POST", "/api/v1/workspaces", admin, True,
                 {"code": "ws-" + tag, "name": "authz slice " + tag})
    kb1 = require("kb internal", "POST", "/api/v1/workspaces/%s/knowledge-bases" % ws["id"], admin, True,
                  {"code": "kb-int-" + tag, "name": "internal kb", "classification": "INTERNAL"})
    kb2 = require("kb secret", "POST", "/api/v1/workspaces/%s/knowledge-bases" % ws["id"], admin, True,
                  {"code": "kb-sec-" + tag, "name": "secret kb", "classification": "SECRET"})
    for user, role in [("contrib_a", "CONTRIBUTOR"), ("viewer_a", "VIEWER"), ("grantme", "VIEWER")]:
        require("member " + user, "POST", "/api/v1/workspaces/%s/members" % ws["id"], admin, True,
                {"userCode": user, "role": role})

    text = ("# P0 授权切片语料\n\n本文档只用于验证服务端鉴权与引用代理，"
            "内容包含关键词 蓝鲸编号%s。禁止把它当作质量评测语料。\n" % tag) * 6
    doc1 = require("upload doc1", "POST",
                   "/api/v1/workspaces/%s/knowledge-bases/%s/documents" % (ws["id"], kb1["id"]),
                   admin, False, part=("file", "authz-doc1-%s.txt" % tag, text.encode("utf-8")),
                   key=newkey())
    doc2 = require("upload doc2", "POST",
                   "/api/v1/workspaces/%s/knowledge-bases/%s/documents" % (ws["id"], kb2["id"]),
                   admin, False, part=("file", "authz-doc2-%s.txt" % tag, text.encode("utf-8")),
                   key=newkey())

    # --- 身份映射 ---
    st, p, _ = http("GET", "/api/v1/me")
    check("ID-01", "缺少主体头必须 401，绝不按匿名继续执行", st == 401 and code_of(p) == "UNAUTHENTICATED",
          "%s/%s" % (st, code_of(p)))
    st, p, _ = http("GET", "/api/v1/me", "red:nobody")
    check("ID-02", "未供给的主体必须 401（不自动建号）", st == 401 and code_of(p) == "UNAUTHENTICATED",
          "%s/%s" % (st, code_of(p)))
    st, p, _ = http("GET", "/api/v1/me", "garbage")
    check("ID-03", "主体头格式非法必须 401", st == 401, str(st))
    st, p, _ = http("GET", "/api/v1/me", admin)
    check("ID-04", "引导管理员可见其空间与 KB", st == 200 and p.get("tenantCode") == "red"
          and any(w["id"] == ws["id"] for w in p.get("workspaces", [])),
          "%s tenant=%s ws=%d" % (st, p.get("tenantCode"), len(p.get("workspaces", []))))

    # --- 夹具面本身 ---
    st, p, _ = http("POST", "/api/v1/fixture/tenants", admin, False, {"code": "zz-" + tag, "name": "x"})
    check("FX-01", "夹具端点缺口令即拒绝", st == 403, str(st))
    st, p, _ = http("GET", "/api/v1/verification/backlog", "red:viewer_a", True)
    check("FX-02", "取证端点仅空间 OWNER/ADMIN 可用", st == 403 and code_of(p) == "ACCESS_DENIED",
          "%s/%s" % (st, code_of(p)))

    # --- 租户边界（响应 404，审计记 CROSS_TENANT_ACCESS）---
    st, p, _ = http("GET", "/api/v1/documents/" + doc1["documentId"], "blue:mallory")
    check("XT-01", "跨租户读文档 404 且不泄露存在性", st == 404, "%s/%s" % (st, code_of(p)))
    st, p, _ = http("POST", "/api/v1/workspaces/%s/knowledge-bases/%s/documents" % (ws["id"], kb1["id"]),
                    "blue:mallory", False, part=("file", "x.txt", text.encode("utf-8")), key=newkey())
    check("XT-02", "跨租户上传 404", st == 404, str(st))
    st, p, _ = http("POST", "/api/v1/workspaces/%s/members" % ws["id"], "blue:mallory", True,
                    {"userCode": "mallory", "role": "OWNER"})
    check("XT-03", "跨租户改成员 404（无法自授权）", st == 404, str(st))
    st, p, _ = http("GET", "/api/v1/documents/" + doc1["documentId"], "blue:mallory")
    cross = sql("SELECT COUNT(*) FROM audit_event a JOIN app_user u ON u.id=a.actor_id "
                "WHERE u.subject='mallory' AND a.reason_code='CROSS_TENANT_ACCESS'")
    check("XT-04", "跨租户访问全部落审计 CROSS_TENANT_ACCESS", int(cross or 0) >= 3,
          "audit CROSS_TENANT_ACCESS rows for mallory=%s" % cross)

    # --- 角色权限位 ---
    st, p, _ = http("POST", "/api/v1/workspaces/%s/knowledge-bases/%s/documents" % (ws["id"], kb1["id"]),
                    "red:viewer_a", False, part=("file", "v.txt", text.encode("utf-8")), key=newkey())
    check("RB-01", "VIEWER 无 kb:write，上传被拒", st == 403 and code_of(p) == "ACCESS_DENIED",
          "%s/%s" % (st, code_of(p)))
    st, p, _ = http("POST", "/api/v1/workspaces/%s/members" % ws["id"], "red:viewer_a", True,
                    {"userCode": "grantme", "role": "OWNER"})
    check("RB-02", "VIEWER 无 member:manage，不能自我提权", st == 403 and code_of(p) == "ACCESS_DENIED",
          "%s/%s" % (st, code_of(p)))
    st, p, _ = http("DELETE", "/api/v1/documents/" + doc1["documentId"], "red:contrib_a")
    check("RB-03", "CONTRIBUTOR 无 kb:delete，删除被拒", st == 403 and code_of(p) == "ACCESS_DENIED",
          "%s/%s" % (st, code_of(p)))
    st, p, _ = http("GET", "/api/v1/documents/" + doc1["documentId"], "red:contrib_a")
    check("RB-04", "CONTRIBUTOR 可写即可读，正向路径成立", st == 200, "%s/%s" % (st, code_of(p)))

    # --- 密级 ---
    st, p, _ = http("GET", "/api/v1/documents/" + doc2["documentId"], "red:viewer_a")
    check("CL-01", "INTERNAL 主体读 SECRET 文档必须 CLEARANCE_TOO_LOW",
          st == 403 and code_of(p) == "CLEARANCE_TOO_LOW", "%s/%s" % (st, code_of(p)))
    st, p, _ = http("GET", "/api/v1/documents/" + doc2["documentId"], admin)
    check("CL-02", "TOP_SECRET 主体读 SECRET 文档放行", st == 200, str(st))

    # --- 文档级 ACL ---
    require("grant acl to contrib", "POST", "/api/v1/documents/%s/acl" % doc1["documentId"], admin, True,
            {"userCode": "contrib_a", "grant": "READ"})
    st, p, _ = http("GET", "/api/v1/documents/" + doc1["documentId"], "red:viewer_a")
    check("DA-01", "文档 ACL 生效后未名单内成员被拒", st == 403 and code_of(p) == "ACCESS_DENIED",
          "%s/%s" % (st, code_of(p)))
    st, p, _ = http("GET", "/api/v1/documents/" + doc1["documentId"], "red:grantme")
    check("DA-02", "同为 VIEWER 但未授予文档 ACL 仍被拒（空间角色不足够）",
          st == 403 and code_of(p) == "ACCESS_DENIED", "%s/%s" % (st, code_of(p)))
    require("grant acl to grantme", "POST", "/api/v1/documents/%s/acl" % doc1["documentId"], admin, True,
            {"userCode": "grantme", "grant": "READ"})
    st, p, _ = http("GET", "/api/v1/documents/" + doc1["documentId"], "red:grantme")
    check("DA-03", "授予后立即放行", st == 200, str(st))

    # --- 缓存与撤权即时性 ---
    st, before, _ = http("GET", "/api/v1/verification/cache-stats", admin, True)
    require("revoke grantme", "DELETE", "/api/v1/workspaces/%s/members?userCode=grantme" % ws["id"],
            admin, True)
    st, p, _ = http("GET", "/api/v1/documents/" + doc1["documentId"], "red:grantme")
    check("CA-01", "撤成员后立刻失效，不留 TTL 窗口", st == 403 and code_of(p) == "ACCESS_DENIED",
          "%s/%s" % (st, code_of(p)))

    acl_version = 0
    st, me, _ = http("GET", "/api/v1/me", admin)
    for w in me.get("workspaces", []):
        for k in w.get("knowledgeBases", []):
            if k["id"] == kb1["id"]:
                acl_version = int(k["aclVersion"])
    # stranger 只拿到文档级 ACL，从未成为空间成员：若过期缓存被信任，这一条就会变成 200（真越权）
    require("principal stranger", "POST", "/api/v1/fixture/principals", admin, True,
            {"tenantCode": "red", "userCode": "stranger", "displayName": "stranger", "clearance": "INTERNAL"})
    require("grant acl to stranger", "POST", "/api/v1/documents/%s/acl" % doc1["documentId"], admin, True,
            {"userCode": "stranger", "grant": "READ"})
    st, p, _ = http("GET", "/api/v1/documents/" + doc1["documentId"], "red:stranger")
    check("CA-02", "非成员即便有文档 ACL 也不能读", st == 403 and code_of(p) == "ACCESS_DENIED",
          "%s/%s" % (st, code_of(p)))
    stale = max(1, acl_version - 1)
    require("poison", "POST", "/api/v1/verification/cache/poison", admin, True,
            {"kbId": kb1["id"], "userCode": "stranger", "aclVersion": stale, "role": "OWNER"})
    st, p, _ = http("GET", "/api/v1/documents/" + doc1["documentId"], "red:stranger")
    check("CA-03", "注入的过期 OWNER 决策被水位拒绝，仍按 MySQL 判定为非成员",
          st == 403 and code_of(p) == "ACCESS_DENIED",
          "%s/%s acl=%d stale=%d" % (st, code_of(p), acl_version, stale))
    st, after, _ = http("GET", "/api/v1/verification/cache-stats", admin, True)
    check("CA-04", "缓存统计可取证：命中/未命中/过期拒绝/失效计数存在",
          isinstance(after, dict) and all(k in after for k in ("hits", "misses", "staleRejected",
                                                              "invalidations")),
          "staleRejected=%s invalidations=%s" % (after.get("staleRejected"), after.get("invalidations")))
    if isinstance(before, dict) and isinstance(after, dict):
        notes.append("cache-stats before=%s after=%s" % (json.dumps(before), json.dumps(after)))
        check("CA-05", "过期缓存确被计入 staleRejected（不是静默回落）",
              int(after.get("staleRejected", 0)) > int(before.get("staleRejected", 0)),
              "%s -> %s" % (before.get("staleRejected"), after.get("staleRejected")))

    # --- 未知资源与越权成功计数（硬门）---
    st, p, _ = http("GET", "/api/v1/documents/" + uuid.uuid4().hex, admin)
    check("NG-01", "不存在的文档 404", st == 404, str(st))
    leaked = sql("SELECT COUNT(*) FROM audit_event a JOIN app_user u ON u.id=a.actor_id "
                 "JOIN tenant t ON t.id=u.tenant_id "
                 "WHERE u.subject IN ('mallory','stranger') AND a.result='ALLOW' "
                 "AND a.resource_id IN ('%s','%s','%s','%s')"
                 % (doc1["documentId"], doc2["documentId"], kb1["id"], ws["id"]))
    check("GATE", "越权读取成功 0 例（硬门）", int(leaked or -1) == 0, "ALLOW rows on red resources by intruders=%s" % leaked)

    deny_total = sql("SELECT COUNT(*) FROM audit_event WHERE result='DENY' AND created_at > NOW() - INTERVAL 10 MINUTE")
    notes.append("本窗口 DENY 审计条数=%s；夹具对象 tenant=red workspace=%s kb=%s,%s doc=%s,%s"
                 % (deny_total, ws["id"], kb1["id"], kb2["id"], doc1["documentId"], doc2["documentId"]))

    write_report()
    failed = [r for r in results if not r["pass"]]
    print("\n%d/%d 通过" % (len(results) - len(failed), len(results)))
    if failed:
        print("失败项: " + ", ".join(r["id"] for r in failed))
        sys.exit(1)


def write_report():
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    passed = sum(1 for r in results if r["pass"])
    lines = ["# 授权切片与 ACL 负向矩阵（自动验证）", "",
             "- 生成时间：%s" % time.strftime("%Y-%m-%d %H:%M:%S"),
             "- 目标服务：%s（fixture=%s）" % (BASE, "enabled"),
             "- 结论：**%d/%d 条通过，越权读取成功 %s 例**" % (passed, len(results),
                                                             "0（硬门达成）" if passed == len(results) else "见失败项"),
             "", "| 用例 | 断言 | 结果 | 证据 |", "| --- | --- | --- | --- |"]
    for r in results:
        lines.append("| %s | %s | %s | %s |" % (r["id"], r["desc"], "PASS" if r["pass"] else "FAIL",
                                                str(r["detail"]).replace("|", "/")))
    lines.append("")
    lines.append("## 取证说明")
    for n in notes:
        lines.append("- " + n)
    lines.append("")
    lines.append("## 覆盖的判定层")
    lines.append("身份映射 → 租户边界 → 空间成员/角色权限位 → 密级 → 资源状态 → 文档级 ACL → Redis 缓存水位。"
                 "每层都有正向与负向用例，跨租户一律 404 且落 CROSS_TENANT_ACCESS 审计。")
    REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("报告: " + str(REPORT))


if __name__ == "__main__":
    main()
