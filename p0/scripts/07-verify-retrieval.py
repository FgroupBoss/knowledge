#!/usr/bin/env python3
"""检索隔离与引用代理验证：服务端作用域交集、逐条复核、密级/文档 ACL 在召回前剔除、
引用预览只走授权代理，SSE 事件序列与序号可核对。
外泄判定用语义无关的随机标记词（只存在于正文，绝不出现在提问里），避免"问题被模型复述"造成的误判或漏判。"""
import json
import time

from p0_verify_lib import Verifier, code_of, http, newkey, scalar, sse, sql

v = Verifier("检索隔离、引用授权代理与 SSE 验证")
tag = time.strftime("%m%d%H%M%S")
ADMIN = "red:admin"

# 标记词：LX=正文独占标记；FACT 是答案里必须复述的关键事实
LX_RED, LX_BLUE, LX_SECRET, LX_ACL = "LX红" + tag, "LX蓝" + tag, "LX密" + tag, "LX垒" + tag
FACT_RED = "03:15"

red_doc = ("子系统说明：%s 登记为 %s。它的维护窗口固定在每周三 03:15 开始，历时 45 分钟，"
           "由平台组当班人员执行，值班表在运维手册第七节。" % (LX_RED, LX_RED)) * 4
blue_doc = ("财务纪要：%s 登记为 %s。它的预算审批由财务三处负责，额度十二万元，"
            "只有本租户可见，任何外部主体都不应读到这一段。" % (LX_BLUE, LX_BLUE)) * 4
secret_doc = ("涉密安排：%s 登记为 %s。它的交接地点在西门三层，仅限 SECRET 及以上密级人员知悉。"
              % (LX_SECRET, LX_SECRET)) * 4
acl_doc = ("专项记录：%s 登记为 %s。它的复核结论是暂缓上线，只有被授予该文档权限的人可以读到这条结论。"
           % (LX_ACL, LX_ACL)) * 4

Q_WINDOW = "这套登记的维护窗口安排在星期几几点？"
Q_BUDGET = "这项登记的预算审批由哪个部门负责，额度是多少？"
Q_PLACE = "这项登记的交接地点在哪里？"
Q_CONCLUSION = "这项登记的复核结论是什么？"


def make_doc(subject, ws_id, kb_id, filename, text):
    st, p, _ = http("POST", "/api/v1/workspaces/%s/knowledge-bases/%s/documents" % (ws_id, kb_id),
                    subject, part=("file", filename, text.encode("utf-8")), key=newkey())
    if st != 202:
        v.note("upload %s 未受理：%s %s" % (filename, st, p))
        return None
    return p["documentId"]


def converge(document_ids, timeout_s=240):
    start = time.time()
    while time.time() - start < timeout_s:
        rows = scalar("SELECT COUNT(*) FROM document WHERE status='READY' AND id IN (%s)"
                      % ",".join("'%s'" % d for d in document_ids))
        if int(rows or 0) == len(document_ids):
            return True, (time.time() - start) * 1000
        time.sleep(2)
    return False, (time.time() - start) * 1000


def leaked(payload, tokens):
    blob = json.dumps(payload, ensure_ascii=False)
    return [t for t in tokens if t in blob]


# ---------- 夹具 ----------
for tenant, user, clearance in [("blue", "alice", "TOP_SECRET"), ("red", "low", "INTERNAL"),
                                ("red", "stranger2", "TOP_SECRET")]:
    v.require("principal " + user, "POST", "/api/v1/fixture/principals", fixture=True,
              body={"tenantCode": tenant, "userCode": user, "displayName": user, "clearance": clearance})

ws_r = v.require("red ws", "POST", "/api/v1/workspaces", fixture=True,
                 body={"code": "ws-rt-r-" + tag, "name": "retrieval red"})
kb_r = v.require("red kb", "POST", "/api/v1/workspaces/%s/knowledge-bases" % ws_r["id"], fixture=True,
                 body={"code": "kb-rt-r-" + tag, "name": "red kb", "classification": "INTERNAL"})
kb_s = v.require("secret kb", "POST", "/api/v1/workspaces/%s/knowledge-bases" % ws_r["id"], fixture=True,
                 body={"code": "kb-rt-s-" + tag, "name": "secret kb", "classification": "SECRET"})
ws_b = v.require("blue ws", "POST", "/api/v1/workspaces", subject="blue:alice", fixture=True,
                 body={"code": "ws-rt-b-" + tag, "name": "retrieval blue"})
kb_b = v.require("blue kb", "POST", "/api/v1/workspaces/%s/knowledge-bases" % ws_b["id"],
                 subject="blue:alice", fixture=True,
                 body={"code": "kb-rt-b-" + tag, "name": "blue kb", "classification": "INTERNAL"})
