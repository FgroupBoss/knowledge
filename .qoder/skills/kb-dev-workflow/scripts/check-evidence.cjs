#!/usr/bin/env node
'use strict';
/*
 * 证据链与引用完整性门禁。
 * 查：ADR 索引↔文件↔证据路径、backlog 登记字段、仓库内引用的契约/报告/脚本路径是否真的存在、
 * Kafka 主题是否在冻结契约登记、证据报告是否被引用。
 * ERROR = 现有交付物的引用落不了地（契约/ADR/代码在骗人）；WARN = 设计文档里尚未落地的规划路径。
 */
const fs = require('fs');
const path = require('path');

const ERRORS = [];
const WARNINGS = [];
const err = (m) => ERRORS.push(m);
const warn = (m) => WARNINGS.push(m);

function findRoot(start) {
  let d = start;
  for (let i = 0; i < 12; i++) {
    if (fs.existsSync(path.join(d, 'p0', 'decisions')) && fs.existsSync(path.join(d, 'outputs'))) return d;
    const up = path.dirname(d);
    if (up === d) break;
    d = up;
  }
  return path.resolve(__dirname, '..', '..', '..', '..');
}
const ROOT = findRoot(__dirname);
const rel = (p) => path.relative(ROOT, p).replace(/\\/g, '/');
const read = (p) => (fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : null);

const SKIP_DIRS = new Set(['.git', 'node_modules', 'target', '.tmp', '.idea', '__pycache__', 'dist', 'venv', '.venv']);
function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (SKIP_DIRS.has(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}
const ALL = walk(ROOT, []).map(rel);
const BY_NAME = new Map();
for (const r of ALL) {
  const b = r.split('/').pop();
  if (!BY_NAME.has(b)) BY_NAME.set(b, []);
  BY_NAME.get(b).push(r);
}

/* ---------- 引用解析 ---------- */
const EXT = /\.(md|json|ya?ml|sql|py|sh|csv|txt|java|cjs|js|html|xml|jar)$/i;
const SKIP_REF = /[*<>…]|^https?:|^mailto:|xxx|placeholder|^\d+$|=|×|\s/;
const BASES = ['.', 'p0', 'p1', 'outputs'];

function resolveRef(ref) {
  const clean = ref.replace(/^\.\//, '').replace(/^\/+/, '');
  if (!clean || SKIP_REF.test(clean)) return { skip: true };
  if (!clean.includes('/')) return BY_NAME.has(clean) ? { ok: true } : { kind: 'file', dir: '' };
  const abs = rel(path.resolve(ROOT, clean));
  for (const b of BASES) {
    const cand = b === '.' ? clean : b + '/' + clean;
    if (ALL.includes(cand)) return { ok: true };
  }
  for (const r of ALL) if (r === abs || r.endsWith('/' + clean)) return { ok: true };
  const parent = clean.split('/').slice(0, -1).join('/');
  const parentExists = BASES.some((b) => fs.existsSync(path.join(b === '.' ? ROOT : path.join(ROOT, b), parent)));
  return parentExists ? { kind: 'file', dir: parent } : { kind: 'dir', dir: clean.split('/')[0] };
}

function reportRef(ref, fromFile, hint, planned) {
  const r = resolveRef(ref);
  if (r.skip || r.ok) return false;
  const msg = `${rel(fromFile)} → ${ref}`;
  const isPlan = planned === true || rel(fromFile).startsWith('outputs/');
  const line = `${msg}${hint ? ' ' + hint : ''}`;
  if (isPlan) warn(`待产出路径（尚未落地）：${line}`);
  else if (r.kind === 'file') err(`引用落空（目录在、文件不在）：${line}`);
  else warn(`规划路径未落地：${line}`);
  return true;
}

/* ---------- markdown 表格 ---------- */
function tableRows(file) {
  const text = read(file);
  if (!text) return null;
  const rows = [];
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i].trim();
    if (!t.startsWith('|')) continue;
    const cells = t.split('|').slice(1, -1).map((c) => c.trim().replace(/<br>/g, ' / '));
    if (cells.every((c) => /^[-: ]*$/.test(c))) continue;
    rows.push({ line: i + 1, cells });
  }
  return { rows, text };
}
const backticked = (s) => (s.match(/`([^`]+)`/g) || []).map((m) => m.slice(1, -1));
const fileLike = (s) => s.includes('/') || EXT.test(s);

/* ---------- ADR 索引 ---------- */
const DEC = path.join(ROOT, 'p0', 'decisions');
const IDX = path.join(DEC, 'README.md');
const adrNums = new Set();
{
  const parsed = tableRows(IDX);
  if (!parsed) err('缺少 ADR 索引：p0/decisions/README.md');
  else {
    for (const { line, cells } of parsed.rows) {
      const m = cells[0].match(/^ADR-(\d{3})/);
      if (!m) continue;
      adrNums.add(m[1]);
      if (cells.length < 4) { err(`ADR 索引第 ${line} 行列数不足（需 编号|主题|状态|证据）`); continue; }
      if (!cells[2]) err(`ADR-${m[1]} 状态为空（${rel(IDX)}:${line}）`);
      const ev = backticked(cells[3]).filter(fileLike);
      if (!ev.length) warn(`ADR-${m[1]} 证据列没有可核对的文件引用（${rel(IDX)}:${line}）`);
      for (const ref of ev) reportRef(ref, IDX, '（索引声明的证据）');
      const hit = fs.existsSync(DEC) ? fs.readdirSync(DEC).filter((f) => f.startsWith(`ADR-${m[1]}-`)) : [];
      if (!hit.length) err(`索引登记 ADR-${m[1]} 但文件不存在`);
      for (const f of hit) {
        const body = read(path.join(DEC, f)) || '';
        if (!/证据|报告|复测|验证|门槛/.test(body)) warn(`${rel(path.join(DEC, f))} 正文缺少证据/验证字段`);
      }
    }
    for (const f of fs.readdirSync(DEC).filter((f) => /^ADR-\d{3}-/.test(f))) {
      const num = f.match(/^ADR-(\d{3})-/)[1];
      if (!adrNums.has(num)) err(`ADR 文件未登记进索引：p0/decisions/${f}`);
    }
  }
}

/* ---------- P1 backlog ---------- */
const BL = path.join(DEC, 'P1-backlog.md');
{
  const parsed = tableRows(BL);
  if (!parsed) err('缺少 p0/decisions/P1-backlog.md');
  else {
    const seen = new Set();
    for (const { line, cells } of parsed.rows) {
      if (!/^\d+$/.test(cells[0])) continue;
      const n = cells[0];
      if (cells.length !== 7) { err(`backlog #${n} 列数为 ${cells.length}，应为 7（#|级别|事项|来源|关闭证据|责任人|目标）（第 ${line} 行）`); continue; }
      if (seen.has(n)) err(`backlog 编号重复：#${n}（编号一经登记不再变动）`);
      seen.add(n);
      const [lvl, , source, evidence, owner, target] = cells.slice(1);
      if (!['阻断', '高', '中', '低'].includes(lvl)) err(`backlog #${n} 级别异常：「${lvl}」`);
      if (!evidence) err(`backlog #${n} 缺「关闭证据」——不接受"改完了"`);
      if (!owner) err(`backlog #${n} 缺责任人`);
      if (!target) err(`backlog #${n} 缺目标日期`);
      for (const a of source.match(/ADR-\d{3}/g) || []) if (!adrNums.has(a.slice(4))) err(`backlog #${n} 来源引用 ${a} 不在 ADR 索引`);
      for (const ref of backticked(evidence).filter(fileLike)) reportRef(ref, BL, `（backlog #${n} 关闭证据）`, true);
    }
    if (!seen.size) err('P1-backlog.md 没有解析到任何登记项');
  }
}

