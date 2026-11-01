#!/usr/bin/env node
'use strict';
/*
 * 交付物红线扫描。
 * 查：真实口令是否出现在交付物、可疑凭据字面量、镜像是否 tag+digest、
 * secrets/.env/私钥/证书是否被 .gitignore 覆盖、.gitignore 规则是否空转、报告里是否混入长正文。
 * ERROR 一律视为不可交付。
 */
const fs = require('fs');
const path = require('path');

const ERRORS = [];
const WARNINGS = [];
const NOTES = [];
const err = (m) => ERRORS.push(m);
const warn = (m) => WARNINGS.push(m);
const note = (m) => NOTES.push(m);

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

const HARD_SKIP = new Set(['.git', 'node_modules', '.venv', 'venv']);
const SCAN_SKIP = new Set([...HARD_SKIP, 'target', '.tmp', '.idea', '__pycache__', 'dist', 'corpus']);
const TEXT_EXT = /\.(md|java|yml|yaml|sql|py|sh|txt|cjs|js|json|conf|xml|properties)$/i;

const treeFiles = [];
const treeDirs = new Set();
(function walk(dir, filter) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (filter.has(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { treeDirs.add(rel(p)); walk(p, filter); }
    else treeFiles.push(p);
  }
})(ROOT, HARD_SKIP);
const DIR_NAMES = new Set([...treeDirs].map((d) => d.split('/').pop()));
const scanned = treeFiles.filter((f) => !SCAN_SKIP.has(rel(f).split('/')[0]) &&
  !rel(f).split('/').some((s) => SCAN_SKIP.has(s)) && fs.statSync(f).size < 400000);