v.require("low member", "POST", "/api/v1/workspaces/%s/members" % ws_r["id"], fixture=True,
          body={"userCode": "low", "role": "VIEWER"})

red_id = make_doc(ADMIN, ws_r["id"], kb_r["id"], "red.txt", red_doc)
blue_id = make_doc("blue:alice", ws_b["id"], kb_b["id"], "blue.txt", blue_doc)
secret_id = make_doc(ADMIN, ws_r["id"], kb_s["id"], "secret.txt", secret_doc)
acl_id = make_doc(ADMIN, ws_r["id"], kb_r["id"], "acl.txt", acl_doc)
docs = [d for d in (red_id, blue_id, secret_id, acl_id) if d]
ok, ms = converge(docs)
v.check("SEED-01", "四篇语料全部收敛为 READY（红两篇、蓝一篇、涉密一篇）", ok and len(docs) == 4,
        "docs=%d elapsed=%.0fms" % (len(docs), ms))
v.require("doc acl", "POST", "/api/v1/documents/%s/acl" % acl_id, fixture=True,
          body={"userCode": "admin", "grant": "READ"})

chat_calls = []


def chat(subject, kb_ids, question):
    st, p, _ = http("POST", "/api/v1/knowledge-bases/%s/chat" % kb_ids[0], subject,
                    body={"question": question, "conversationId": "cv-" + tag,
                          "knowledgeBaseIds": kb_ids, "topK": 8})
    chat_calls.append((subject, st, p, question))
    return st, p


# ---------- 正向召回：有权者得到答案与引用 ----------
st, ans = chat(ADMIN, [kb_r["id"]], Q_WINDOW)
cited = {c.get("documentId") for c in ans.get("citations", [])}
v.check("CH-01", "授权提问得到 ANSWERED，引用只落在本租户有权文档",
        st == 200 and ans.get("status") == "ANSWERED" and red_id in cited
        and not cited.intersection({blue_id, secret_id}),
        "status=%s citations=%d docs=%s" % (ans.get("status"), len(cited), cited))
v.check("CH-02", "答案复述了被引用片段中的关键事实（03:15）", FACT_RED in (ans.get("answer") or ""),
        (ans.get("answer") or "")[:70].replace("\n", " "))
v.check("CH-03", "逐条复核没有把有效索引误判为过期（droppedByStaleVersion=0）",
        (ans.get("aclStats") or {}).get("droppedByStaleVersion") == 0
        and (ans.get("aclStats") or {}).get("promptChunks", 0) >= 1,
        "aclStats=%s" % ans.get("aclStats"))
lat = ans.get("latency") or {}
v.note("单请求耗时 authorization=%sms retrieval=%sms firstToken=%s total=%sms"
       % (lat.get("authorizationMs"), lat.get("retrievalMs"), lat.get("firstTokenMs"), lat.get("totalMs")))

# ---------- 跨租户隔离 ----------
st, blue_ok = chat("blue:alice", [kb_b["id"]], Q_BUDGET)
v.check("XT-00", "他租户在自己的 KB 上能正常问答（证明后续负向不是整体失效）",
        blue_ok.get("status") == "ANSWERED" and LX_BLUE in json.dumps(blue_ok, ensure_ascii=False),
        "status=%s" % blue_ok.get("status"))
st, blue_q = chat("blue:alice", [kb_b["id"]], Q_WINDOW)
v.check("XT-01", "他租户问红队专有事实：不得召回他租户正文",
        not leaked(blue_q, [LX_RED, FACT_RED]), "status=%s leaks=%s"
        % (blue_q.get("status"), leaked(blue_q, [LX_RED, FACT_RED])))
st, cross, _ = http("POST", "/api/v1/knowledge-bases/%s/chat" % kb_r["id"], "blue:alice",
                 body={"question": Q_WINDOW, "knowledgeBaseIds": [kb_r["id"]], "topK": 8})
chat_calls.append(("blue:alice", st, cross, Q_WINDOW))
v.check("XT-02", "直接指名他租户 KB：入口即 404，不进入检索",
        st == 404 and not leaked(cross, [LX_RED, FACT_RED]), "%s/%s" % (st, code_of(cross)))

# ---------- 作用域交集（越界项静默丢弃并计数）----------
st, scoped = chat("red:low", [kb_r["id"], kb_b["id"]], Q_BUDGET)
stats = scoped.get("aclStats") or {}
v.check("SC-01", "客户端提交的越界 KB 只作提示：授权数小于请求数且越界项被记录",
        stats.get("requestedKbCount") == 2 and stats.get("authorizedKbCount") == 1
        and len(stats.get("droppedScopes") or []) == 1,
        "requested=%s authorized=%s dropped=%s" % (stats.get("requestedKbCount"),
                                                   stats.get("authorizedKbCount"), stats.get("droppedScopes")))
