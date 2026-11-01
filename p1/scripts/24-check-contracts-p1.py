#!/usr/bin/env python3
"""P1/P2 契约一致性校验（pilot 侧 `com.acme.kb.p1`）。

P0 的 `p0/scripts/04-check-contracts.py` 只看 P0 的 `Events` / schema / OpenAPI，
改 P1 的错误码、配置三层同名、Wiki 状态机边表**不会报警**——这正是 backlog #20/#22 的成因。
本脚本补上 pilot 侧的机器核对，**不改动 04 号脚本的任何判定语义**（AGENTS.md 硬性规则 4）。

七组检查（任一组出 ERROR 即退出码 1）：
  1. 错误码三方一致 + 可产生性 + 列语义（HTTP / 分类）
  2. 配置三层同名：`KB_*` ↔ Spring 属性 ↔ `@DefaultValue` ↔ compose ↔ `*_FILE` 映射
  3. OpenAPI `paths:` ↔ Controller 注解（双向）
  4. `x-error-codes` ⊆ `ApiError.code` 枚举，且枚举里每个码都被至少一个操作声明
  5. Wiki 状态机：`state-machine-wiki.md` 边表 ↔ `WikiStateMachine.EDGES`（逐条四元组）
  6. Kafka：schema ↔ 样例 ↔ `Events`/`AuditWriter` 字段 ↔ README 主题登记
  7. 审计动作命名与可产生性（点小写，W1 声明的动作必须能在代码里 grep 到）

用法：`python p1/scripts/24-check-contracts-p1.py`
"""
import json
import os
import re
import sys

import yaml

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
P1 = os.path.join(ROOT, "p1")
PORTAL = os.path.join(P1, "portal")
MAIN_JAVA = os.path.join(PORTAL, "src", "main", "java")
YML = os.path.join(PORTAL, "src", "main", "resources", "application.yml")
CATALOG = os.path.join(P1, "contracts", "config-catalog-p1.md")
CODES_MD = os.path.join(P1, "contracts", "error-codes.md")
SM_MD = os.path.join(P1, "contracts", "state-machine-wiki.md")
OAS = os.path.join(P1, "contracts", "openapi-1.2.yaml")
KAFKA = os.path.join(ROOT, "p0", "contracts", "kafka")
KAFKA_README = os.path.join(KAFKA, "README.md")
SAMPLES = os.path.join(KAFKA, "samples")
COMPOSE = os.path.join(P1, "compose", "docker-compose.yml")

ERROR_CODE_JAVA = os.path.join(MAIN_JAVA, "com", "acme", "kb", "p1", "common", "ErrorCode.java")
KB_PROPS_JAVA = os.path.join(MAIN_JAVA, "com", "acme", "kb", "p1", "config", "KbProperties.java")
SECRETS_JAVA = os.path.join(MAIN_JAVA, "com", "acme", "kb", "p1", "config",
                            "SecretsEnvironmentPostProcessor.java")
P1_EVENTS_JAVA = os.path.join(MAIN_JAVA, "com", "acme", "kb", "p1", "pipeline", "Events.java")
AUDIT_WRITER_JAVA = os.path.join(MAIN_JAVA, "com", "acme", "kb", "p1", "audit", "AuditWriter.java")
WIKI_SM_JAVA = os.path.join(MAIN_JAVA, "com", "acme", "kb", "p1", "wiki", "WikiStateMachine.java")

STATUS_INT = {
    "BAD_REQUEST": 400, "UNAUTHORIZED": 401, "FORBIDDEN": 403, "NOT_FOUND": 404,
    "METHOD_NOT_ALLOWED": 405, "CONFLICT": 409, "GONE": 410, "PAYLOAD_TOO_LARGE": 413,
    "UNPROCESSABLE_ENTITY": 422, "TOO_MANY_REQUESTS": 429, "INTERNAL_SERVER_ERROR": 500,
    "BAD_GATEWAY": 502, "SERVICE_UNAVAILABLE": 503,
}

# 只在审计里出现、从不作为 HTTP 响应体的码（AGENTS.md §6：跨租户降级为 404）。
AUDIT_ONLY_CODES = {"CROSS_TENANT_ACCESS"}
# 设计文档 §7 与 P1 附录 B 里目标但未实现的路径：出现在 info.description 的「未声明」块，
# 一旦有人把它们塞进 paths: 而门户没有对应 Controller，第 3 组检查就会报 ERROR。
HTTP_METHODS = ("get", "post", "put", "delete", "patch", "head", "options")

ERRORS = []
WARNINGS = []
CHECK_COUNT = {}


def err(msg):
    ERRORS.append(msg)


def warn(msg):
    WARNINGS.append(msg)


def bump(group, n=1):
    CHECK_COUNT[group] = CHECK_COUNT.get(group, 0) + n


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def load_json(path):
    return json.loads(read(path))


def cells(line):
    parts = [p.strip() for p in line.strip().strip("|").split("|")]
    return parts


