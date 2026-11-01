#!/usr/bin/env python3
"""契约一致性校验：Kafka JSON Schema、样例载荷、OpenAPI 与 Java 事件记录字段必须互相对得上。

冻结契约靠人眼核对是守不住的，所以把"谁改了字段忘了改文档"变成一条可重复执行的检查。
退出码非 0 表示契约漂移，P1 交接前必须为 0。
"""
import json
import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONTRACTS = os.path.join(ROOT, "contracts")
KAFKA = os.path.join(CONTRACTS, "kafka")
SAMPLES = os.path.join(KAFKA, "samples")
EVENTS_JAVA = os.path.join(
    ROOT, "portal-poc", "src", "main", "java", "com", "acme", "kb", "p0", "pipeline", "Events.java")

ERRORS = []
WARNINGS = []

SCHEMA_FOR_RECORD = {
    "IngestRequested": "document-ingest-requested.v1.schema.json",
    "IngestRetry": "document-ingest-retry.v1.schema.json",
    "DeleteRequested": "document-delete-requested.v1.schema.json",
    "DlqEnvelope": "dlq-envelope.v1.schema.json",
}

PRIMITIVE = {
    "string": str,
    "integer": int,
    "number": (int, float),
    "boolean": bool,
    "object": dict,
    "array": list,
}


def err(msg):
    ERRORS.append(msg)


def warn(msg):
    WARNINGS.append(msg)