/* ---------- 全仓库路径引用扫描 ---------- */
const REF_RE = /(?:p0|p1|outputs|contracts|evaluation|decisions|scripts|compose|db|runbooks|portal|portal-poc)\/[\w.\-\/@]*[A-Za-z0-9@_]\.(?:jsonl|md|json|ya?ml|sql|py|sh|csv|txt|java|cjs|jar)(?![\w])/g;
const files = ALL.filter((r) => /\.(md|java|yml|yaml|sql|py|sh|txt|cjs)$/i.test(r) && !r.startsWith('.qoder/')).map((r) => path.join(ROOT, r));
let refChecked = 0;
for (const f of files) {
  const text = read(f);
  if (!text || text.length > 400000) continue;
  for (const m of text.matchAll(REF_RE)) { refChecked++; reportRef(m[0], f, '（正文引用）'); }
}

/* ---------- Kafka 主题登记 ---------- */
{
  const contractText = read(path.join(ROOT, 'p0', 'contracts', 'kafka', 'README.md')) || '';
  const declared = new Set(contractText.match(/kb\.[a-z0-9.]+\.v\d+/g) || []);
  if (!declared.size) err('p0/contracts/kafka/README.md 里找不到主题清单');
  const used = new Map();
  for (const f of files) {
    const r = rel(f);
    if (!/application\.yml|KbProperties\.java|docker-compose[\w.]*\.ya?ml|compose\/README\.md|p1\/compose/.test(r)) continue;
    const t = read(f) || '';
    for (const m of t.matchAll(/kb\.[a-z0-9.]+\.v\d+/g)) if (!used.has(m[0])) used.set(m[0], r);
  }
  for (const [topic, where] of used) if (!declared.has(topic)) err(`主题 ${topic} 被使用但未登记在冻结契约（首次出现：${where}）`);
}

/* ---------- 证据报告是否被引用 ---------- */
{
  const dir = path.join(ROOT, 'p0', 'evaluation', 'reports');
  if (fs.existsSync(dir)) {
    const corpus = files.filter((f) => /decisions|README\.md$|^outputs|AGENTS\.md$/.test(rel(f))).map((f) => read(f) || '').join('\n');
    for (const f of fs.readdirSync(dir).filter((x) => x.endsWith('.md'))) {
      if (!corpus.includes(f)) warn(`证据报告未被任何索引/文档引用：p0/evaluation/reports/${f}`);
    }
  }
}

process.stdout.write('== 证据链与引用完整性 ==\n');
const show = (label, arr) => {
  process.stdout.write(`${label}：${arr.length} 项\n`);
  arr.slice(0, 25).forEach((m) => process.stdout.write(`  ${m}\n`));
  if (arr.length > 25) process.stdout.write(`  …另 ${arr.length - 25} 项（同类）\n`);
};
show('ERROR', ERRORS);
show('WARN', WARNINGS);
process.stdout.write(`\n核对正文引用 ${refChecked} 条；ADR ${adrNums.size} 份；仓库文件 ${ALL.length} 个。\n`);
process.stdout.write(`${ERRORS.length} error / ${WARNINGS.length} warning → ${ERRORS.length ? '漂移：修引用或补文件' : '通过'}\n`);
process.exit(ERRORS.length ? 1 : 0);
