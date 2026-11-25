import { writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

const BASE = 'http://127.0.0.1:18090';
const evidencePath = process.argv[2];
const evidence = { createdTasks: [], single: null, concurrent: [], recovery: null };
let writes = Promise.resolve();
async function saveEvidence() {
  const snapshot = JSON.stringify(evidence, null, 2);
  writes = writes.then(() => writeFile(evidencePath, snapshot, 'utf8'));
  await writes;
}
async function request(path, options = {}) {
  const response = await fetch(`${BASE}${path}`, { ...options, signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${path}`);
  return response;
}

async function uploadAndCreate(name, rows, invalidEvery, batchSize) {
  const prefix = `RMQ-${randomUUID().slice(0, 8).toUpperCase()}`;
  const lines = ['\uFEFFsku,name,category,price,stock\r\n'];
  let invalid = 0;
  for (let i = 1; i <= rows; i++) {
    const bad = invalidEvery > 0 && i % invalidEvery === 0;
    if (bad) invalid++;
    lines.push(`${prefix}-${i},"RMQ商品${i}",办公,${bad ? '-1' : '15.00'},5\r\n`);
  }
  const form = new FormData();
  form.append('file', new Blob([lines.join('')], { type: 'text/csv' }), `${name}.csv`);
  const upResp = await request('/api/uploads', { method: 'POST', body: form });
  const upload = await upResp.json();
  const taskResp = await request('/api/tasks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
    body: JSON.stringify({
      name, uploadId: upload.uploadId,
      mapping: { sku: 'sku', name: 'name', category: 'category', price: 'price', stock: 'stock' },
      batchSize, maxAttempts: 3,
    }),
  });
  const task = await taskResp.json();
  evidence.createdTasks.push({ id: task.id, name, totalRows: rows, expectedValid: rows - invalid, expectedError: invalid, prefix });
  await saveEvidence();
  return { task, expectedValid: rows - invalid, expectedError: invalid, prefix };
}

async function waitTerminal(id, timeoutMs = 60000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    const r = await request(`/api/tasks/${encodeURIComponent(id)}`);
    const d = await r.json();
    if (['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED'].includes(d.status)) return d;
    await new Promise(res => setTimeout(res, 250));
  }
  throw new Error(`Task ${id} timed out`);
}

// Scenario A: single RabbitMQ task
const single = await uploadAndCreate('RMQ-单任务验证', 120, 25, 40);
const singleDone = await waitTerminal(single.task.id);
evidence.single = { id: singleDone.id, status: singleDone.status, processedRows: singleDone.processedRows, successRows: singleDone.successRows, errorRows: singleDone.errorRows, checkpoint: singleDone.checkpoint };
await saveEvidence();
if (singleDone.status !== 'COMPLETED_WITH_ERRORS' || singleDone.processedRows !== 120 || singleDone.successRows !== single.expectedValid || singleDone.errorRows !== single.expectedError || singleDone.checkpoint !== 120) {
  throw new Error(`Scenario A mismatch: ${JSON.stringify(singleDone)}`);
}

// Four concurrent submissions; deterministic lock contention is tested separately.
const concurrent = await Promise.all([
  uploadAndCreate('RMQ-并发1', 200, 0, 50),
  uploadAndCreate('RMQ-并发2', 200, 0, 50),
  uploadAndCreate('RMQ-并发3', 200, 0, 50),
  uploadAndCreate('RMQ-并发4', 200, 0, 50),
]);
const concurrentDone = await Promise.all(concurrent.map(c => waitTerminal(c.task.id, 90000)));
evidence.concurrent = concurrentDone.map(d => ({ id: d.id, status: d.status, processedRows: d.processedRows, successRows: d.successRows, errorRows: d.errorRows, checkpoint: d.checkpoint }));
await saveEvidence();
for (const d of concurrentDone) {
  if (d.status !== 'COMPLETED' || d.processedRows !== 200 || d.successRows !== 200 || d.errorRows !== 0 || d.checkpoint !== 200) {
    throw new Error(`Scenario B failed (possible dropped message): ${JSON.stringify(d)}`);
  }
}

// Output recovery task created for PowerShell worker-kill test
const rec = await uploadAndCreate('RMQ-故障恢复实测', 600, 25, 100);
evidence.recovery = { id: rec.task.id, prefix: rec.prefix, expectedValid: rec.expectedValid, expectedError: rec.expectedError };
await saveEvidence();
