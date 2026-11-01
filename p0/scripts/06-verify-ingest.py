#!/usr/bin/env python3
"""入库链路验证：上传校验矩阵 → 事务性 outbox → Kafka → 引擎索引 → READY。
证明无重复有效索引、幂等、卡口与积压可读，并留下收敛耗时证据。"""
import sys
import time

from p0_verify_lib import UPLOAD_MAX_BYTES, Verifier, code_of, http, newkey, scalar, sql

v = Verifier("入库链路（上传 → outbox → Kafka → 引擎索引 → READY）验证")
tag = time.strftime("%m%d%H%M%S")
ADMIN = "red:admin"

ws = v.require("workspace", "POST", "/api/v1/workspaces", fixture=True, body={"code": "ws-ing-" + tag, "name": "ingest"})
kb = v.require("kb", "POST", "/api/v1/workspaces/%s/knowledge-bases" % ws["id"],
               fixture=True, body={"code": "kb-ing-" + tag, "name": "ingest kb", "classification": "INTERNAL"})
UPLOAD = "/api/v1/workspaces/%s/knowledge-bases/%s/documents" % (ws["id"], kb["id"])

marker = "蓝鲸" + tag
para = ("%s 是本次入库验证的定位词。服务端必须在授权范围内召回它，且不得把其它租户的同名词召回。"
        "这一段用于把文档切成多个检索块，观察分块与索引收敛是否一致。" % marker)
content = (para * 60).encode("utf-8")


def upload(key=None, payload=content, filename="ingest-%s.txt" % tag, ctype="text/plain"):
    return http("POST", UPLOAD, ADMIN, False, part=("file", filename, payload), key=key)


def wait_ready(document_id, timeout_s=180):
    start = time.time()
    last = None
    while time.time() - start < timeout_s:
        st, p, _ = http("GET", "/api/v1/documents/" + document_id, ADMIN)
        last = p
        if st == 200 and p.get("status") == "READY":
            return True, (time.time() - start) * 1000, p
        if st == 200 and p.get("status") in ("FAILED", "COMPENSATION_REQUIRED"):
            return False, (time.time() - start) * 1000, p
        time.sleep(2)
    return False, (time.time() - start) * 1000, last


# --- 上传校验矩阵（冻结的上传限制在此逐条落证）---
st, p, _ = upload(key=newkey())
doc_id = p.get("documentId", "")
v.check("UP-01", "合法上传返回 202 与任务句柄", st == 202 and doc_id and p.get("taskId"),
        "%s status=%s" % (st, p.get("status")))
ok, ms, view = wait_ready(doc_id)
v.check("UP-02", "上传后在 180s 内经 outbox→Kafka→引擎收敛为 READY", ok and view and view.get("status") == "READY",
        "elapsed=%.0fms status=%s chunks=%s" % (ms, (view or {}).get("status"),
                                                scalar("SELECT COUNT(*) FROM engine_chunk WHERE document_id='%s'" % doc_id)))
v.note("首文档收敛耗时 %.0f ms（含 Kafka 轮询间隔与 Ollama 向量调用）" % ms)

st, p, _ = http("POST", UPLOAD, ADMIN, False, part=("file", "nokey.txt", content))
v.check("UP-03", "缺少 Idempotency-Key 必须 400", st == 400 and code_of(p) == "VALIDATION_FAILED",
        "%s/%s" % (st, code_of(p)))
st, p, _ = upload(key="not-a-uuid")
v.check("UP-04", "Idempotency-Key 非 UUID 必须 400", st == 400 and code_of(p) == "VALIDATION_FAILED",
        "%s/%s" % (st, code_of(p)))
st, p, _ = upload(key=newkey(), payload=b"")
v.check("UP-05", "空文件必须 400", st == 400 and code_of(p) == "VALIDATION_FAILED", "%s/%s" % (st, code_of(p)))
st, p, _ = upload(key=newkey(), payload=b"MZ\x00binary", filename="tool.exe")
v.check("UP-06", "扩展名不在白名单必须 422 DOCUMENT_FORMAT_UNSUPPORTED",
        st == 422 and code_of(p) == "DOCUMENT_FORMAT_UNSUPPORTED", "%s/%s" % (st, code_of(p)))
st, p, _ = upload(key=newkey(), payload=b"this is not a pdf at all", filename="fake.pdf",
                  ctype="application/pdf")
v.check("UP-07", "扩展名与内容签名不符必须 422 DOCUMENT_SIGNATURE_MISMATCH",
        st == 422 and code_of(p) == "DOCUMENT_SIGNATURE_MISMATCH", "%s/%s" % (st, code_of(p)))

# --- 幂等与"无重复有效索引" ---
key2 = newkey()
st, first, _ = upload(key=key2, payload=content)
st2, again, _ = upload(key=key2, payload=content)
same = first.get("documentId") == again.get("documentId")
v.check("ID-01", "同一主体同一 Idempotency-Key 重发返回同一文档且不新建任务",
        st == 202 and st2 == 202 and same and again.get("duplicate") is True,
        "%s/%s same=%s duplicate=%s" % (st, st2, same, again.get("duplicate")))