def table_rows(text, section):
    """取某个 `##`/`###` 小节里第一张表格的数据行（跳过表头与分隔行）。"""
    lines = text.splitlines()
    out, inside, header_done = [], False, False
    for line in lines:
        if line.startswith("#"):
            if inside:
                break
            inside = section in line
            continue
        if not inside:
            continue
        if not line.strip().startswith("|"):
            continue
        if re.fullmatch(r"\|[\s:\-|]+\|", line.strip()):
            header_done = True
            continue
        if not header_done:
            continue
        out.append(cells(line))
    return out


def bare(cell):
    return cell.strip("` ").strip()


def main_java_sources():
    for dirpath, _dirs, files in os.walk(MAIN_JAVA):
        for name in files:
            if name.endswith(".java"):
                yield os.path.join(dirpath, name)


# ---------------------------------------------------------------- 1. 错误码

def parse_java_error_codes():
    source = read(ERROR_CODE_JAVA)
    body = source.split("public enum ErrorCode", 1)[1]
    body = body.split("private final HttpStatus", 1)[0]
    codes = {}
    for name, status in re.findall(r"^\s*([A-Z][A-Z0-9_]+)\(HttpStatus\.(\w+)\)", body, re.M):
        codes[name] = status
    members = {}
    for method in ("permanent", "transientFailure"):
        chunk = source.split("public boolean %s()" % method, 1)
        found = set()
        if len(chunk) > 1:
            tail = chunk[1].split("\n    }", 1)[0]
            found = set(re.findall(r"this\s*==\s*([A-Z][A-Z0-9_]+)", tail))
        members[method] = found
    return codes, members


def check_error_codes():
    compare_codes(load_codes())


def load_codes():
    java_codes, members = parse_java_error_codes()
    md = {}
    for row in table_rows(read(CODES_MD), "## 码表"):
        if len(row) < 4:
            continue
        code = bare(row[0])
        if not re.fullmatch(r"[A-Z][A-Z0-9_]+", code):
            err("error-codes.md 码表第一列不是错误码：%r" % row[0])
            continue
        md[code] = {"http": row[1].strip(), "cls": bare(row[3])}
    spec = load_yaml(OAS)
    return {"java": java_codes, "members": members, "md": md,
            "enum": set(spec["components"]["schemas"]["ApiError"]["properties"]["code"]["enum"]),
            "hits": count_code_hits(java_codes)}


def count_code_hits(java_codes):
    hits = {code: 0 for code in java_codes}
    for path in main_java_sources():
        if os.path.abspath(path) == os.path.abspath(ERROR_CODE_JAVA):
            continue
        text = read(path)
        for code in java_codes:
            hits[code] += len(re.findall(r"\bErrorCode\.%s\b" % code, text))
            hits[code] += len(re.findall(r'"%s"' % code, text))
    return hits


def compare_codes(data):
    java_codes, members, md, enum_codes, hits = (
        data["java"], data["members"], data["md"], data["enum"], data["hits"])
    for label, a, b in (("枚举 vs 码表", set(java_codes), set(md)),
                        ("枚举 vs OpenAPI", set(java_codes), enum_codes)):
        for missing in sorted(a - b):
            err("%s 缺少 %s（%s 有、对方没有）" % (label, missing, label.split(" vs ")[0]))
        for ghost in sorted(b - a):
            err("%s 多出 %s（%s 里没有这个码）" % (label, ghost, label.split(" vs ")[0]))
    bump("错误码", len(java_codes))

    for code, status in java_codes.items():
        if status not in STATUS_INT:
            err("ErrorCode.%s 的 HttpStatus %s 没有对应的数字口径" % (code, status))
            continue
        row = md.get(code)
        if not row:
            continue
        if row["http"] != str(STATUS_INT[status]):
            err("%s 码表 HTTP 列=%s，但 ErrorCode.status()=%d" % (code, row["http"], STATUS_INT[status]))
        expect = "永久" if code in members["permanent"] else (
            "暂时" if code in members["transientFailure"] else "—")
        if row["cls"] != expect:
            err("%s 码表分类=%s，但代码归类应为 %s（permanent()=%s / transientFailure()=%s）"
                % (code, row["cls"], expect, code in members["permanent"],
                   code in members["transientFailure"]))
        bump("错误码列语义")

    for code, n in sorted(hits.items()):
        if n == 0:
            err("%s 在 src/main/java（ErrorCode.java 除外）出现 0 次：违反「声明必须可产生」，"
                "要么接线要么从枚举/码表/OpenAPI 删除" % code)
        else:
            bump("可产生性")


def load_yaml(path):
    with open(path, encoding="utf-8") as handle:
        return yaml.safe_load(handle)


# ---------------------------------------------------------------- 2. 配置三层

def parse_catalog_rows(catalog_text):
    rows = []
    for line in catalog_text.splitlines():
        s = line.strip()
        if not s.startswith("|") or re.fullmatch(r"\|[\s:\-|]+\|", s):
            continue
        c = cells(s)
        if len(c) < 3 or c[0] in ("环境变量", "项", "层"):
            continue
        envs = re.findall(r"`([A-Z0-9_]+)`", c[0])
        props = [bare(x) for x in re.split(r"`?\s*/\s*`?", c[1]) if bare(x) and bare(x) != "—"]
        props = [p for p in props if "." in p]
        default = c[2].strip()
        rows.append({"envs": envs, "props": props, "default": bare(default),
                     "raw_default": default, "note": c[3] if len(c) > 3 else ""})
    return rows


