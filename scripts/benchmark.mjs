import { createWriteStream } from 'node:fs';
import { mkdir, mkdtemp, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';
import { createHash, randomUUID } from 'node:crypto';

const BASE_URL = (process.env.BATCHFLOW_BENCH_URL ?? 'http://127.0.0.1:18090').replace(/\/$/, '');
const PROJECT_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const POLL_INTERVAL_MS = 250;
const TASK_TIMEOUT_MS = 180000;
const runAbort = new AbortController();
const milliseconds = value => Number(value.toFixed(3));

function safeText(value) {
  return String(value)
    .replace(/(authorization|password|api[_-]?key|access[_-]?token)([\s"'=:\\]+)[^\s"',;]+/gi, '$1$2[REDACTED]')
    .replace(/bearer\s+[A-Za-z0-9._~+/-]+=*/gi, 'Bearer [REDACTED]')
    .slice(0, 12000);
}

function serializeError(error) {
  return { type: error?.name ?? 'Error', message: safeText(error?.message ?? error), stack: safeText(error?.stack ?? '') };
}

function parseOptions() {
  const args = process.argv.slice(2);
  let reportDir;
  for (let index = 0; index < args.length; index++) {
    if (args[index] === '--help' || args[index] === '-h') {
      console.log('Usage: node scripts/benchmark.mjs [--report-dir <new-run-directory>]\nThe output directory must not already exist. Default: .runtime/evidence/benchmark/<unique-run-id>/');
      return { help: true };
    }
    if (args[index] === '--report-dir' && args[index + 1] && !args[index + 1].startsWith('--')) {
      if (reportDir) throw new Error('--report-dir may only be supplied once');
      reportDir = resolve(args[++index]);
    } else {
      throw new Error(`Unknown or incomplete option: ${args[index]}`);
    }
  }
  return { reportDir };
}

async function saveReport(report, reportDir) {
  const temporary = join(reportDir, 'report.json.tmp');
  await writeFile(temporary, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  await rename(temporary, join(reportDir, 'report.json'));
}

async function request(path, init = {}) {
  runAbort.signal.throwIfAborted();
  const signal = AbortSignal.any([runAbort.signal, AbortSignal.timeout(TASK_TIMEOUT_MS)]);
  const response = await fetch(`${BASE_URL}${path}`, { ...init, signal });
  if (!response.ok) throw new Error(`${init.method ?? 'GET'} ${path}: HTTP ${response.status} ${safeText(await response.text())}`);
  return response.json();
}

async function generateCsv(filePath, rows, invalidEvery, prefix) {
  const start = performance.now();
  const stream = createWriteStream(filePath, { encoding: 'utf8', flags: 'wx' });
  const finished = once(stream, 'finish');
  const digest = createHash('sha256');
  finished.catch(() => {});
  const header = '\uFEFFsku,name,category,price,stock\r\n';
  digest.update(header);
  stream.write(header);
  let invalid = 0;
  try {
    for (let index = 1; index <= rows; index++) {
      runAbort.signal.throwIfAborted();
      const isInvalid = invalidEvery > 0 && index % invalidEvery === 0;
      if (isInvalid) invalid++;
      const line = `${prefix}-${index},"Bench Product ${index}",Bench,${isInvalid ? '-1.00' : '24.50'},10\r\n`;
      digest.update(line);
      if (!stream.write(line)) await once(stream, 'drain');
    }
    stream.end();
    await finished;
  } catch (error) {
    stream.destroy();
    throw error;
  }
  const info = await stat(filePath);
  return {
    prefix, rows, invalidEvery, generateMs: milliseconds(performance.now() - start),
    sizeBytes: info.size, sha256: digest.digest('hex'),
    expectedValid: rows - invalid, expectedError: invalid,
  };
}

async function uploadAndCreate(filePath, filename, taskName, batchSize, measurement) {
  const clientStart = performance.now();
  measurement.clientStartedAt = new Date().toISOString();
  measurement.stage = 'READING_FILE';
  const buffer = await readFile(filePath);
  const form = new FormData();
  form.append('file', new Blob([buffer], { type: 'text/csv' }), filename);
  measurement.fileReadAndFormMs = milliseconds(performance.now() - clientStart);

  measurement.stage = 'UPLOADING';
  const uploadStart = performance.now();
  const upload = await request('/api/uploads', { method: 'POST', body: form });
  measurement.uploadMs = milliseconds(performance.now() - uploadStart);
  measurement.uploadId = upload.uploadId;
  measurement.uploadTotalRows = upload.totalRows;

  measurement.stage = 'CREATING_TASK';
  const createStart = performance.now();
  const task = await request('/api/tasks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
    body: JSON.stringify({
      name: taskName, uploadId: upload.uploadId,
      mapping: { sku: 'sku', name: 'name', category: 'category', price: 'price', stock: 'stock' },
      batchSize, maxAttempts: 3,
    }),
  });
  measurement.createMs = milliseconds(performance.now() - createStart);
  measurement.taskId = task.id;
  measurement.taskCreatedAt = task.createdAt;
  measurement.submittedAt = new Date().toISOString();
  measurement.stage = 'WAITING_FOR_TERMINAL';
  return { clientStart, submittedMonotonic: performance.now(), task };
}

async function waitForTerminal(taskId, measurement, timeoutMs = TASK_TIMEOUT_MS) {
  const deadline = performance.now() + timeoutMs;
  measurement.pollCount = 0;
  while (performance.now() < deadline) {
    const detail = await request(`/api/tasks/${encodeURIComponent(taskId)}`);
    measurement.pollCount++;
    measurement.lastTaskSnapshot = Object.fromEntries([
      'id', 'status', 'totalRows', 'processedRows', 'successRows', 'errorRows', 'checkpoint',
      'attempt', 'maxAttempts', 'createdAt', 'startedAt', 'finishedAt',
    ].map(key => [key, detail[key] ?? null]));
    if (['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED'].includes(detail.status)) return detail;
    runAbort.signal.throwIfAborted();
    await new Promise(resolve => setTimeout(resolve, POLL_INTERVAL_MS));
  }
  throw new Error(`Task ${taskId} timed out after ${timeoutMs}ms`);
}

function validateTask(detail, generated, measurement) {
  const expectedStatus = generated.expectedError > 0 ? 'COMPLETED_WITH_ERRORS' : 'COMPLETED';
  const checks = {
    nonnegativeIntegerCounters: ['totalRows', 'processedRows', 'successRows', 'errorRows', 'checkpoint']
      .every(key => Number.isSafeInteger(detail[key]) && detail[key] >= 0),
    processedEqualsSuccessPlusError: detail.processedRows === detail.successRows + detail.errorRows,
    checkpointEqualsProcessed: detail.checkpoint === detail.processedRows,
    totalRowsMatchesInput: detail.totalRows === generated.rows,
    processedRowsMatchesInput: detail.processedRows === generated.rows,
    expectedSuccessRows: detail.successRows === generated.expectedValid,
    expectedErrorRows: detail.errorRows === generated.expectedError,
    completedWithExpectedStatus: detail.status === expectedStatus,
  };
  measurement.validation = checks;
  if (Object.values(checks).some(value => !value)) throw new Error(`Task ${detail.id} validation failed: ${JSON.stringify(checks)}`);
}

function addTerminalTimings(detail, starts, measurement, rows) {
  const observedAt = performance.now();
  measurement.terminalObservedAt = new Date().toISOString();
  measurement.e2eWaitMs = milliseconds(observedAt - starts.submittedMonotonic);
  measurement.clientEndToEndMs = milliseconds(observedAt - starts.clientStart);
  const workerStart = Date.parse(detail.startedAt);
  const workerFinish = Date.parse(detail.finishedAt);
  measurement.workerExecMs = Number.isFinite(workerStart) && Number.isFinite(workerFinish) && workerFinish > workerStart
    ? workerFinish - workerStart : null;
  measurement.workerRowsPerSec = measurement.workerExecMs === null ? null : Math.round(rows * 1000 / measurement.workerExecMs);
  measurement.e2eRowsPerSec = Math.round(rows * 1000 / Math.max(1, measurement.clientEndToEndMs));
  measurement.workerTimingAvailable = measurement.workerExecMs !== null;
}

async function runMeasuredTask(file, batchSize, measurement) {
  try {
    const starts = await uploadAndCreate(file.csvPath, file.filename, file.taskName, batchSize, measurement);
    const detail = await waitForTerminal(starts.task.id, measurement);
    addTerminalTimings(detail, starts, measurement, file.generated.rows);
    measurement.stage = 'VALIDATING';
    validateTask(detail, file.generated, measurement);
    Object.assign(measurement, {
      status: 'PASSED', stage: 'COMPLETE', taskStatus: detail.status,
      processedRows: detail.processedRows, successRows: detail.successRows, errorRows: detail.errorRows, checkpoint: detail.checkpoint,
    });
  } catch (error) {
    measurement.status = 'FAILED';
    measurement.failure = serializeError(error);
    throw error;
  }
}

async function runSingleBenchmark(dir, report, reportDir, label, rows, invalidEvery, batchSize) {
  const result = { label, status: 'RUNNING', stage: 'GENERATING', rows, batchSize, startedAt: new Date().toISOString() };
  report.results.push(result);
  await saveReport(report, reportDir);
  try {
    const csvPath = join(dir, `${label}.csv`);
    const generated = await generateCsv(csvPath, rows, invalidEvery, `BENCH-${randomUUID().slice(0, 8).toUpperCase()}`);
    result.input = generated;
    result.generateMs = generated.generateMs;
    result.fileSizeMiB = Number((generated.sizeBytes / (1024 * 1024)).toFixed(2));
    await runMeasuredTask({ csvPath, filename: `${label}.csv`, taskName: `压测-${label}`, generated }, batchSize, result);
  } catch (error) {
    result.status = 'FAILED';
    result.failure ??= serializeError(error);
    throw error;
  } finally {
    result.finishedAt = new Date().toISOString();
    await saveReport(report, reportDir);
  }
}

async function runConcurrentBurst(dir, report, reportDir, count, rowsPerTask, batchSize) {
  const result = {
    label: `concurrent-${count}x${rowsPerTask}`, status: 'RUNNING', stage: 'GENERATING',
    concurrentTasks: count, rowsPerTask, totalRows: count * rowsPerTask, batchSize,
    startedAt: new Date().toISOString(), tasks: [],
  };
  report.results.push(result);
  await saveReport(report, reportDir);
  try {
    const files = [];
    for (let index = 0; index < count; index++) {
      const csvPath = join(dir, `burst-${index}.csv`);
      const generated = await generateCsv(csvPath, rowsPerTask, 25, `BURST-${randomUUID().slice(0, 8).toUpperCase()}`);
      files.push({ csvPath, filename: `burst-${index}.csv`, taskName: `并发压测-${index}`, generated });
      result.tasks.push({ index, status: 'NOT_RUN', stage: 'PREPARED', input: generated });
    }
    result.stage = 'RUNNING_CONCURRENT_TASKS';
    const burstStart = performance.now();
    result.burstStartedAt = new Date().toISOString();
    const outcomes = await Promise.allSettled(files.map((file, index) => {
      result.tasks[index].status = 'RUNNING';
      return runMeasuredTask(file, batchSize, result.tasks[index]);
    }));
    result.totalWallMs = milliseconds(performance.now() - burstStart);
    result.burstFinishedAt = new Date().toISOString();
    result.aggregateRowsPerSec = outcomes.every(outcome => outcome.status === 'fulfilled')
      ? Math.round(result.totalRows * 1000 / Math.max(1, result.totalWallMs)) : null;
    result.validation = {
      everyTaskPassed: result.tasks.every(task => task.status === 'PASSED'),
      everyTaskProcessedEqualsSuccessPlusError: result.tasks.every(task => task.validation?.processedEqualsSuccessPlusError === true),
      everyTaskCheckpointEqualsProcessed: result.tasks.every(task => task.validation?.checkpointEqualsProcessed === true),
      totalProcessedMatchesInput: result.tasks.reduce((sum, task) => sum + (task.lastTaskSnapshot?.processedRows ?? 0), 0) === result.totalRows,
    };
    const failures = outcomes.filter(outcome => outcome.status === 'rejected');
    if (failures.length) throw new Error(`${failures.length}/${count} concurrent tasks failed; see tasks[].failure and lastTaskSnapshot`);
    if (Object.values(result.validation).some(value => !value)) throw new Error('Concurrent task aggregate invariant validation failed');
    result.status = 'PASSED';
    result.stage = 'COMPLETE';
  } catch (error) {
    result.status = 'FAILED';
    result.failure = serializeError(error);
    throw error;
  } finally {
    result.finishedAt = new Date().toISOString();
    await saveReport(report, reportDir);
  }
}

async function main() {
  const options = parseOptions();
  if (options.help) return;
  const runId = `${new Date().toISOString().replace(/[-:.]/g, '')}-${randomUUID().slice(0, 8)}`;
  const reportDir = options.reportDir ?? join(PROJECT_ROOT, '.runtime', 'evidence', 'benchmark', runId);
  await mkdir(dirname(reportDir), { recursive: true });
  await mkdir(reportDir);
  const started = performance.now();
  const report = {
    schemaVersion: 1, kind: 'local-benchmark', runId, status: 'RUNNING',
    startedAt: new Date().toISOString(), finishedAt: null, durationMs: null, reportDirectory: reportDir,
    baseUrl: null, runtime: { node: process.version, platform: process.platform, arch: process.arch },
    source: { script: 'scripts/benchmark.mjs', sha256: createHash('sha256').update(await readFile(fileURLToPath(import.meta.url))).digest('hex') },
    configuration: { pollIntervalMs: POLL_INTERVAL_MS, taskTimeoutMs: TASK_TIMEOUT_MS, invalidEvery: 25, requestedMaxAttempts: 3 },
    timingDefinitions: {
      clock: 'Client durations use monotonic performance.now(); ISO timestamps are UTC. Worker duration uses persisted server timestamps and is not substituted with client time.',
      generateMs: 'Stream CSV generation through finish and file stat, including input hashing; excluded from throughput denominators.',
      fileReadAndFormMs: 'Read the generated file into memory and construct Blob/FormData before upload.',
      uploadMs: 'Immediately before upload fetch through parsed JSON response; includes network, server upload/storage and preview parsing.',
      createMs: 'Immediately before task-create fetch through parsed JSON response; includes the task-create round trip.',
      e2eWaitMs: 'From receipt of task-create JSON response to receipt of the first terminal task-poll response; includes queue wait, execution and up to polling/network observation delay.',
      clientEndToEndMs: 'From before reading the CSV/FormData preparation to first terminal task-poll response; includes file preparation, upload, create, queue/execution and polling observation. Excludes CSV generation.',
      workerExecMs: 'Persisted finishedAt minus startedAt in milliseconds, including configured batch delays/recovery intervals; null if timestamps cannot yield a positive duration. Not CPU time.',
      workerRowsPerSec: 'Input rows * 1000 / workerExecMs; null when workerExecMs is unavailable.',
      e2eRowsPerSec: 'Input rows * 1000 / clientEndToEndMs, rounded to integer rows/s.',
      totalWallMs: 'Concurrent burst: immediately before parallel file-read/upload/create/poll tasks until every task settles; excludes prior fixture generation.',
      aggregateRowsPerSec: 'Sum of input rows * 1000 / totalWallMs, only when every concurrent task passes; otherwise null.',
    },
    limitations: ['Synthetic data on one configured endpoint; results do not establish multi-node or production capacity.', 'Worker count, Worker delay and hardware are not inferred from health and must be stated by the operator.', 'Each polling request has a timeout; terminal observation includes the 250ms polling interval.'],
    results: [], cleanupErrors: [],
  };
  const onInterrupt = () => runAbort.abort(new Error('Benchmark interrupted by SIGINT'));
  process.once('SIGINT', onInterrupt);
  let temporaryDirectory;
  await saveReport(report, reportDir);
  try {
    const target = new URL(BASE_URL);
    if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password || target.search || target.hash || target.pathname !== '/') {
      throw new Error('BATCHFLOW_BENCH_URL must be an HTTP(S) origin without credentials, query, fragment, or path');
    }
    report.baseUrl = target.origin;
    const health = await request('/api/health');
    report.health = { status: health.status, role: health.role, queueMode: health.queueMode };
    if (health.status !== 'UP' || health.role !== 'api') throw new Error('The selected endpoint did not report a healthy API role');
    temporaryDirectory = await mkdtemp(join(tmpdir(), 'batchflow-bench-'));
    await runSingleBenchmark(temporaryDirectory, report, reportDir, '10k-rows', 10000, 25, 500);
    await runSingleBenchmark(temporaryDirectory, report, reportDir, '100k-rows', 100000, 25, 1000);
    await runConcurrentBurst(temporaryDirectory, report, reportDir, 3, 5000, 500);
    report.status = 'PASSED';
  } catch (error) {
    report.status = 'FAILED';
    report.failure = serializeError(error);
    process.exitCode = runAbort.signal.aborted ? 130 : 1;
  } finally {
    if (temporaryDirectory) {
      try { await rm(temporaryDirectory, { recursive: true, force: true }); }
      catch (error) { report.cleanupErrors.push(serializeError(error)); report.status = 'FAILED'; process.exitCode = 1; }
    }
    process.removeListener('SIGINT', onInterrupt);
    report.finishedAt = new Date().toISOString();
    report.durationMs = milliseconds(performance.now() - started);
    report.resultCounts = { passed: report.results.filter(result => result.status === 'PASSED').length, failed: report.results.filter(result => result.status === 'FAILED').length, planned: 3 };
    await saveReport(report, reportDir);
  }
  console.log(JSON.stringify({ status: report.status, report: join(reportDir, 'report.json'), resultCounts: report.resultCounts }, null, 2));
}

main().catch(error => {
  console.error(safeText(error?.stack ?? error));
  process.exitCode = 1;
});