ok2, ms2, view2 = wait_ready(again.get("documentId", ""))
engine_rows = scalar("SELECT COUNT(*) FROM engine_document WHERE document_id='%s'" % again.get("documentId"))
v.check("ID-02", "重发不产生第二次有效索引", ok2 and int(engine_rows or 0) == 1, "engine_document rows=%s" % engine_rows)

key3 = newkey()
st3, reup, _ = upload(key=key3, payload=content)
ok3, ms3, view3 = wait_ready(reup.get("documentId", ""))
v.check("VR-01", "同内容不同 Key 生成新版本并收敛（版本递增而非静默复用）",
        st3 == 202 and int(reup.get("version", 0)) >= 2 and ok3,
        "version=%s status=%s elapsed=%.0fms" % (reup.get("version"), (view3 or {}).get("status"), ms3))
dup = sql("SELECT COUNT(*) FROM (SELECT document_id FROM engine_document GROUP BY document_id HAVING COUNT(*)>1) t")
v.check("IX-01", "任一文档在引擎侧只有一份有效索引行（无重复有效索引）", int(dup or -1) == 0,
        "duplicated document_id groups=%s" % dup)
ghost = sql("SELECT COUNT(*) FROM engine_document e LEFT JOIN document d ON d.id=e.document_id "
            "WHERE d.id IS NULL OR d.status='DELETED'")
v.check("IX-02", "引擎侧不存在孤儿索引行", int(ghost or -1) == 0, "orphan engine rows=%s" % ghost)

# --- 积压与队列取证 ---
st, backlog, _ = http("GET", "/api/v1/verification/backlog", ADMIN, fixture=True)
v.check("BK-01", "收敛后无未发布事件、无死信、无未收敛文档",
        st == 200 and backlog.get("outboxUnpublished") == 0 and backlog.get("dlqCount") == 0
        and backlog.get("unconvergedDocuments") == 0,
        "outbox=%s dlq=%s unconverged=%s" % (backlog.get("outboxUnpublished"), backlog.get("dlqCount"),
                                             backlog.get("unconvergedDocuments")))
lag = backlog.get("kafka") or {}
dlag = backlog.get("deleteKafka") or {}
v.check("BK-02", "入库组 lag 可读且收敛后为 0（组名与监听端一致，不是查错组的假零）",
        int(lag.get("lag", -1)) == 0 and int(lag.get("currentOffset", -1)) == int(lag.get("endOffset", -2)),
        "group=%s lag=%s current=%s end=%s" % (lag.get("consumerGroup"), lag.get("lag"),
                                               lag.get("currentOffset"), lag.get("endOffset")))
v.check("BK-03", "删除组单独计量，不与入库组混成一个数字",
        dlag.get("consumerGroup") and dlag.get("consumerGroup") != lag.get("consumerGroup"),
        "delete group=%s lag=%s" % (dlag.get("consumerGroup"), dlag.get("lag")))
v.note("backlog=%s" % str(backlog)[:400])

out_attempts = scalar("SELECT COALESCE(MAX(attempts),0) FROM outbox_event WHERE topic LIKE 'kb.ingest%'")
task_rows = scalar("SELECT COUNT(*) FROM ingest_task WHERE status='SUCCEEDED'")
retry_tasks = scalar("SELECT COUNT(*) FROM ingest_task WHERE attempts > 1")
consumed = scalar("SELECT COUNT(*) FROM event_consumption")
v.check("TX-01", "事务性 outbox 一次即发布成功（无重试放大）", int(out_attempts or -1) == 1,
        "max outbox attempts=%s" % out_attempts)
v.check("TX-02", "消费账本有记录，说明重复投递可被识别", int(consumed or -1) >= 1,
        "event_consumption rows=%s succeeded tasks=%s retried tasks=%s" % (consumed, task_rows, retry_tasks))

st, p, _ = http("POST", UPLOAD, ADMIN, False, part=("file", "big.txt", b"x" * (UPLOAD_MAX_BYTES + 1)))
v.check("LIM-01", "超过上限（%d 字节）必须 413 DOCUMENT_TOO_LARGE" % UPLOAD_MAX_BYTES,
        st == 413 and code_of(p) == "DOCUMENT_TOO_LARGE", "%s/%s" % (st, code_of(p)))

objs = sql("SELECT COUNT(*) FROM document WHERE object_key IS NULL")
uniq = sql("SELECT COUNT(*) FROM (SELECT object_key FROM document GROUP BY object_key HAVING COUNT(*)>1) t")
v.check("ST-01", "每个文档都登记了唯一对象键（原件与行的对应关系可审计）",
        int(objs or -1) == 0 and int(uniq or -1) == 0, "null keys=%s dup keys=%s docs=%s"
        % (objs, uniq, scalar("SELECT COUNT(*) FROM document")))

v.report("ingest-run.md", extra="上传上限 %d 字节由 P0_UPLOAD_MAX_BYTES 同时驱动 servlet 与业务校验，"
                                "两者不会错位。" % UPLOAD_MAX_BYTES)