def parse_yml_placeholders(yml_text):
    """application.yml 里所有 ${KB_*:default}，返回 {env: (default, 出现的属性路径)}。"""
    out = {}
    prop_path = None
    stack = []
    for line in yml_text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        while stack and stack[-1][0] >= indent:
            stack.pop()
        key = line.strip().split(":", 1)[0].strip()
        if not line.strip().startswith("-") and ":" in line:
            if line.strip().endswith(":") or (line.count(":") == 1 and line.strip().endswith(":")):
                stack.append((indent, key))
                prop_path = ".".join(k for _i, k in stack)
                continue
            stack.append((indent, key))
            prop_path = ".".join(k for _i, k in stack)
            stack.pop()
        for env, default in re.findall(r"\$\{([A-Z0-9_]+):([^}]*)\}", line):
            if env.startswith("KB_"):
                out.setdefault(env, (default, prop_path))
    return out


def single_backtick(raw):
    """单元格正好是一个反引号包裹的值时返回它，否则 None（如 `false`（compose 里为 `true`）不参与比对）。

    带 `…` / `...` 的省略写法（如 JDBC URL）只做人工核对，不比对。
    """
    match = re.fullmatch(r"`([^`]*)`", raw.strip())
    if not match:
        return None
    value = match.group(1)
    if "..." in value or "…" in value:
        return None
    return value


def same_scalar(value, expect):
    """yml 取值与目录默认值的比较：布尔按小写文本比，其余按字符串严格比。"""
    if isinstance(value, bool):
        return ("true" if value else "false") == expect
    return str(value) == expect


def yml_flatten(node, prefix=""):
    out = {}
    if isinstance(node, dict):
        for k, v in node.items():
            out.update(yml_flatten(v, "%s.%s" % (prefix, k) if prefix else str(k)))
    else:
        out[prefix] = node
    return out


def parse_kb_properties():
    """返回 {属性路径: (字段名, @DefaultValue 文本或 None)}，覆盖 KbProperties 的嵌套 record。"""
    source = read(KB_PROPS_JAVA)
    records = {}
    for name, args in re.findall(r"public record (\w+)\((.*?)\)\s*\{", source, re.S):
        fields = {}
        for part in split_args(args):
            part = re.sub(r"/\*.*?\*/", " ", part, flags=re.S)          # 参数列表里的 javadoc
            default = None
            match = re.search(r'@DefaultValue\(\s*"([^"]*)"\s*\)', part)
            if match:
                default = match.group(1)
            rest = re.sub(r"@\w+(\(\s*(?:\"[^\"]*\"|\{[^}]*\})?\s*\))?", " ", part).strip()
            tokens = rest.split()
            if len(tokens) < 2:
                continue
            fields[tokens[-1]] = (default, " ".join(tokens[:-1]))
        records[name] = fields
    root = records.pop("KbProperties", {})
    out = {}

    def walk(fields, prefix):
        for fname, (dv, ftype) in fields.items():
            kebab = re.sub(r"(?<!^)(?=[A-Z])", "-", fname).lower()
            path = "%s.%s" % (prefix, kebab)
            out[path] = (fname, dv)
            tname = re.sub(r"<.*>", "", ftype).strip()
            if tname in records:
                walk(records[tname], path)

    walk(root, "kb")
    return out


def split_args(args):
    """按顶层逗号切分 record 参数列表（忽略 <> 与 () 内的逗号）。"""
    parts, depth, cur = [], 0, ""
    for ch in args:
        if ch in "<(":
            depth += 1
        elif ch in ">)":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur)
    return parts


def check_config():
    compare_config(load_config())


def load_config():
    secrets_java = read(SECRETS_JAVA)
    catalog_text = read(CATALOG)
    bullet = catalog_text.split("映射表在 `SecretsEnvironmentPostProcessor`", 1)
    catalog_map = {}
    if len(bullet) > 1:
        chunk = bullet[1].split("- 内联值优先", 1)[0]
        catalog_map = dict(re.findall(r"`(KB_[A-Z0-9_]+)`→`([a-z0-9.\-]+)`", chunk.replace("\n", "")))
    return {
        "rows": parse_catalog_rows(catalog_text),
        "flat": yml_flatten(load_yaml(YML)),
        "props": parse_kb_properties(),
        "placeholders": parse_yml_placeholders(read(YML)),
        "compose_envs": sorted(set(re.findall(r"\bKB_[A-Z0-9_]+\b", read(COMPOSE)))),
        "secrets_code": dict(re.findall(r'"(KB_[A-Z0-9_]+)",\s*"([a-z0-9.\-]+)"', secrets_java)),
        "secrets_catalog": catalog_map,
    }