const secretFiles = scanned.filter((f) => /(^|\/)secrets\//.test(rel(f)) || /(^|\/)\.env$/.test(rel(f)));
const deliverables = scanned.filter((f) => !secretFiles.includes(f) && TEXT_EXT.test(path.basename(f)));

/* ---------- .gitignore ---------- */
const ignoreLines = (fs.existsSync(path.join(ROOT, '.gitignore'))
  ? fs.readFileSync(path.join(ROOT, '.gitignore'), 'utf8').split(/\r?\n/).map((l) => l.trim())
  : []).filter((l) => l && !l.startsWith('#'));
function isIgnored(rp) {
  return ignoreLines.some((raw) => {
    const anchored = raw.startsWith('/');
    const pat = anchored ? raw.slice(1) : raw;
    if (pat.endsWith('/')) {
      // 带目录的斜杠模式在 git 里相对 .gitignore 所在目录，只有前缀匹配才算被排除
      if (pat.includes('/')) return rp.startsWith(pat) || (!anchored && rp.includes('/' + pat));
      return rp.split('/').includes(pat.slice(0, -1));
    }
    if (pat.startsWith('*.')) return rp.endsWith(pat.slice(1));
    return rp === pat || rp.startsWith(pat + '/') || (!anchored && pat.includes('/') && rp.includes('/' + pat));
  });
}

/* ---------- 1. 真实凭据值出现在交付物 ---------- */
const SECRET_SOURCE = /(password|passwd|secret|token)\.txt$/i; // 账号名/access key 是标识符，不比对值
const DOC_EXT = /\.(md|yaml|yml|json|sql|conf|properties|xml|example)$/i;
const secretValues = [];
for (const f of secretFiles) {
  if (!SECRET_SOURCE.test(path.basename(f))) continue;
  const v = fs.readFileSync(f, 'utf8').trim();
  if (v.length >= 6 && !/^(<|changeme|replace|TODO|xxx)/i.test(v)) secretValues.push({ v, from: rel(f) });
}
for (const f of deliverables) {
  const rp = rel(f);
  fs.readFileSync(f, 'utf8').split(/\r?\n/).forEach((line, i) => {
    for (const s of secretValues) {
      if (s.from === rp || !line.includes(s.v)) continue;
      const msg = `${rp}:${i + 1} 出现真实凭据值（来自 ${s.from}）`;
      if (DOC_EXT.test(rp)) err(`${msg} → 交付文档必须只写占位符与哈希`);
      else note(`（已计入下方 WARN）${msg}`);
    }
  });
}
const codeHits = new Set(NOTES.splice(0).map((m) => m.replace(/^（已计入下方 WARN）/, '')));
for (const h of codeHits) warn(`${h} → 改为 \${ENV}/` + `*_FILE，或从代码里移除默认值`);

/* ---------- 2. 可疑硬编码凭据字面量（只查配置与文档，避开代码表达式误报） ---------- */
const CRED_RE = /(password|passwd|secret[-_]?key|access[-_]?key|api[-_]?key|client[-_]?secret|token)\s*[:=]\s*['"`]?([^\s'"`,)]{8,})/gi;
const LOOKS_LITERAL = /^[A-Za-z0-9+\/=_\-.@#%&*!?]{8,}$/;
const PLACEHOLDER = /^(<.*>|\{.*\}|\$\{.*\}|\$\(.*\)|https?:|[\w.\/-]*\.(txt|key|crt)$|changeme\w*|replace\w*|placeholder|example\w*|null|empty|your\w*|x+|\*+|.*_FILE.*|.*[Ss]ecrets?.*|[A-Za-z][A-Za-z0-9]*[A-Z]\w*)$/;
for (const f of deliverables) {
  const rp = rel(f);
  if (!DOC_EXT.test(rp)) continue;
  fs.readFileSync(f, 'utf8').split(/\r?\n/).forEach((line, i) => {
    for (const m of line.matchAll(CRED_RE)) {
      const val = m[2];
      if (!LOOKS_LITERAL.test(val) || PLACEHOLDER.test(val)) continue;
      if (!/\d/.test(val) && !/[^\w]/.test(val)) continue; // 纯字典词不判
      warn(`${rp}:${i + 1} 疑似硬编码凭据字面量 ${m[1].trim()}=<${val.slice(0, 2)}***>（应走占位符或 *_FILE）`);
    }
  });
}

/* ---------- 3. 镜像必须 tag + digest（ADR-001） ---------- */
for (const f of deliverables) {
  const rp = rel(f);
  if (!/docker-compose[\w.]*\.ya?ml$|Dockerfile$/.test(path.basename(f))) continue;
  fs.readFileSync(f, 'utf8').split(/\r?\n/).forEach((line, i) => {
    const m = line.match(/^\s*(?:-\s*)?(?:image|FROM)\s+([^\s#'"]+)/);
    if (!m) return;
    const img = m[1];
    if (/^(build|\.\/)/.test(img) || img.includes('{')) return;
    if (/:latest$/.test(img) || !img.split('@')[0].includes(':')) err(`${rp}:${i + 1} 镜像未固定 tag：${img}（禁止 latest / 裸标签）`);
    else if (!img.includes('@sha256:')) warn(`${rp}:${i + 1} 镜像未锁 digest：${img}（本地构建需在 image-manifest 登记摘要）`);
  });
}

/* ---------- 4. 凭据/密钥/证书必须被 .gitignore 覆盖 ---------- */
for (const f of treeFiles) {
  const rp = rel(f);
  if (SCAN_SKIP.has(rp.split('/')[0])) continue;
  const risky = /(^|\/)secrets\//.test(rp) || /(^|\/)\.env$/.test(rp) || /\.(key|pem|p12|pfx)$/.test(rp) || /(^|\/)certs\//.test(rp);
  if (!risky || /\.example$|README/.test(rp)) continue;
  if (!isIgnored(rp)) err(`${rp} 属本机凭据/密钥，但未被 .gitignore 覆盖 → 补规则`);
  else note(`${rp} 已被 .gitignore 排除`);
}

/* ---------- 5. .gitignore 规则是否空转（写错路径＝排除形同虚设） ---------- */
for (const pat of ignoreLines) {
  if (/[{}\[\]]/.test(pat)) continue;
  const probe = pat.replace(/\/$/, '');
  const hit = pat.endsWith('/')
    ? treeDirs.has(probe) || DIR_NAMES.has(probe.split('/').pop())
    : pat.startsWith('*.')
      ? treeFiles.some((f) => rel(f).endsWith(pat.slice(1)))
      : treeFiles.some((f) => { const r = rel(f); return r === probe || r.startsWith(probe + '/') || r.endsWith('/' + probe) || treeDirs.has(probe); });
  if (!hit) {
    const critical = /secret|\.env|corpus|quality-raw|\.tmp|\.log|target|creds?|cert/i.test(pat);
    const msg = `.gitignore 规则未命中任何路径：${pat} → 路径可能写错，排除无效`;
    // 父目录存在、只是本机尚未生成该文件（如 .env 由 cp .env.example 产生）→ 规则本身有效
    const parentOk = !pat.endsWith('/') &&
      path.posix.dirname(probe) !== '.' && fs.existsSync(path.join(ROOT, path.posix.dirname(probe)));
    if (critical && !parentOk) err(msg);
    else warn(msg + (parentOk ? '（父目录在，本机文件尚未生成，规则有效）' : '（非关键路径，可为将来预留）'));
  }
}

/* ---------- 6. 交付文档里的个人主机绝对路径 ---------- */
for (const f of deliverables) {
  if (!/\.md$/.test(path.basename(f))) continue;
  fs.readFileSync(f, 'utf8').split(/\r?\n/).forEach((line, i) => {
    if (/[A-Za-z]:[\\/][Uu]sers/.test(line)) warn(`${rel(f)}:${i + 1} 含个人主机绝对路径（换机即失效）`);
  });
}

/* ---------- 7. 证据报告里是否混入文档正文/提示词 ---------- */
for (const f of deliverables) {
  if (!/evaluation[\/\\]reports|decisions/.test(rel(f))) continue;
  const n = ((fs.readFileSync(f, 'utf8').match(/[一-龥A-Za-z0-9 ,.;:()（）—、]{160,}/g) || [])).length;
  if (n > 2) warn(`${rel(f)} 含 ${n} 处 160+ 字符连续正文，报告应只留哈希与计数`);
}

process.stdout.write('== 交付物红线扫描 ==\n');
const show = (label, arr, cap = 20) => {
  process.stdout.write(`${label}：${arr.length} 项\n`);
  arr.slice(0, cap).forEach((m) => process.stdout.write(`  ${m}\n`));
  if (arr.length > cap) process.stdout.write(`  …另 ${arr.length - cap} 项\n`);
};
show('ERROR', ERRORS, 40);
show('WARN', WARNINGS, 30);
if (process.argv.includes('--all')) show('INFO', NOTES);
else if (NOTES.length) process.stdout.write(`INFO：${NOTES.length} 项（--all 展开）\n`);
process.stdout.write(`\n扫描交付文本 ${deliverables.length} 个，比对凭据源 ${secretValues.length} 条，仓库文件 ${treeFiles.length} 个。\n`);
process.stdout.write(`${ERRORS.length} error / ${WARNINGS.length} warning → ${ERRORS.length ? '不可交付' : '通过'}\n`);
process.exit(ERRORS.length ? 1 : 0);
