#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 P0 质量基线语料：60 份可检索文档 + 1 份无文字层 PDF（已知解析缺口，负向样本）。

体裁取自制造/医药企业的真实文档结构（编号、生效日期、归口部门、章节、参数表），
内容全部按脱敏口径重写：机构、人名、金额、时点均为虚构值，不含任何生产数据。
每份文档埋入一组唯一的「主体-属性-值」事实，questions.csv 据此生成标准问题与期望来源。

阶段：
  --phase spec      写文本类语料 + office-spec.json（docx/xlsx/pptx/pdf 交给 Java 生成器）
  --phase manifest  在 office 文件生成后，写 corpus-manifest.csv 与 questions.csv
"""
import csv
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
EV = os.path.join(ROOT, "evaluation")
CORPUS = os.path.join(EV, "corpus")
SRC = os.path.join(EV, "corpus-src")

KBS = {
    "ops": ("kb-ops", "运维与变更手册", "INTERNAL"),
    "qms": ("kb-qms", "质量体系程序文件", "INTERNAL"),
    "fin": ("kb-fin", "财务与采购制度", "INTERNAL"),
    "dat": ("kb-dat", "数据标准与接口规范", "INTERNAL"),
}
SUIT = {
    "ops": ("运维", "变更管理组", "机房、数据库、消息与缓存"),
    "qms": ("质量", "质量保证部", "生产、检验与体系文件"),
    "fin": ("财务", "财务共享中心", "费用、采购与结算"),
    "dat": ("数据", "数据治理组", "标准、接口与指标"),
}

# code, kb, fmt, title, subject, attr, value, attr2, value2, 适用范围, 责任人, 生效日
D = [
    ("OPS-01", "ops", "md", "机房环境告警阈值基线", "冷通道温度", "告警阈值", "27.5℃", "采样周期", "30秒", "A/B 两座机房", "翁志强", "2026-03-01"),
    ("OPS-02", "ops", "docx", "数据库备份与保留策略", "全量备份", "保留期", "35天", "日志备份频率", "每15分钟", "生产 MySQL 与引擎索引库", "裴莱", "2026-02-10"),
    ("OPS-03", "ops", "md", "Kafka 消费组运行参数", "入库消费组", "单批最大拉取记录数", "500条", "会话超时", "15000毫秒", "kb.ingest 系列主题", "慕言", "2026-04-05"),
    ("OPS-04", "ops", "md", "Redis 缓存容量与逐出规则", "授权缓存实例", "内存上限", "6GB", "逐出策略", "allkeys-lru", "决策缓存与水位键", "慕言", "2026-04-05"),
    ("OPS-05", "ops", "txt", "对象存储版本保留说明", "原件桶非当前版本", "保留天数", "14天", "碎片清理", "每日03:40", "kb-p0-originals-private 桶", "裴莱", "2026-01-20"),
    ("OPS-06", "ops", "xlsx", "变更窗口审批时限表", "紧急变更", "审批时限", "30分钟", "常规变更提前申报", "72小时", "生产变更评审", "荣沛", "2026-05-12"),
    ("OPS-07", "ops", "pptx", "生产事故分级与响应要求", "P1 级事故", "到场响应", "15分钟", "P2 级到场", "60分钟", "7×24 值班", "荣沛", "2026-05-12"),
    ("OPS-08", "ops", "md", "容器镜像漏洞扫描基线", "阻断门限", "CVSS 评分", "7.0分", "扫描频率", "每日02:00", "全部对外镜像", "戚远", "2026-06-01"),
    ("OPS-09", "ops", "md", "TLS 证书轮换手册", "证书到期", "提前触发天数", "21天", "旧证书宽限期", "48小时", "内部与对外网关", "戚远", "2026-06-01"),
    ("OPS-10", "ops", "csv", "磁盘容量水位治理表", "磁盘告警", "水位阈值", "75%", "写入熔断水位", "90%", "数据盘与对象存储盘", "裴莱", "2026-03-18"),
    ("OPS-11", "ops", "md", "运行日志留存与检索规则", "应用日志热检索", "留存天数", "7天", "归档留存", "180天", "门户与管道日志", "慕言", "2026-02-25"),
    ("OPS-12", "ops", "json", "服务端口白名单登记", "数据库对外映射端口", "端口号", "13306", "对象存储控制台端口", "19001", "验证环境出口策略", "戚远", "2026-06-15"),
    ("OPS-13", "ops", "pdf", "灾备切换演练方案", "切换目标", "RPO", "900秒", "切换 RTO", "3600秒", "知识库全链路", "荣沛", "2026-07-01"),
    ("OPS-14", "ops", "log", "夜班值守交接记录格式", "夜间值班", "在岗人数", "2人", "交接确认时限", "10分钟", "值守台", "荣沛", "2026-07-01"),
    ("OPS-15", "ops", "md", "发布回滚判据", "回滚触发", "错误率门限", "2.5%", "持续观测窗口", "5分钟", "门户与引擎发布", "慕言", "2026-04-20"),

    ("QMS-01", "qms", "md", "偏差分级与处理时限", "重大偏差", "调查启动时限", "24小时", "次要偏差时限", "72小时", "生产与检验环节", "水皎", "2026-01-05"),
    ("QMS-02", "qms", "docx", "纠正与预防措施关闭规则", "CAPA 有效性确认", "确认周期", "90天", "关闭前复核", "2次", "所有已开 CAPA", "水皎", "2026-01-05"),
    ("QMS-03", "qms", "xlsx", "洁净区环境监测限度表", "沉降菌", "静态限度", "每皿1cfu", "浮游菌限度", "每皿5cfu", "B/C 级区", "谷琳", "2026-02-18"),
    ("QMS-04", "qms", "md", "工艺用水检测频次", "纯化水送检", "每周频次", "2次", "趋势回顾", "每季度", "配制与清洗用水", "谷琳", "2026-02-18"),
    ("QMS-05", "qms", "csv", "关键仪器校准周期清单", "分析天平", "校准周期", "6个月", "期间核查", "每3个月", "检验室仪器", "谷琳", "2026-03-02"),
    ("QMS-06", "qms", "md", "批记录审核与放行时限", "批记录审核", "放行前工作日", "5个工作日", "缺项退回次数", "1次", "批生产与检验记录", "水皎", "2026-03-02"),
    ("QMS-07", "qms", "pdf", "稳定性试验留样要求", "长期稳定性考察", "考察月数", "36个月", "留样保存期", "效期后1年", "已上市规格", "郗宁", "2026-04-11"),
    ("QMS-08", "qms", "md", "供应商审计频次规定", "关键供应商现场审计", "间隔月数", "24个月", "书面审计", "每12个月", "原辅料与包材", "郗宁", "2026-04-11"),
    ("QMS-09", "qms", "docx", "变更控制影响评估规则", "变更影响评估", "评估工作日", "10个工作日", "紧急变更补评", "3个工作日", "工艺与设备变更", "水皎", "2026-05-06"),
    ("QMS-10", "qms", "tsv", "培训考核合格标准表", "GDP 年度培训", "合格分数", "80分", "补考窗口", "15天", "全员必修", "郗宁", "2026-05-06"),
    ("QMS-11", "qms", "md", "受控文件复审周期", "受控文件复审", "复审间隔", "36个月", "临时修订上限", "2次", "体系文件", "水皎", "2026-06-01"),
    ("QMS-12", "qms", "md", "不合格品处置权限", "让步放行", "审批岗位", "质量负责人", "销毁见证人数", "2人", "原辅料与成品", "郗宁", "2026-06-01"),
    ("QMS-13", "qms", "json", "内审不符合项整改登记", "不符合项整改", "证据提交天数", "30天", "复审逾期上限", "60天", "年度内审", "谷琳", "2026-06-20"),
    ("QMS-14", "qms", "txt", "数据完整性抽查说明", "关键数据抽查", "每批关键点", "3个", "抽查覆盖月数", "12个月", "电子记录", "谷琳", "2026-06-20"),
    ("QMS-15", "qms", "xlsx", "清洁验证取样规则", "擦拭取样面积", "取样面积", "25平方厘米", "取样点最少", "4个", "共用生产设备", "郗宁", "2026-07-08"),

    ("FIN-01", "fin", "md", "差旅住宿标准", "一线城市住宿", "每晚上限", "450元", "内陆城市上限", "330元", "员工国内出差", "仰岚", "2026-01-12"),
    ("FIN-02", "fin", "docx", "发票认证与跨期报销", "跨期报销截止", "截止日期", "次年1月31日", "专票认证期限", "360天", "员工报销", "仰岚", "2026-01-12"),
    ("FIN-03", "fin", "md", "采购比价与寻源门槛", "小额采购比价", "最少供应商数", "3家", "单一来源审批", "总经理", "非招标采购", "毛岫", "2026-02-20"),
    ("FIN-04", "fin", "xlsx", "合同用印办理时限", "合同用印", "办理工作日", "2个工作日", "加急办理", "4小时", "全部对外合同", "毛岫", "2026-02-20"),
    ("FIN-05", "fin", "csv", "固定资产折旧年限表", "服务器类设备", "折旧年限", "5年", "办公家具年限", "5年", "在用固定资产", "支滑", "2026-03-15"),
    ("FIN-06", "fin", "md", "供应商付款条件与账期", "月结账期", "付款天数", "60天", "预付比例上限", "30%", "原辅料采购", "毛岫", "2026-03-15"),
    ("FIN-07", "fin", "docx", "年度预算调整流程", "预算调整上会门槛", "调整幅度", "10%", "调整窗口", "每季度", "部门预算", "支滑", "2026-04-02"),
    ("FIN-08", "fin", "pptx", "费用报销审批权限矩阵", "单笔报销升级审批", "金额起点", "2万元", "总经理联签起点", "10万元", "全员费用报销", "支滑", "2026-04-02"),
    ("FIN-09", "fin", "md", "投标与履约保证金规则", "投标保证金", "占合同额比例", "2%", "履约保证金比例", "10%", "工程与服务投标", "毛岫", "2026-05-09"),
    ("FIN-10", "fin", "tsv", "中介机构服务费标准表", "年度审计服务费", "计费比例", "营收0.08%", "评估费上限", "8万元", "外部审计与评估", "仰岚", "2026-05-09"),
    ("FIN-11", "fin", "json", "低值易耗品领用规则", "资产编号起点", "单件金额", "500元", "月度领用上限量", "20件", "部门领用", "支滑", "2026-06-11"),
    ("FIN-12", "fin", "log", "银行结算手续费登记", "跨行转账", "单笔手续费", "25元", "加急汇款手续费", "50元", "对公结算账户", "仰岚", "2026-06-11"),
    ("FIN-13", "fin", "md", "印花税与票据税费计算", "买卖合同印花税率", "计税比例", "万分之三", "租赁合同税率", "千分之一", "对外签订合同的应税凭证", "仰岚", "2026-07-01"),
    ("FIN-14", "fin", "md", "员工备用金借款上限", "在职员工借款", "上限口径", "1个月工资", "归还期限", "60天", "出差与项目备用金", "支滑", "2026-07-01"),
    ("FIN-15", "fin", "txt", "供应商准入资料清单", "资质剩余有效期", "最少剩余天数", "90天", "资料更新周期", "每年", "新引入供应商", "毛岫", "2026-08-01"),

    ("DAT-01", "dat", "md", "数据主题域划分说明", "主题域", "划分数量", "14个", "扩展域审批", "数据治理会", "经营分析数据", "边渡", "2026-02-08"),
    ("DAT-02", "dat", "docx", "对外接口报文约束", "单请求体大小", "上限", "2MB", "超时熔断", "3秒", "门户对外接口", "边渡", "2026-02-08"),
    ("DAT-03", "dat", "xlsx", "主数据同步时间表", "供应商主数据同步", "同步时点", "每日01:30", "失败重试次数", "3次", "ERP 与门户之间", "白湄", "2026-03-22"),
    ("DAT-04", "dat", "csv", "经营指标口径登记表", "准时交付率", "分母口径", "应交付订单数", "统计周期", "自然月", "月度经营例会", "白湄", "2026-03-22"),
    ("DAT-05", "dat", "pdf", "服务目录与可用性承诺", "知识库门户", "月度可用性", "99.5%", "计划内停机窗口", "每月2小时", "对外公布的服务目录", "边渡", "2026-04-19"),
    ("DAT-06", "dat", "md", "错误码登记与响应约定", "跨租户资源访问", "响应码", "404", "密级不足响应码", "403", "门户全部接口", "边渡", "2026-04-19"),
    ("DAT-07", "dat", "md", "字段脱敏与展示规则", "手机号脱敏", "替换位数", "中间4位", "身份证保留位数", "前3后2", "对外展示与导出", "白湄", "2026-05-15"),
    ("DAT-08", "dat", "tsv", "报表产出时效表", "运营日报", "生成时点", "每日07:00", "周报时点", "周一09:00", "管理驾驶舱", "白湄", "2026-05-15"),
    ("DAT-09", "dat", "md", "知识入库分片参数", "分片目标长度", "字符数", "800字符", "相邻重叠", "120字符", "文档解析与索引", "慕言", "2026-06-05"),
    ("DAT-10", "dat", "log", "向量索引运行观察记录", "向量维度", "维度数", "768维", "索引重建耗时", "18分钟", "引擎召回链路", "慕言", "2026-06-05"),
    ("DAT-11", "dat", "txt", "数据资产责任人登记说明", "每张表责任人", "备用责任人", "1名", "变更审批时限", "5个工作日", "数据资产目录", "边渡", "2026-06-26"),
    ("DAT-12", "dat", "md", "接口幂等键使用规范", "幂等键", "规定格式", "UUIDv4", "有效保留天数", "7天", "写操作接口", "白湄", "2026-06-26"),
    ("DAT-13", "dat", "json", "上传文件类型白名单", "允许扩展名", "登记数量", "10种", "单文件上限", "104857600字节", "门户上传通道", "慕言", "2026-07-17"),
    ("DAT-14", "dat", "pptx", "问答上下文预算约束", "单次问答送入片段", "最多片段数", "6条", "提示字符预算", "4096字符", "门户问答", "慕言", "2026-07-17"),
    ("DAT-15", "dat", "xlsx", "引用定位字段规范", "引用定位", "必备字段数", "8个", "摘要长度上限", "600字符", "问答引用回溯", "白湄", "2026-08-10"),
]

REFUSALS = [
    "公司食堂早餐供应到几点结束？",
    "今年年会安排在哪个城市举办？",
    "员工健身房每天的开放时间是？",
    "公司的股票代码是多少？",
    "园区班车从哪个门发车？",
    "明年校园招聘计划招录多少人？",
    "食堂窗口支持哪种支付方式？",
    "董事长座驾是什么品牌？",
    "厂区地下停车场的收费标准如何？",
    "公司成立周年纪念活动安排在哪一天？",
]

OFFICE = {"docx", "xlsx", "pptx", "pdf"}


def golden(row):
    return "「%s」的%s为 %s。" % (row[4], row[5], row[6])


def golden2(row):
    return "「%s」的%s为 %s。" % (row[4], row[7], row[8])


def meta_line(row):
    return "文件编号：%s　生效日期：%s　归口部门：%s　责任人：%s　状态：受控" % (
        row[0], row[11], SUIT[row[1]][1], row[10])


def sections(row):
    code, kb, _, title, subject, attr, value, attr2, value2, scope, owner, _ = row
    return [
        ("1 目的与范围", [
            "为规范%s相关操作，制定本文件。" % title,
            "本文件适用于%s范围内的%s活动。" % (SUIT[kb][2], scope),
            "本文件由%s归口管理，解释权归%s。" % (SUIT[kb][1], SUIT[kb][1]),
        ]),
        ("2 职责与分工", [
            "%s（岗位编号 %s-01）负责本文件的编制、宣贯与年度回顾。" % (owner, code.replace("-", "")),
            "使用部门负责按本文件执行并留存记录，发现偏离应在当日内上报归口部门。",
            "内审组抽查执行情况，抽查结果纳入季度质量例会通报。",
        ]),
        ("3 关键参数规定", [
            golden(row),
            golden2(row),
            "上述参数由归口部门统一维护，任何调整须经变更流程并同步更新本文件版本。",
        ]),
        ("4 程序与步骤", [
            "第一步：责任人核对当前生效版本与配套记录表单。",
            "第二步：按第 3 节参数执行，异常情形不得自行放宽。",
            "第三步：记录执行结果并归档，归档期限按记录管理清单执行。",
            "第四步：每年回顾一次，连续两次偏离须触发纠正与预防措施。",
        ]),
        ("5 例外与否决", [
            "未经审批偏离本文件参数的，按重大不符合处理，作业应立即中止。",
            "涉及数据、金额或质量结论的例外，必须留存双人复核签名。",
            "例外有效期不得超过一个自然季度，到期自动失效。",
        ]),
        ("6 关联文件", [
            "《记录管理清单》《变更控制程序》《应急与回滚指引》为本文件配套文件。",
            "本文件与配套文件冲突时，以更严格者为准。",
        ]),
    ]


def as_blocks(row):
    return [{"heading": h, "lines": list(lines)} for h, lines in sections(row)]


def build_md(row):
    out = ["# %s" % row[3], "", meta_line(row), ""]
    for h, lines in sections(row):
        out += ["## %s" % h, ""] + ["- %s" % ln for ln in lines] + [""]
    out += ["## 修订记录", "", "| 版本 | 日期 | 说明 |", "| --- | --- | --- |",
            "| V1.0 | %s | 首次发布 |" % row[11], "| V1.1 | 2026-09-01 | 参数复核，无实质变更 |", ""]
    return "\n".join(out)


def build_txt(row):
    out = [row[3], meta_line(row), "", "适用范围：%s" % row[9], ""]
    for h, lines in sections(row):
        out.append(h)
        out += ["  %s" % ln for ln in lines] + [""]
    out.append("附注：本说明以纸质受控版本为最终依据，电子副本仅供检索与查阅。")
    return "\n".join(out) + "\n"


def build_log(row):
    code, kb, title, subject, attr, value, attr2, value2 = row[0], row[1], row[3], row[4], row[5], row[6], row[7], row[8]
    lines = [
        "2026-09-03T01:12:04Z INFO  [%s] policy.sync start source=%s" % (code, SUIT[kb][1]),
        "2026-09-03T01:12:04Z INFO  [%s] clause.load title=%s" % (code, title),
        "2026-09-03T01:12:05Z INFO  [%s] clause.check %s %s=%s -> PASS" % (code, subject, attr, value),
        "2026-09-03T01:12:05Z INFO  [%s] clause.check %s %s=%s -> PASS" % (code, subject, attr2, value2),
        "2026-09-03T01:12:06Z WARN  [%s] record.gap open_items=2 owner=%s" % (code, row[10]),
        "2026-09-03T01:12:06Z INFO  [%s] policy.sync done elapsed_ms=2143" % code,
        "2026-09-04T01:12:04Z INFO  [%s] policy.sync start source=%s" % (code, SUIT[kb][1]),
        "2026-09-04T01:12:05Z INFO  [%s] clause.check %s %s=%s -> PASS" % (code, subject, attr, value),
        "2026-09-04T01:12:05Z INFO  [%s] policy.sync done elapsed_ms=2098" % code,
    ]
    return "\n".join(lines) + "\n"


def build_json(row):
    doc = {
        "document": row[3], "code": row[0], "owner": row[10], "department": SUIT[row[1]][1],
        "effectiveFrom": row[11], "scope": row[9],
        "parameters": [
            {"subject": row[4], "attribute": row[5], "specifiedValue": row[6], "mandatory": True},
            {"subject": row[4], "attribute": row[7], "specifiedValue": row[8], "mandatory": False},
        ],
        "exceptionPolicy": "未经审批偏离规定值即判为重大不符合，作业中止",
        "reviewCycle": "每年一次，连续两次偏离触发纠正与预防措施",
    }
    return json.dumps(doc, ensure_ascii=False, indent=2) + "\n"


def build_table(row, sep):
    rows = [
        ["项目", "主体", "属性", "规定值", "文件编号", "归口部门"],
        [row[3], row[4], row[5], row[6], row[0], SUIT[row[1]][1]],
        [row[3], row[4], row[7], row[8], row[0], SUIT[row[1]][1]],
        ["适用范围", row[9], "生效日期", row[11], row[0], SUIT[row[1]][1]],
        ["例外审批", "归口部门负责人", "复核周期", "每年", row[0], SUIT[row[1]][1]],
    ]
    return "\n".join(sep.join(r) for r in rows) + "\n"


TEXT_BUILDERS = {
    "md": lambda r: build_md(r),
    "txt": lambda r: build_txt(r),
    "log": lambda r: build_log(r),
    "json": lambda r: build_json(r),
    "csv": lambda r: build_table(r, ","),
    "tsv": lambda r: build_table(r, "\t"),
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for blk in iter(lambda: f.read(1 << 16), b""):
            h.update(blk)
    return h.hexdigest()


def file_of(row):
    return os.path.join(CORPUS, row[2], "%s.%s" % (row[0], row[2]))


def phase_spec():
    for d in (CORPUS, SRC):
        os.makedirs(d, exist_ok=True)
    office_spec, texts = [], 0
    for row in D:
        fmt = row[2]
        os.makedirs(os.path.join(CORPUS, fmt), exist_ok=True)
        if fmt in OFFICE:
            office_spec.append({"code": row[0], "format": fmt, "path": file_of(row).replace(os.sep, "/"),
                                "title": row[3], "meta": meta_line(row), "sheet": SUIT[row[1]][0],
                                "params": [{"subject": row[4], "attribute": row[5], "value": row[6]},
                                           {"subject": row[4], "attribute": row[7], "value": row[8]}],
                                "blocks": as_blocks(row)})
        else:
            with open(file_of(row), "w", encoding="utf-8", newline="\n") as f:
                f.write(TEXT_BUILDERS[fmt](row))
            texts += 1
    with open(os.path.join(SRC, "office-spec.json"), "w", encoding="utf-8") as f:
        json.dump(office_spec, f, ensure_ascii=False, indent=1)
    mix = {}
    for row in D:
        mix[row[2]] = mix.get(row[2], 0) + 1
    print("文本类 %d 份已写出，office 类 %d 份待 Java 生成；格式分布=%s"
          % (texts, len(office_spec), json.dumps(mix, ensure_ascii=False, sort_keys=True)))


def phase_manifest():
    missing = [file_of(r) for r in D if not os.path.exists(file_of(r))]
    if missing:
        raise SystemExit("!! office 语料尚未生成（先执行 08-gen-office.sh）：%d 个文件缺失" % len(missing))
    with open(os.path.join(EV, "corpus-manifest.csv"), "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["code", "format", "kb_code", "kb_name", "title", "file", "bytes", "sha256",
                    "expect_subject", "expect_attribute", "expect_value"])
        for row in D:
            path = file_of(row)
            w.writerow([row[0], row[2], KBS[row[1]][0], KBS[row[1]][1], row[3],
                        os.path.relpath(path, ROOT).replace(os.sep, "/"), os.path.getsize(path), sha256(path),
                        row[4], row[5], row[6]])
    with open(os.path.join(EV, "questions.csv"), "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["qid", "kb_code", "type", "question", "expect_code", "expect_subject",
                    "expect_attribute", "expect_value", "expect_snippet"])
        n = 0
        for row in D:
            for q in ("%s的%s是多少？" % (row[4], row[5]),
                      "请依据《%s》说明%s的%s。" % (row[3], row[4], row[5])):
                n += 1
                w.writerow(["Q%03d" % n, KBS[row[1]][0], "answerable", q, row[0], row[4], row[5], row[6],
                            golden(row)])
        for q in REFUSALS:
            n += 1
            w.writerow(["Q%03d" % n, KBS["ops"][0], "refusal", q, "", "", "", "", ""])
    print("清单与问题集已写出：文档 %d 份，问题 %d 条（可答 %d / 拒答 %d）"
          % (len(D), n, n - len(REFUSALS), len(REFUSALS)))


if __name__ == "__main__":
    phase = sys.argv[1] if len(sys.argv) > 1 else "spec"
    (phase_spec if phase == "spec" else phase_manifest)()