def compare_config(cfg):
    rows = cfg["rows"]
    flat = cfg["flat"]
    props = cfg["props"]
    placeholders = cfg["placeholders"]
    registered_envs = set()
    compose_only = set()
    for row in rows:
        registered_envs |= set(row["envs"])
        if not row["props"]:
            compose_only |= set(row["envs"])

    for row in rows:
        for env in row["envs"]:
            if env in compose_only:
                continue
            base = env[:-5] if env.endswith("_FILE") else env
            if env.endswith("_FILE") and base in (
                    "KB_DB_USER", "KB_DB_PASSWORD", "KB_DB_DDL_PASSWORD", "KB_REDIS_PASSWORD",
                    "KB_MINIO_ACCESS_KEY", "KB_MINIO_SECRET_KEY", "KB_ENGINE_TOKEN",
                    "KB_OIDC_CLIENT_SECRET"):
                continue
            if env not in placeholders:
                err("目录登记了 %s 但 application.yml 里没有 ${%s:...}" % (env, env))
        for prop in row["props"]:
            if prop not in flat:
                err("目录登记的属性 %s 在 application.yml 里不存在" % prop)
                continue
            bump("配置三层-属性存在")
            value = flat[prop]
            expect_default = single_backtick(row["raw_default"])
            if isinstance(value, str) and value.startswith("${") and ":" in value:
                inline_env = value[2:value.index(":")]
                inline_default = value[value.index(":") + 1:-1]
                if row["envs"] and inline_env not in row["envs"]:
                    err("%s 的 yml 占位符用 ${%s:…}，但目录该行的环境变量列是 %s"
                        % (prop, inline_env, ", ".join(row["envs"])))
                if row["raw_default"] == "（空）":
                    if inline_default != "":
                        err("%s 目录写「（空）」但 yml 内联默认值是 %r" % (prop, inline_default))
                elif expect_default is not None:
                    if inline_default != expect_default:
                        err("%s yml 内联默认值=%r 与目录=%s 不一致" % (prop, inline_default, expect_default))
                    bump("配置三层-内联默认值")
            elif expect_default is not None and not row["envs"]:
                if not same_scalar(value, expect_default):
                    err("%s yml 取值=%r 与目录默认值=%s 不一致" % (prop, value, expect_default))
                bump("配置三层-固定取值")
            if prop.startswith("kb."):
                if prop not in props:
                    err("%s 在 KbProperties 里没有对应字段（三层缺一）" % prop)
                    continue
                fname, dv = props[prop]
                if row["raw_default"] == "（空）":
                    if dv not in (None, ""):
                        err("%s 目录写「（空）」但 KbProperties.%s 带 @DefaultValue(%r)" % (prop, fname, dv))
                elif row["raw_default"] == "（yml 固定）":
                    if dv is not None:
                        err("%s 目录写「（yml 固定）」但 KbProperties.%s 有 @DefaultValue(%r)" % (prop, fname, dv))
                elif expect_default is not None:
                    if dv is None:
                        err("%s 目录默认值=%s，但 KbProperties.%s 没有 @DefaultValue"
                            % (prop, expect_default, fname))
                    elif dv != expect_default:
                        err("%s @DefaultValue(%r) 与目录默认值 %s 不一致" % (prop, dv, expect_default))
                bump("配置三层-KbProperties")

    for env, (_default, _prop) in sorted(placeholders.items()):
        if env not in registered_envs:
            err("application.yml 使用了未登记的环境变量 %s（必须写进 config-catalog-p1.md）" % env)
        bump("配置登记-环境变量")

    for env in cfg["compose_envs"]:
        if env not in registered_envs:
            err("docker-compose.yml 设置了未登记的 %s" % env)
        else:
            bump("配置登记-compose")

    code_map = cfg["secrets_code"]
    catalog_map = cfg["secrets_catalog"]
    for env, prop in sorted(code_map.items()):
        if env not in catalog_map:
            err("`*_FILE` 映射 %s→%s 未登记进 config-catalog-p1.md" % (env, prop))
        elif catalog_map[env] != prop:
            err("`*_FILE` 映射 %s 不一致：代码=%s 目录=%s" % (env, prop, catalog_map[env]))
        else:
            bump("凭据注入映射")
    for env, prop in sorted(catalog_map.items()):
        if env not in code_map:
            err("目录登记了 %s→%s，但 SecretsEnvironmentPostProcessor 里没有该映射" % (env, prop))


# ---------------------------------------------------------------- 3. OpenAPI ↔ Controller