def load_json(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def type_of(spec):
    t = spec.get("type")
    if isinstance(t, list):
        return tuple(PRIMITIVE[x] for x in t if x in PRIMITIVE)
    return PRIMITIVE.get(t)


def check_sample(sample_path, schema):
    name = os.path.basename(sample_path)
    data = load_json(sample_path)
    props = schema.get("properties", {})
    required = set(schema.get("required", []))
    missing = sorted(required - set(data))
    if missing:
        err("%s 缺少必填字段 %s" % (name, ", ".join(missing)))
    extra = sorted(set(data) - set(props))
    if extra and schema.get("additionalProperties") is False:
        err("%s 含 schema 未声明的字段 %s（additionalProperties:false）" % (name, ", ".join(extra)))
    for key, value in data.items():
        spec = props.get(key)
        if spec is None:
            continue
        expected = type_of(spec)
        if expected is not None and not isinstance(value, expected):
            # JSON Schema 里 integer 也是 number 的子集，Python 的 bool 是 int 的子集
            if not (expected is float and isinstance(value, (int, float)) and not isinstance(value, bool)):
                err("%s.%s 类型应为 %s，实际 %r" % (name, key, spec.get("type"), value))
        if "const" in spec and value != spec["const"]:
            err("%s.%s 应为常量 %r，实际 %r" % (name, key, spec["const"], value))
        if "enum" in spec and value not in spec["enum"]:
            err("%s.%s=%r 不在枚举 %s 内" % (name, key, value, spec["enum"]))
        if isinstance(value, str):
            if "maxLength" in spec and len(value) > spec["maxLength"]:
                err("%s.%s 长度 %d 超过上限 %d" % (name, key, len(value), spec["maxLength"]))
            if "pattern" in spec and not re.match(spec["pattern"], value):
                err("%s.%s=%r 不匹配 %s" % (name, key, value, spec["pattern"]))
            if spec.get("format") == "date-time" and not re.match(
                    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$", value):
                err("%s.%s=%r 不是 UTC ISO-8601" % (name, key, value))
            if spec.get("format") == "uuid" and not re.match(
                    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", value):
                err("%s.%s=%r 不是 uuid" % (name, key, value))
        if isinstance(value, int) and not isinstance(value, bool):
            if "minimum" in spec and value < spec["minimum"]:
                err("%s.%s=%d 小于最小值 %d" % (name, key, value, spec["minimum"]))
            if "maximum" in spec and value > spec["maximum"]:
                err("%s.%s=%d 大于最大值 %d" % (name, key, value, spec["maximum"]))
    print("  样例 %s <- %s" % (name, os.path.basename(schema_path_of(sample_path))))


def schema_path_of(sample_path):
    stem = os.path.basename(sample_path).split(".sample.")[0]
    path = os.path.join(KAFKA, stem + ".v1.schema.json")
    if not os.path.isfile(path):
        raise AssertionError("找不到 %s 对应的 schema：%s" % (sample_path, path))
    return path


def java_record_fields():
    """解析 Events.java 里的 record 声明，取出字段名（类型注解无关）。"""
    with open(EVENTS_JAVA, encoding="utf-8") as handle:
        source = handle.read()
    out = {}
    for match in re.finditer(r"public record (\w+)\(([^)]*)\)", source, re.S):
        record, arg_list = match.group(1), match.group(2)
        names = []
        for part in arg_list.split(","):
            tokens = part.split()
            if len(tokens) >= 2 and re.fullmatch(r"[a-zA-Z_]\w*", tokens[-1]):
                names.append(tokens[-1])
        out[record] = names
    return out


def check_java_vs_schema():
    records = java_record_fields()
    for record, schema_file in SCHEMA_FOR_RECORD.items():
        path = os.path.join(KAFKA, schema_file)
        schema = load_json(path)
        props = set(schema.get("properties", {}))
        required = set(schema.get("required", []))
        fields = set(records.get(record, []))
        if not fields:
            err("Events.java 未找到 record %s" % record)
            continue
        emitted_extra = sorted(fields - props)
        if emitted_extra and schema.get("additionalProperties") is False:
            err("%s 发出 schema 未声明的字段 %s" % (record, ", ".join(emitted_extra)))
        missing_required = sorted(required - fields)
        if missing_required:
            err("%s 未发出必填字段 %s" % (record, ", ".join(missing_required)))
        missing_optional = sorted(props - fields)
        if missing_optional:
            warn("%s 未发出可选字段 %s" % (record, ", ".join(missing_optional)))
        print("  %s <-> %s：%d 个发出字段全部合法" % (record, schema_file, len(fields)))


def check_openapi():
    import yaml

    path = os.path.join(CONTRACTS, "openapi.yaml")
    with open(path, encoding="utf-8") as handle:
        spec = yaml.safe_load(handle)
    if spec.get("openapi", "").split(".")[0] != "3":
        err("openapi.yaml 版本异常: %r" % spec.get("openapi"))
    paths = spec.get("paths", {})
    ops = 0
    for url, item in paths.items():
        if not url.startswith("/"):
            err("openapi.yaml 路径必须以 / 开头: %s" % url)
        for verb, op in (item or {}).items():
            if verb in ("get", "post", "put", "delete", "patch", "head"):
                ops += 1
                if not op.get("operationId"):
                    err("%s %s 缺少 operationId" % (verb.upper(), url))
                for code, resp in (op.get("responses") or {}).items():
                    if "application/json" in (resp.get("content") or {}) and "schema" not in (
                            resp["content"]["application/json"]):
                        err("%s %s %s 响应缺 schema" % (verb.upper(), url, code))
    schemas = (spec.get("components") or {}).get("schemas") or {}
    if "ApiError" not in schemas:
        err("openapi.yaml 缺少 ApiError 组件")
    else:
        codes = set((schemas["ApiError"]["properties"]["code"].get("enum") or []))
        java_codes = read_java_error_codes()
        missing = sorted(java_codes - codes)
        if missing:
            err("ErrorCode 未在 openapi 枚举中: %s" % ", ".join(missing))
        ghost = sorted(codes - java_codes)
        if ghost:
            err("openapi 枚举了不存在的 ErrorCode: %s" % ", ".join(ghost))
    print("  openapi.yaml：%d 条路径 / %d 个操作 / %d 个组件 schema / ApiError %d 个错误码"
          % (len(paths), ops, len(schemas), len(schemas.get("ApiError", {}).get("properties", {})
                                           .get("code", {}).get("enum", []))))


def read_java_error_codes():
    path = os.path.join(ROOT, "portal-poc", "src", "main", "java", "com", "acme", "kb", "p0",
                        "common", "ErrorCode.java")
    with open(path, encoding="utf-8") as handle:
        source = handle.read()
    body = source.split("public enum ErrorCode", 1)[1]
    body = body.split("private final HttpStatus", 1)[0]
    return set(re.findall(r"^\s*([A-Z][A-Z0-9_]+)\(", body, re.M))


def main():
    print("== Kafka schema 与样例 ==")
    for sample in sorted(os.listdir(SAMPLES)):
        path = os.path.join(SAMPLES, sample)
        schema = load_json(schema_path_of(path))
        check_sample(path, schema)
    print("== Java 事件字段 <-> schema ==")
    check_java_vs_schema()
    print("== OpenAPI ==")
    check_openapi()
    print()
    for item in WARNINGS:
        print("WARN  %s" % item)
    for item in ERRORS:
        print("ERROR %s" % item)
    print("\n%d error / %d warning" % (len(ERRORS), len(WARNINGS)))
    return 1 if ERRORS else 0


if __name__ == "__main__":
    sys.exit(main())
