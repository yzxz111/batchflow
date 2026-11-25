import { createWriteStream } from 'node:fs';
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';

const BASE_URL = process.env.BATCHFLOW_BENCH_URL ?? 'http://127.0.0.1:18090';

async function generateCsv(filePath, rows, invalidEvery, prefix) {
  const start = performance.now();
  const stream = createWriteStream(filePath, { encoding: 'utf8' });
  const finished = once(stream, 'finish');
  stream.write('\uFEFFsku,name,category,price,stock\r\n');
  let invalid = 0;
  for (let i = 1; i <= rows; i++) {
    const isInvalid = invalidEvery > 0 && i % invalidEvery === 0;
    if (isInvalid) invalid++;
    const line = `${prefix}-${i},"Bench Product ${i}",Bench,${isInvalid ? '-1.00' : '24.50'},10\r\n`;
    if (!stream.write(line)) await once(stream, 'drain');
  }
  stream.end();
  await finished;
  const info = await stat(filePath);
  return {
    generateMs: Math.round(performance.now() - start),
    sizeBytes: info.size,
    expectedValid: rows - invalid,
    expectedError: invalid,
  };
}

async function uploadAndCreate(filePath, filename, taskName, batchSize) {
  const buf = await readFile(filePath);
  const form = new FormData();
  form.append('file', new Blob([buf], { type: 'text/csv' }), filename);

  const upStart = performance.now();
  const upResp = await fetch(`${BASE_URL}/api/uploads`, { method: 'POST', body: form });
  if (!upResp.ok) throw new Error(`Upload failed: HTTP ${upResp.status} ${await upResp.text()}`);
  const upload = await upResp.json();
  const uploadMs = Math.round(performance.now() - upStart);

  const taskStart = performance.now();
  const taskResp = await fetch(`${BASE_URL}/api/tasks`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': randomUUID(),
    },
    body: JSON.stringify({
      name: taskName,
      uploadId: upload.uploadId,
      mapping: { sku: 'sku', name: 'name', category: 'category', price: 'price', stock: 'stock' },
      batchSize,
      maxAttempts: 3,
    }),
  });
  if (!taskResp.ok) throw new Error(`Create task failed: HTTP ${taskResp.status} ${await taskResp.text()}`);
  const task = await taskResp.json();
  const createMs = Math.round(performance.now() - taskStart);

  return { upload, uploadMs, task, createMs, submittedAt: performance.now() };
}

async function waitForTerminal(taskId, timeoutMs = 180000) {
  const deadline = performance.now() + timeoutMs;
  while (performance.now() < deadline) {
    const resp = await fetch(`${BASE_URL}/api/tasks/${encodeURIComponent(taskId)}`);
    if (!resp.ok) throw new Error(`Poll failed: HTTP ${resp.status}`);
    const detail = await resp.json();
    if (['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED'].includes(detail.status)) {
      return detail;
    }
    await new Promise(r => setTimeout(r, 250));
  }
  throw new Error(`Task ${taskId} timed out after ${timeoutMs}ms`);
}

async function runSingleBenchmark(dir, label, rows, invalidEvery, batchSize) {
  const prefix = `BENCH-${randomUUID().slice(0, 8).toUpperCase()}`;
  const csvPath = join(dir, `${label}.csv`);
  const gen = await generateCsv(csvPath, rows, invalidEvery, prefix);
  const { uploadMs, task, createMs, submittedAt } = await uploadAndCreate(
    csvPath,
    `${label}.csv`,
    `压测-${label}`,
    batchSize,
  );
  const detail = await waitForTerminal(task.id);
  const e2eWaitMs = Math.round(performance.now() - submittedAt);
  const workerExecMs = detail.startedAt && detail.finishedAt
    ? Math.max(1, new Date(detail.finishedAt).getTime() - new Date(detail.startedAt).getTime())
    : e2eWaitMs;

  if (detail.processedRows !== detail.successRows + detail.errorRows || detail.checkpoint !== detail.processedRows) {
    throw new Error(`Invariant mismatch on ${task.id}`);
  }
  if (detail.successRows !== gen.expectedValid || detail.errorRows !== gen.expectedError) {
    throw new Error(`Expected ${gen.expectedValid}/${gen.expectedError}, got ${detail.successRows}/${detail.errorRows}`);
  }

  return {
    label,
    rows,
    batchSize,
    fileSizeMiB: Number((gen.sizeBytes / (1024 * 1024)).toFixed(2)),
    generateMs: gen.generateMs,
    uploadMs,
    createMs,
    e2eWaitMs,
    workerExecMs,
    workerRowsPerSec: Math.round((rows * 1000) / workerExecMs),
    e2eRowsPerSec: Math.round((rows * 1000) / (uploadMs + createMs + e2eWaitMs)),
    status: detail.status,
    successRows: detail.successRows,
    errorRows: detail.errorRows,
  };
}

async function runConcurrentBurst(dir, count, rowsPerTask, batchSize) {
  const files = [];
  for (let i = 0; i < count; i++) {
    const prefix = `BURST-${randomUUID().slice(0, 8).toUpperCase()}`;
    const csvPath = join(dir, `burst-${i}.csv`);
    const gen = await generateCsv(csvPath, rowsPerTask, 25, prefix);
    files.push({ csvPath, gen, idx: i });
  }

  const burstStart = performance.now();
  const submitted = await Promise.all(
    files.map(f => uploadAndCreate(f.csvPath, `burst-${f.idx}.csv`, `并发压测-${f.idx}`, batchSize)),
  );
  const details = await Promise.all(submitted.map(s => waitForTerminal(s.task.id)));
  const totalWallMs = Math.round(performance.now() - burstStart);
  const totalRows = count * rowsPerTask;
  for (let i = 0; i < details.length; i++) {
    const d = details[i];
    const g = files[i].gen;
    if (d.successRows !== g.expectedValid || d.errorRows !== g.expectedError) {
      throw new Error(`Burst invariant mismatch on ${d.id}`);
    }
  }
  return {
    label: `concurrent-${count}x${rowsPerTask}`,
    concurrentTasks: count,
    totalRows,
    batchSize,
    totalWallMs,
    aggregateRowsPerSec: Math.round((totalRows * 1000) / totalWallMs),
  };
}

async function main() {
  const healthResp = await fetch(`${BASE_URL}/api/health`);
  if (!healthResp.ok) throw new Error(`API not healthy: ${healthResp.status}`);
  const health = await healthResp.json();

  const tempDir = await mkdtemp(join(tmpdir(), 'batchflow-bench-'));
  try {
    const results = [];
    results.push(await runSingleBenchmark(tempDir, '10k-rows', 10000, 25, 500));
    results.push(await runSingleBenchmark(tempDir, '100k-rows', 100000, 25, 1000));
    results.push(await runConcurrentBurst(tempDir, 3, 5000, 500));
    console.log(JSON.stringify({ health, results }, null, 2));
  } finally {
    await rm(tempDir, { recursive: true, force: true });
  }
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