def controller_operations():
    out = {}
    for path in sorted(main_java_sources()):
        text = read(path)
        if "Mapping(" not in text:
            continue
        bases = re.findall(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"', text)
        base = bases[0] if bases else ""
        for verb, tail in re.findall(
                r'@(Get|Post|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*)?"([^"]+)"', text):
            out.setdefault("%s %s%s" % (verb.upper(), base, tail), os.path.basename(path))
    return out


def declared_operations(spec):
    out = {}
    for url, item in (spec.get("paths") or {}).items():
        for verb in item or {}:
            if verb in HTTP_METHODS:
                out["%s %s" % (verb.upper(), url)] = (item[verb] or {}).get("operationId", "")
    return out


def check_openapi_vs_controllers():
    spec = load_yaml(OAS)
    compare_operations(declared_operations(spec), controller_operations(), spec)


def compare_operations(declared, actual, spec):
    for key in sorted(set(actual) - set(declared)):
        err("Controller 有 %s（%s），openapi-1.2.yaml 未声明" % (key, actual[key]))
    for key in sorted(set(declared) - set(actual)):
        err("openapi-1.2.yaml 声明了 %s，但门户里没有对应 Controller 注解" % key)
    bump("OpenAPI 操作", len(declared))

    desc = (spec.get("info") or {}).get("description") or ""
    for url, _item in (spec.get("paths") or {}).items():
        if "`%s`" % url in desc:
            err("%s 被 info.description 列为「未声明的目标契约」，却出现在 paths: 里" % url)


# ---------------------------------------------------------------- 4. x-error-codes

def check_x_error_codes():
    spec = load_yaml(OAS)
    enum_codes = set(spec["components"]["schemas"]["ApiError"]["properties"]["code"]["enum"])
    used = set()
    for url, item in (spec.get("paths") or {}).items():
        for verb, op in (item or {}).items():
            if verb not in HTTP_METHODS:
                continue
            codes = (op or {}).get("x-error-codes")
            if not codes:
                err("%s %s 缺 x-error-codes" % (verb.upper(), url))
                continue
            for code in codes:
                if code not in enum_codes:
                    err("%s %s 声明了枚举外的码 %s" % (verb.upper(), url, code))
                used.add(code)
            bump("x-error-codes")
    for code in sorted(enum_codes - used):
        if code in AUDIT_ONLY_CODES:
            continue
        err("ApiError.code 枚举里的 %s 没有任何操作声明（要么挂到会产生它的操作上，要么从枚举删除）"
            % code)


# ---------------------------------------------------------------- 5. Wiki 状态机

AXIS_ENUM = {"PAGE": "WikiPageStatus", "REVISION": "WikiRevisionStatus", "SYNC": "WikiSyncStatus"}


def parse_edge_table(md_text):
    rows = []
    for row in table_rows(md_text, "## 2. 迁移边表"):
        if len(row) < 4:
            continue
        eid = bare(row[0])
        if not re.fullmatch(r"[ERS]\d+", eid):
            continue
        rows.append((eid, bare(row[1]), bare(row[2]), bare(row[3]), row[7] if len(row) > 7 else ""))
    return rows


def enum_constants(enum_name):
    path = os.path.join(MAIN_JAVA, "com", "acme", "kb", "p1", "wiki", "%s.java" % enum_name)
    if not os.path.isfile(path):
        return None
    body = read(path)
    match = re.search(r"enum\s+%s\s*(?:implements\s[\w, ]+)?\s*\{" % enum_name, body)
    if not match:
        return set()
    head = body[match.end():].split("\n    }", 1)[0]
    return {c for c in re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*[,;(]", head, re.M)}


def parse_java_edges():
    """`WikiStateMachine.EDGES` 里的 edge("E1", "PAGE", "DRAFT", "IN_REVIEW", …)；文件不存在返回 None。"""
    if not os.path.isfile(WIKI_SM_JAVA):
        return None
    text = read(WIKI_SM_JAVA)
    return {tuple(m) for m in re.findall(
        r'edge\(\s*"([ERS]\d+)"\s*,\s*"(\w+)"\s*,\s*"([A-Z0-9_]+)"\s*,\s*"([A-Z0-9_]+)"', text)}


def check_state_machine():
    compare_state_machine(parse_edge_table(read(SM_MD)), parse_java_edges())


def compare_state_machine(rows, java_edges):
    if not rows:
        err("state-machine-wiki.md 没解析出任何迁移边（表格格式变了？）")
        return
    for eid, axis, src, dst, _wiring in rows:
        if axis not in AXIS_ENUM:
            err("边 %s 的 axis=%r 不是 PAGE/REVISION/SYNC" % (eid, axis))
            continue
        consts = enum_constants(AXIS_ENUM[axis])
        if consts is None:
            continue
        for state in (src, dst):
            if state not in consts:
                err("边 %s 的状态 %s 不是 %s 的枚举常量" % (eid, state, AXIS_ENUM[axis]))
    bump("状态机边", len(rows))

    if java_edges is None:
        warn("WikiStateMachine.java 尚未落地（P2-W1）：%d 条边只核对了文档侧" % len(rows))
        return
    doc = {(e, a, s, d) for e, a, s, d, _ in rows}
    for missing in sorted(doc - java_edges):
        err("边 %s（%s %s → %s）在文档里但 WikiStateMachine.EDGES 没有" % missing)
    for extra in sorted(java_edges - doc):
        err("边 %s（%s %s → %s）在 WikiStateMachine.EDGES 里但文档未登记" % extra)
    bump("状态机边表比对", len(java_edges & doc))


# ---------------------------------------------------------------- 6. Kafka

PRIMITIVE = {"string": str, "integer": int, "number": (int, float),
             "boolean": bool, "object": dict, "array": list}


def type_of(spec):
    t = spec.get("type")
    if isinstance(t, list):
        return tuple(PRIMITIVE[x] for x in t if x in PRIMITIVE)
    return PRIMITIVE.get(t)


def validate_instance(data, schema, where):
    props = schema.get("properties", {})
    required = set(schema.get("required", []))
    for missing in sorted(required - set(data)):
        err("%s 缺必填字段 %s" % (where, missing))
    extra = sorted(set(data) - set(props))
    if extra and schema.get("additionalProperties") is False:
        err("%s 含 schema 未声明的字段 %s（additionalProperties:false）" % (where, ", ".join(extra)))
    for key, value in data.items():
        spec = props.get(key)
        if spec is None:
            continue
        if value is None:
            err("%s.%s 是 null：non_null 序列化会把该键整个丢掉，样例里不许出现 null" % (where, key))
            continue
        expected = type_of(spec)
        if expected is not None and not isinstance(value, expected):
            err("%s.%s 类型应为 %s，实际 %r" % (where, key, spec.get("type"), value))
        if "const" in spec and value != spec["const"]:
            err("%s.%s 应为常量 %r，实际 %r" % (where, key, spec["const"], value))
        if "enum" in spec and value not in spec["enum"]:
            err("%s.%s=%r 不在枚举 %s 内" % (where, key, value, spec["enum"]))
        if isinstance(value, str):
            if "maxLength" in spec and len(value) > spec["maxLength"]:
                err("%s.%s 长度 %d 超上限 %d" % (where, key, len(value), spec["maxLength"]))
            if "pattern" in spec and not re.match(spec["pattern"], value):
                err("%s.%s=%r 不匹配 %s" % (where, key, value, spec["pattern"]))
            if spec.get("format") == "date-time" and not re.match(
                    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$", value):
                err("%s.%s=%r 不是 UTC ISO-8601" % (where, key, value))
            if spec.get("format") == "uuid" and not re.match(
                    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", value):
                err("%s.%s=%r 不是 uuid" % (where, key, value))
        if isinstance(value, int) and not isinstance(value, bool):
            if "minimum" in spec and value < spec["minimum"]:
                err("%s.%s=%d 小于最小值 %d" % (where, key, value, spec["minimum"]))
            if "maximum" in spec and value > spec["maximum"]:
                err("%s.%s=%d 大于最大值 %d" % (where, key, value, spec["maximum"]))
        if isinstance(value, dict) and spec.get("type") == "object":
            nested = spec.get("properties")
            if nested:
                validate_instance(value, spec, "%s.%s" % (where, key))
        if isinstance(value, list):
            if "minItems" in spec and len(value) < spec["minItems"]:
                err("%s.%s 元素数 %d 少于 minItems %d" % (where, key, len(value), spec["minItems"]))
            if "maxItems" in spec and len(value) > spec["maxItems"]:
                err("%s.%s 元素数 %d 超过 maxItems %d" % (where, key, len(value), spec["maxItems"]))
            item_spec = spec.get("items") or {}
            for index, element in enumerate(value):
                if isinstance(element, dict) and item_spec.get("properties"):
                    validate_instance(element, item_spec, "%s.%s[%d]" % (where, key, index))


def java_record_fields(path):
    out = {}
    for match in re.finditer(r"public record (\w+)\((.*?)\)\s*\{", read(path), re.S):
        names = []
        for part in split_args(match.group(2)):
            tokens = part.split()
            if len(tokens) >= 2 and re.fullmatch(r"[a-zA-Z_]\w*", tokens[-1]):
                names.append(tokens[-1])
        out[match.group(1)] = names
    return out


SCHEMA_FOR_RECORD = {
    "IngestRequested": "document-ingest-requested.v1.schema.json",
    "IngestRetry": "document-ingest-retry.v1.schema.json",
    "DeleteRequested": "document-delete-requested.v1.schema.json",
    "DlqEnvelope": "dlq-envelope.v1.schema.json",
}


def compare_audit_payload(keys, props):
    """载荷键 ↔ `audit-event.v1.schema.json` 双向一致（backlog #20 的判据落点）。"""
    for key in keys:
        if key not in props:
            err("AuditWriter 外发载荷有键 %s，audit-event.v1.schema.json 未声明" % key)
    for key in sorted(props - set(keys)):
        err("audit-event.v1.schema.json 声明了 %s，但 AuditWriter 的载荷里没有该键" % key)


def check_kafka():
    schemas = sorted(f for f in os.listdir(KAFKA) if f.endswith(".schema.json"))
    if not schemas:
        err("p0/contracts/kafka 下没有 schema 文件")
    sample_stems = set()
    for name in sorted(os.listdir(SAMPLES)):
        if not name.endswith(".sample.json"):
            continue
        stem = name.split(".sample.")[0]
        sample_stems.add(stem)
        schema_path = os.path.join(KAFKA, stem + ".v1.schema.json")
        if not os.path.isfile(schema_path):
            err("样例 %s 找不到对应 schema：%s" % (name, os.path.basename(schema_path)))
            continue
        validate_instance(load_json(os.path.join(SAMPLES, name)), load_json(schema_path), name)
        bump("样例", 1)
    for schema in schemas:
        stem = schema[:-len(".v1.schema.json")]
        if stem not in sample_stems:
            err("schema %s 没有 samples/%s.sample.json（04 号脚本同类要求）" % (schema, stem))

    records = java_record_fields(P1_EVENTS_JAVA)
    for record, schema_file in SCHEMA_FOR_RECORD.items():
        schema = load_json(os.path.join(KAFKA, schema_file))
        props = set(schema.get("properties", {}))
        required = set(schema.get("required", []))
        fields = set(records.get(record, []))
        if not fields:
            err("P1 Events.java 未找到 record %s" % record)
            continue
        extra = sorted(fields - props)
        if extra and schema.get("additionalProperties") is False:
            err("P1 %s 发出 schema 未声明的字段 %s" % (record, ", ".join(extra)))
        missing = sorted(required - fields)
        if missing:
            err("P1 %s 未发出必填字段 %s" % (record, ", ".join(missing)))
        bump("事件字段", 1)

    writer = read(AUDIT_WRITER_JAVA)
    keys = re.findall(r'payload\.put\("(\w+)"', writer)
    audit_schema = load_json(os.path.join(KAFKA, "audit-event.v1.schema.json"))
    compare_audit_payload(keys, set(audit_schema.get("properties", {})))
    bump("审计载荷字段", len(keys))

    declared = set(re.findall(r"`(kb\.[a-z0-9.]+\.v\d)`", read(KAFKA_README)))
    referenced = set()
    for text in (read(YML), read(KB_PROPS_JAVA), read(COMPOSE)):
        referenced |= set(re.findall(r"\b(kb\.[a-z0-9.]+\.v\d)\b", text))
    for topic in sorted(referenced - declared):
        err("主题 %s 被 yml/KbProperties/compose 引用，但没在 p0/contracts/kafka/README.md 登记" % topic)
    for topic in sorted(declared - referenced):
        if topic.startswith("kb.wiki."):
            continue
        warn("README 登记的主题 %s 当前没有任何引用处（孤儿主题，见 backlog #21）" % topic)
    bump("主题登记", len(declared))


# ---------------------------------------------------------------- 7. 审计动作

AUDIT_ACTION_RE = re.compile(r"^[a-z][a-z0-9.]{2,63}$")


def check_audit_actions():
    rows = table_rows(read(SM_MD), "## 9. 审计动作清单")
    if not rows:
        err("state-machine-wiki.md §9 审计动作清单没解析出行（表格格式变了？）")
        return
    sources = [(p, read(p)) for p in main_java_sources()]
    for row in rows:
        action = bare(row[0])
        if not AUDIT_ACTION_RE.match(action):
            err("审计动作 %r 不符合小写点名式（audit-event.v1.schema.json 的 pattern）" % action)
            continue
        wiring = row[4] if len(row) > 4 else ""
        if not wiring.startswith("W1"):
            continue
        hits = sum(len(re.findall(r'"%s"' % re.escape(action), text)) for _p, text in sources)
        if hits == 0:
            err("审计动作 %s 标为 W1 已接线，但 src/main/java 里搜不到该字面量" % action)
        else:
            bump("审计动作")


# ---------------------------------------------------------------- 自检（负向用例）

def expect_error(label, run, needle):
    """把输入改坏一处，确认对应检查真的报错。自检产生的错误不回写进正式结论。"""
    saved = ERRORS[:]
    del ERRORS[:]
    try:
        run()
    except Exception as exc:  # noqa: BLE001
        del ERRORS[:]
        ERRORS[:] = saved
        err("自检 %s 抛异常：%s: %s" % (label, type(exc).__name__, exc))
        return False
    hit = any(needle in e for e in ERRORS)
    del ERRORS[:]
    ERRORS[:] = saved
    if not hit:
        err("自检失败：%s 没有报出预期错误（%s）——该检查是空跑的，不能当门禁" % (label, needle))
        return False
    print("  自检 %-28s 已捕获：%s" % (label, needle))
    return True


def selftest():
    print("== 负向自检（P2-W0-05：改坏一处必须报错）==")
    cfg = load_config()

    def renamed_prop():
        mutated = dict(cfg)
        rows = [dict(r) for r in cfg["rows"]]
        target = next((r for r in rows if r["props"] and r["props"][0] == "kb.wiki.stale-days"), rows[0])
        target["props"] = ["kb.wiki.stale-dayz"] + target["props"][1:]
        mutated["rows"] = rows
        compare_config(mutated)

    def dropped_field():
        mutated = dict(cfg)
        props = dict(cfg["props"])
        props.pop("kb.ingest.lease-seconds", None)
        mutated["props"] = props
        compare_config(mutated)

    def unregistered_env():
        mutated = dict(cfg)
        placeholders = dict(cfg["placeholders"])
        placeholders["KB_SELFTEST_GHOST"] = ("1", "kb.selftest")
        mutated["placeholders"] = placeholders
        compare_config(mutated)

    def audit_payload_key_renamed():
        writer_keys = re.findall(r'payload\.put\("(\w+)"', read(AUDIT_WRITER_JAVA))
        schema_props = set(load_json(os.path.join(KAFKA, "audit-event.v1.schema.json"))
                           .get("properties", {}))
        victim = sorted(schema_props & set(writer_keys))[0]
        compare_audit_payload([victim + "_renamed"] + [k for k in writer_keys if k != victim],
                              schema_props)

    def missing_code():
        data = load_codes()
        md = dict(data["md"])
        victim = sorted(set(data["java"]) & set(md))[0]
        md.pop(victim)
        mutated = dict(data)
        mutated["md"] = md
        compare_codes(mutated)

    def wrong_class():
        data = load_codes()
        mutated = dict(data)
        md = dict(data["md"])
        victim = sorted(md)[0]
        md[victim] = dict(md[victim], cls="永久" if md[victim]["cls"] != "永久" else "—")
        mutated["md"] = md
        compare_codes(mutated)

    def unproduced_code():
        data = load_codes()
        mutated = dict(data)
        victim = sorted(data["hits"])[0]
        mutated["hits"] = dict(data["hits"], **{victim: 0})
        compare_codes(mutated)

    rows = parse_edge_table(read(SM_MD))
    all_edges = {(e, a, s, d) for e, a, s, d, _ in rows}

    def edge_missing_in_java():
        compare_state_machine(rows, all_edges - {sorted(all_edges)[0]})

    def edge_not_registered():
        compare_state_machine(rows[:-1], all_edges | {("E99", "PAGE", "DRAFT", "IN_REVIEW")})

    def ghost_operation():
        compare_operations({"GET /api/v1/x": "x"}, {"GET /api/v1/x": "X.java",
                                                   "POST /api/v1/y": "Y.java"},
                           {"info": {"description": ""}, "paths": {"/api/v1/x": {"get": {}}}})

    def undeclared_path():
        compare_operations({"GET /api/v1/x": "x"}, {"GET /api/v1/x": "X.java"},
                           {"info": {"description": "`/api/v1/x`"},
                            "paths": {"/api/v1/x": {"get": {}}}})

    ok = 0
    total = 0
    for label, fn, needle in (
            ("属性名改名", renamed_prop, "在 application.yml 里不存在"),
            ("KbProperties 缺字段", dropped_field, "在 KbProperties 里没有对应字段"),
            ("未登记环境变量", unregistered_env, "使用了未登记的环境变量"),
            ("审计载荷键改名", audit_payload_key_renamed, "audit-event.v1.schema.json 未声明"),
            ("码表少一行", missing_code, "缺少"),
            ("分类列漂移", wrong_class, "码表分类"),
            ("码不可产生", unproduced_code, "出现 0 次"),
            ("边未接进 Java", edge_missing_in_java, "在文档里但 WikiStateMachine.EDGES 没有"),
            ("边未登记进文档", edge_not_registered, "在 WikiStateMachine.EDGES 里但文档未登记"),
            ("Controller 越出契约", ghost_operation, "未声明"),
            ("未声明路径混进 paths", undeclared_path, "却出现在 paths: 里")):
        total += 1
        ok += 1 if expect_error(label, fn, needle) else 0
    print("  自检 %d/%d 通过" % (ok, total))


# ---------------------------------------------------------------- main

def main():
    if "--selftest" in sys.argv:
        selftest()
        print("\n%d error / %d warning" % (len(ERRORS), len(WARNINGS)))
        for item in ERRORS:
            print("ERROR %s" % item)
        return 1 if ERRORS else 0
    print("== pilot 契约一致性（P1/P2）==")
    for name, fn in (("错误码三方一致", check_error_codes),
                     ("配置三层同名", check_config),
                     ("OpenAPI ↔ Controller", check_openapi_vs_controllers),
                     ("x-error-codes", check_x_error_codes),
                     ("Wiki 状态机边表", check_state_machine),
                     ("Kafka schema/样例/载荷", check_kafka),
                     ("审计动作", check_audit_actions)):
        before = len(ERRORS)
        try:
            fn()
        except Exception as exc:  # noqa: BLE001 —— 校验脚本自身出错必须显眼，不能静默跳过
            err("[%s] 校验器异常：%s: %s" % (name, type(exc).__name__, exc))
        status = "OK" if len(ERRORS) == before else "FAIL"
        print("  %-22s %s" % (name, status))

    print()
    print("核对项：%s" % "，".join("%s %d" % (k, v) for k, v in sorted(CHECK_COUNT.items())))
    for item in WARNINGS:
        print("WARN  %s" % item)
    for item in ERRORS:
        print("ERROR %s" % item)
    print("\n%d error / %d warning" % (len(ERRORS), len(WARNINGS)))
    return 1 if ERRORS else 0


if __name__ == "__main__":
    sys.exit(main())