v.check("SC-02", "越界请求不得把他租户正文带回", not leaked(scoped, [LX_BLUE, "十二万元"]),
        "status=%s" % scoped.get("status"))

# ---------- 密级 ----------
st, sec_entry, _ = http("POST", "/api/v1/knowledge-bases/%s/chat" % kb_s["id"], "red:low",
                     body={"question": Q_PLACE, "knowledgeBaseIds": [kb_s["id"]], "topK": 8})
chat_calls.append(("red:low", st, sec_entry, Q_PLACE))
v.check("CL-01", "密级不足时入口即 403 CLEARANCE_TOO_LOW，不进入检索",
        st == 403 and code_of(sec_entry) == "CLEARANCE_TOO_LOW" and not leaked(sec_entry, [LX_SECRET]),
        "%s/%s" % (st, code_of(sec_entry)))
st, sec_mix = chat("red:low", [kb_r["id"], kb_s["id"]], Q_PLACE)
mix_stats = sec_mix.get("aclStats") or {}
v.check("CL-02", "混合范围里涉密 KB 被剔除并计数，正文零泄露",
        mix_stats.get("authorizedKbCount") == 1 and len(mix_stats.get("droppedScopes") or []) == 1
        and not leaked(sec_mix, [LX_SECRET, "西门三层"]),
        "authorized=%s dropped=%s" % (mix_stats.get("authorizedKbCount"), mix_stats.get("droppedScopes")))

# ---------- 文档级 ACL ----------
st, aclq = chat("red:low", [kb_r["id"]], Q_CONCLUSION)
v.check("DA-01", "同一 KB 内未授予文档权限的正文不得出现在检索结果里",
        not leaked(aclq, [LX_ACL, "暂缓上线"]), "status=%s leaks=%s"
        % (aclq.get("status"), leaked(aclq, [LX_ACL, "暂缓上线"])))
st, ctrl = chat(ADMIN, [kb_r["id"]], Q_CONCLUSION)
v.check("DA-02", "对照：有权主体在同 KB 内能召回被隔离的正文（证明上一条不是检索整体失效）",
        ctrl.get("status") == "ANSWERED" and acl_id in {c.get("documentId") for c in ctrl.get("citations", [])},
        "status=%s cited=%s" % (ctrl.get("status"), {c.get("documentId") for c in ctrl.get("citations", [])}))

# ---------- 引用预览：授权代理 ----------
citations = ans.get("citations") or []
if citations:
    cid = citations[0]["citationId"]
    st, prev, hdr = http("GET", "/api/v1/citations/%s/preview" % cid, ADMIN)
    body = json.dumps(prev, ensure_ascii=False)
    v.check("CP-01", "授权引用预览 200、强制 no-store、正文按字符偏移切出",
            st == 200 and "no-store" in (hdr.get("Cache-Control") or "") and "03:15" in body,
            "%s cache=%s" % (st, hdr.get("Cache-Control")))
    v.check("CP-02", "预览不含预签名 URL 或对象存储地址",
            "X-Amz" not in body and "19000" not in body and "object_key" not in body, body[:50])
    st, _, _ = http("GET", "/api/v1/citations/%s/preview" % cid, "blue:alice")
    v.check("CP-03", "他租户主体取同一引用预览 404（不承认其存在）", st == 404, str(st))
    st, denied, _ = http("GET", "/api/v1/citations/%s/preview" % cid, "red:stranger2")
    v.check("CP-04", "同租户但非该空间成员者取预览被拒且不返回正文",
            st in (403, 404) and "03:15" not in json.dumps(denied, ensure_ascii=False),
            "%s/%s" % (st, code_of(denied)))
    # 对照：同空间成员（未被文档 ACL 排除的文档）应能预览，证明上一条不是预览整体失效
    st, allowed, _ = http("GET", "/api/v1/citations/%s/preview" % cid, "red:low")
    v.check("CP-04b", "同空间成员预览本有权文档正常放行",
            st == 200 and "03:15" in json.dumps(allowed, ensure_ascii=False), str(st))
    acl_citation = next((c.get("citationId") for c in (ctrl.get("citations") or [])
                         if c.get("documentId") == acl_id), None)
    if acl_citation:
        st, denied2, _ = http("GET", "/api/v1/citations/%s/preview" % acl_citation, "red:low")
        v.check("CP-05", "空间成员但未被授予该文档权限者，预览文档 ACL 正文被拒",
                st in (403, 404) and LX_ACL not in json.dumps(denied2, ensure_ascii=False),
                "%s/%s" % (st, code_of(denied2)))
else:
    v.check("CP-01", "存在可预览的引用", False, "第一条回答没有引用")

