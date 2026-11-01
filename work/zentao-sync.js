// 将知识库项目分期计划同步到禅道（幂等：按名称跳过已存在项）
const fs = require('fs');
const BASE = 'http://127.0.0.1:8800/api.php/v1';
const TOKEN = process.env.ZT_TOKEN;
if (!TOKEN) throw new Error('missing ZT_TOKEN');
const plan = JSON.parse(fs.readFileSync('work/zentao-plan.json', 'utf8'));

async function api(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: { Token: TOKEN, 'Content-Type': 'application/json; charset=utf-8' },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : {};
}

async function main() {
  const proj = await api('GET', `/projects/${plan.project.id}`);
  if (proj.code !== plan.project.code || proj.desc !== plan.project.desc) {
    await api('PUT', `/projects/${plan.project.id}`, {
      name: proj.name, code: plan.project.code, desc: plan.project.desc
    });
    console.log(`项目已更新: ${proj.name} (${proj.begin} ~ ${proj.end})`);
  }

  const existingExecs = (await api('GET', `/projects/${plan.project.id}/executions`)).executions || [];
  let created = 0, skipped = 0;
  for (const phase of plan.phases) {
    let exec = existingExecs.find(e => e.name === phase.name);
    if (!exec) {
      exec = await api('POST', `/projects/${plan.project.id}/executions`, {
        name: phase.name, begin: phase.begin, end: phase.end, desc: phase.desc
      });
      created++;
      console.log(`+ 迭代 ${exec.id}: ${phase.name}`);
    } else {
      skipped++;
    }
    const tasks = (await api('GET', `/executions/${exec.id}/tasks?limit=1000`)).tasks || [];
    for (const t of phase.tasks) {
      if (tasks.some(x => x.name === t.name)) continue;
      await api('POST', `/executions/${exec.id}/tasks`, { ...t, assignedTo: 'admin' });
      created++;
      console.log(`  + 任务: ${t.name} (${t.estimate}h)`);
    }
  }
  console.log(`完成：新建 ${created} 项，跳过已存在 ${skipped} 个迭代。`);
}

main().catch(e => { console.error('失败:', e.message); process.exit(1); });