# ---------- SSE 事件序列 ----------
st, events, shdr = sse("/api/v1/knowledge-bases/%s/chat/stream" % kb_r["id"], ADMIN,
                       {"question": Q_WINDOW, "conversationId": "sse-" + tag,
                        "knowledgeBaseIds": [kb_r["id"]], "topK": 8})
types = [e.get("event", "") for e in events]
shape = "".join({"meta": "m", "sources": "s", "token": "t", "done": "d", "error": "e"}.get(t, "?")
                for t in types)
v.check("SSE-01", "事件顺序 meta → sources → token+ → done",
        st == 200 and shape.startswith("ms") and shape.endswith("d") and "t" in shape,
        "content-type=%s shape=%s" % (shdr.get("Content-Type"), shape[:60]))
ids = [e.get("id") for e in events]
seqs = [int(i) for i in ids if (i or "").isdigit()]
v.check("SSE-02", "每条事件带 id(seq) 且严格递增，客户端可判断漏事件",
        len(seqs) == len(events) and seqs == sorted(seqs) and len(set(seqs)) == len(seqs),
        "events=%d seq=%s→%s" % (len(events), seqs[:1], seqs[-1:]))
stream_text = "".join(str(e.get("data", "")) for e in events)
forbidden = [t for t in (LX_BLUE, LX_SECRET) if t in stream_text]
v.check("SSE-03", "流式回答不含他租户与涉密正文（本会话有权的文档 ACL 标记不算外泄）",
        not forbidden, "stream chars=%d forbidden=%s" % (len(stream_text), forbidden))
st, ev2, _ = sse("/api/v1/knowledge-bases/%s/chat/stream" % kb_r["id"], "red:low",
                 {"question": Q_CONCLUSION, "conversationId": "sse2-" + tag,
                  "knowledgeBaseIds": [kb_r["id"]], "topK": 8})
text2 = "".join(str(e.get("data", "")) for e in ev2)
v.check("SSE-04", "无权问答在流里以拒答收尾，不出现文档 ACL 正文",
        LX_ACL not in text2 and any(e.get("event") == "done" for e in ev2),
        "events=%s" % [e.get("event") for e in ev2])

# ---------- 硬门：按主体分组扫描正文标记 ----------
red_side = [p for sub, _, p, _ in chat_calls if sub == ADMIN]
low_side = [p for sub, _, p, _ in chat_calls if sub == "red:low"]
blue_side = [p for sub, _, p, _ in chat_calls if sub == "blue:alice"]
v.check("GATE-01", "本租户有权者的响应不含他租户标记", not leaked(red_side, [LX_BLUE]),
        "red-admin responses=%d" % len(red_side))
v.check("GATE-02", "同租户低密级/无文档权限者的响应不含涉密与文档 ACL 标记",
        not leaked(low_side, [LX_BLUE, LX_SECRET, LX_ACL]), "low responses=%d" % len(low_side))
v.check("GATE-03", "他租户主体的响应不含任何红队专有标记",
        not leaked(blue_side, [LX_RED, LX_SECRET, LX_ACL, FACT_RED]), "blue responses=%d" % len(blue_side))
xcite = sql("SELECT COUNT(*) FROM citation c JOIN chat_request r ON r.id=c.request_id "
            "JOIN app_user u ON u.id=r.actor_id WHERE c.tenant_id <> u.tenant_id")
v.check("GATE-04", "落库引用中没有一条指向引用者所属租户之外的文档", int(xcite or -1) == 0,
        "cross-tenant citation rows=%s" % xcite)
adeny = sql("SELECT COUNT(*) FROM citation c JOIN document d ON d.id=c.document_id "
            "JOIN chat_request r ON r.id=c.request_id "
            "WHERE d.acl_enforced=1 AND NOT EXISTS (SELECT 1 FROM document_acl a "
            "WHERE a.document_id=d.id AND a.subject_type='USER' AND a.subject_id=r.actor_id)")
v.check("GATE-05", "文档级 ACL 生效后没有一条越权引用落库", int(adeny or -1) == 0,
        "acl-violating citation rows=%s" % adeny)
foreign = sql("SELECT COUNT(*) FROM chat_request r JOIN knowledge_base k ON k.id=r.kb_id "
              "JOIN app_user u ON u.id=r.actor_id WHERE k.tenant_id <> u.tenant_id")
v.check("GATE-06", "没有任何一条 Chat 记录把主体指向他租户的知识库", int(foreign or -1) == 0,
        "cross-tenant chat_request rows=%s" % foreign)

v.report("retrieval-acl.md", extra="外泄判定使用只存在于正文的随机标记词，对完整响应体扫描；"
                                   "同时保留有权对照用例，避免把「检索整体失效」误记成「隔离成功」。")
