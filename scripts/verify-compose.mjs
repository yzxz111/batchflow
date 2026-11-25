import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { appendFile, mkdir, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { dirname, join, resolve } from 'node:path';
import { finished } from 'node:stream/promises';
import { setTimeout as sleep } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

// Requires Node 24, Docker Engine and Docker Compose v2. It builds and starts
// only its randomly named project, then removes that project's containers,
// network, volumes and image. It never uses the default local project's ports.
if (process.argv.includes('--help')) {
  console.log('Usage: node scripts/verify-compose.mjs\n'
    + 'Build and verify an isolated Docker Compose stack; preserve evidence in\n'
    + 'test-results/compose/<run-id> (override with BATCHFLOW_COMPOSE_REPORT_DIR).\n'
    + 'Requires a running Docker Engine and Compose v2. No tools are installed.');
  process.exit(0);
}
assert.equal(process.argv.length, 2, 'Only --help is supported.');

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const runId = `${new Date().toISOString().replace(/[:.]/g, '-')}-${randomUUID().slice(0, 8)}`;
const reportRoot = resolve(root, process.env.BATCHFLOW_COMPOSE_REPORT_DIR ?? 'test-results/compose');
const reportDir = join(reportRoot, runId);
const project = `batchflow-smoke-${randomUUID().slice(0, 12)}`;
const composePrefix = ['compose', '--project-name', project, '--file', 'deploy/compose.yaml'];
const report = {
  schemaVersion: 1,
  status: 'RUNNING',
  startedAt: new Date().toISOString(),
  commit: process.env.GITHUB_SHA ?? null,
  project,
  reportDir,
  checks: [],
};
let env = { ...process.env, COMPOSE_ANSI: 'never', COMPOSE_IGNORE_ORPHANS: 'true' };
let commandNumber = 0;
let managedProject = false;
let imageBuilt = false;
let failure;
const reservations = [];
await mkdir(reportDir, { recursive: true });

async function saveReport() {
  await writeFile(join(reportDir, 'report.json'), `${JSON.stringify(report, null, 2)}\n`);
}

async function command(label, args, { allowFailure = false, timeoutMs = 120000 } = {}) {
  const number = String(++commandNumber).padStart(3, '0');
  const basename = `${number}-${label}`;
  const stdout = createWriteStream(join(reportDir, `${basename}.stdout.log`));
  const stderr = createWriteStream(join(reportDir, `${basename}.stderr.log`));
  // Always drain both streams to disk, including failed builds and cleanup.
  const outputsDone = Promise.allSettled([finished(stdout), finished(stderr)]);
  const startedAt = new Date().toISOString();
  let output = '';
  let launchError;
  let timedOut = false;
  const child = spawn('docker', args, { cwd: root, env, windowsHide: true });
  child.stdout.on('data', chunk => {
    stdout.write(chunk);
    if (output.length < 2 * 1024 * 1024) output += chunk.toString();
  });
  child.stderr.on('data', chunk => stderr.write(chunk));
  child.once('error', error => { launchError = error; });
  const timeout = setTimeout(() => { timedOut = true; child.kill('SIGKILL'); }, timeoutMs);
  const code = await new Promise(resolveCode => child.once('close', resolveCode));
  clearTimeout(timeout);
  stdout.end();
  stderr.end();
  const written = await outputsDone;
  const logError = written.find(item => item.status === 'rejected');
  await appendFile(join(reportDir, 'commands.jsonl'), `${JSON.stringify({
    label, command: ['docker', ...args], startedAt,
    finishedAt: new Date().toISOString(), code, timedOut,
    error: launchError?.message ?? (logError ? String(logError.reason) : null),
    stdout: `${basename}.stdout.log`, stderr: `${basename}.stderr.log`,
  })}\n`);
  if (launchError || logError || timedOut || code !== 0) {
    const message = launchError?.message ?? (logError ? String(logError.reason)
      : timedOut ? 'command timed out' : `exit code ${code}`);
    if (!allowFailure) throw new Error(`${label}: ${message}; see ${basename}.*.log`);
    return { ok: false, output, code };
  }
  return { ok: true, output, code };
}

function compose(label, args, options) {
  return command(label, [...composePrefix, ...args], options);
}

async function check(name, operation) {
  const startedAt = new Date().toISOString();
  try {
    const details = await operation();
    report.checks.push({ name, status: 'PASSED', startedAt, finishedAt: new Date().toISOString(), details });
    await saveReport();
    console.log(`PASS ${name}`);
    return details;
  } catch (error) {
    report.failureStage = name;
    report.checks.push({ name, status: name === 'docker_preflight' ? 'BLOCKED' : 'FAILED',
      startedAt, finishedAt: new Date().toISOString(), error: error.message });
    throw error;
  }
}

async function reservePort() {
  const server = createServer();
  await new Promise((resolveListen, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolveListen);
  });
  reservations.push(server);
  return server.address().port;
}

async function releasePorts() {
  await Promise.all(reservations.splice(0).map(server => new Promise((resolveClose, reject) => {
    server.close(error => error ? reject(error) : resolveClose());
  })));
}

let baseUrl;
async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, { ...options, signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error(`${options.method ?? 'GET'} ${path}: HTTP ${response.status} ${await response.text()}`);
  return response;
}

async function json(path, options) {
  return (await request(path, options)).json();
}

async function submit(label, rows, invalidEvery, batchSize) {
  const prefix = `CMP-${randomUUID().slice(0, 8).toUpperCase()}`;
  const lines = ['\uFEFFsku,name,category,price,stock\r\n'];
  for (let i = 1; i <= rows; i++) {
    lines.push(`${prefix}-${i},"Compose Product ${i}",Smoke,${invalidEvery && i % invalidEvery === 0 ? '-1.00' : '24.50'},10\r\n`);
  }
  const body = new FormData();
  body.append('file', new Blob([lines.join('')], { type: 'text/csv' }), `${label}.csv`);
  const upload = await json('/api/uploads', { method: 'POST', body });
  assert.equal(upload.totalRows, rows);
  const task = await json('/api/tasks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
    body: JSON.stringify({ name: `Compose-${label}`, uploadId: upload.uploadId,
      mapping: { sku: 'sku', name: 'name', category: 'category', price: 'price', stock: 'stock' },
      batchSize, maxAttempts: 3 }),
  });
  assert.match(task.id, /^[0-9a-f-]{36}$/i);
  return { id: task.id, rows, prefix, errors: invalidEvery ? Math.floor(rows / invalidEvery) : 0 };
}

const terminalStatuses = new Set(['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED']);
async function waitTask(id, predicate, timeoutMs = 90000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const task = await json(`/api/tasks/${id}`);
    if (predicate(task)) return task;
    if (terminalStatuses.has(task.status)) throw new Error(`Unexpected terminal task: ${JSON.stringify(task)}`);
    await sleep(100);
  }
  throw new Error(`Task ${id} did not reach the expected state within ${timeoutMs}ms`);
}

function assertFinished(task, expected) {
  assert.equal(task.status, expected.errors ? 'COMPLETED_WITH_ERRORS' : 'COMPLETED');
  assert.equal(task.totalRows, expected.rows);
  assert.equal(task.processedRows, expected.rows);
  assert.equal(task.successRows, expected.rows - expected.errors);
  assert.equal(task.errorRows, expected.errors);
  assert.equal(task.processedRows, task.successRows + task.errorRows);
  assert.equal(task.checkpoint, task.processedRows);
}

async function sql(label, query) {
  const result = await compose(label, ['exec', '-T', 'postgres', 'psql', '-U', 'batchflow', '-d', 'batchflow',
    '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-c', query]);
  return result.output.trim();
}

async function verify() {
  await check('docker_preflight', async () => {
    const docker = await command('docker-version', ['version', '--format', '{{json .Server.Version}}']);
    const composeVersion = await command('compose-version', ['compose', 'version', '--short']);
    return { serverVersion: docker.output.trim(), composeVersion: composeVersion.output.trim() };
  });

  report.ports = {
    api: await reservePort(), postgres: await reservePort(),
    rabbitmq: await reservePort(), rabbitmqManagement: await reservePort(),
  };
  const image = `${project}:local`;
  report.image = image;
  env = { ...env,
    BATCHFLOW_API_PORT: String(report.ports.api), BATCHFLOW_POSTGRES_PORT: String(report.ports.postgres),
    BATCHFLOW_RABBIT_PORT: String(report.ports.rabbitmq),
    BATCHFLOW_RABBIT_MANAGEMENT_PORT: String(report.ports.rabbitmqManagement),
    BATCHFLOW_APP_IMAGE: image,
    BATCHFLOW_DB_PASSWORD: randomUUID(), BATCHFLOW_RABBIT_PASSWORD: randomUUID(),
    BATCHFLOW_WORKER_BATCH_DELAY_MS: '750', BATCHFLOW_WORKER_LEASE_SECONDS: '8',
    BATCHFLOW_WORKER_POLL_MS: '250', BATCHFLOW_WORKER_RESTART_POLICY: 'no',
  };
  baseUrl = `http://127.0.0.1:${report.ports.api}`;
  await check('compose_configuration', async () => {
    await compose('compose-config', ['config', '--quiet']);
    const existing = await compose('existing-project', ['ps', '--all', '--quiet']);
    assert.equal(existing.output.trim(), '', 'Refusing to reuse an existing Compose project.');
    return { project, ports: report.ports };
  });
  await check('compose_image_build', async () => {
    // Set before building so an image produced by a failed late build is removed.
    imageBuilt = true;
    await compose('compose-build', ['build', '--pull', 'api'], { timeoutMs: 900000 });
    return { image };
  });
  await releasePorts();
  await check('compose_health_and_frontend', async () => {
    managedProject = true;
    await compose('compose-up', ['up', '--detach', '--no-build', '--wait', '--wait-timeout', '180'], { timeoutMs: 240000 });
    const api = await json('/api/health');
    assert.deepEqual(api, { status: 'UP', role: 'api', queueMode: 'rabbitmq' });
    const workerResponse = await compose('worker-health', ['exec', '-T', 'worker', 'curl', '--fail', '--silent',
      'http://127.0.0.1:18091/api/health']);
    const worker = JSON.parse(workerResponse.output);
    assert.deepEqual(worker, { status: 'UP', role: 'worker', queueMode: 'rabbitmq' });
    assert.match(await (await request('/')).text(), /<div id="app"><\/div>/);
    return { api, worker };
  });

  const expectations = [];
  await check('http_mixed_csv_import', async () => {
    const expected = await submit('mixed-120', 120, 25, 40);
    expectations.push(expected);
    const final = await waitTask(expected.id, task => terminalStatuses.has(task.status));
    assertFinished(final, expected);
    const errors = await json(`/api/tasks/${expected.id}/errors?size=100`);
    assert.equal(errors.total, expected.errors);
    const csv = new Uint8Array(await (await request(`/api/tasks/${expected.id}/errors.csv`)).arrayBuffer());
    assert.deepEqual(Array.from(csv.slice(0, 3)), [0xef, 0xbb, 0xbf]);
    await writeFile(join(reportDir, 'mixed-task.json'), `${JSON.stringify(final, null, 2)}\n`);
    return final;
  });
  await check('rabbitmq_busy_worker_four_tasks', async () => {
    const submitted = await Promise.all(Array.from({ length: 4 }, (_, i) => submit(`burst-${i + 1}`, 200, 0, 50)));
    expectations.push(...submitted);
    const finals = await Promise.all(submitted.map(async expected => {
      const task = await waitTask(expected.id, value => terminalStatuses.has(value.status));
      assertFinished(task, expected);
      return task;
    }));
    return finals;
  });
  await check('worker_sigkill_checkpoint_recovery', async () => {
    const expected = await submit('recovery-1200', 1200, 25, 100);
    expectations.push(expected);
    const observed = await waitTask(expected.id, task => task.status === 'RUNNING' && task.checkpoint >= 100 && task.checkpoint < expected.rows);
    await writeFile(join(reportDir, 'recovery-before-kill.json'), `${JSON.stringify(observed, null, 2)}\n`);
    await compose('worker-sigkill', ['kill', '--signal', 'SIGKILL', 'worker']);
    const id = (await compose('worker-container-id', ['ps', '--all', '--quiet', 'worker'])).output.trim();
    assert.match(id, /^[0-9a-f]{12,64}$/i);
    const running = await command('worker-stopped-state', ['inspect', '--format', '{{json .State.Running}}', id]);
    assert.equal(JSON.parse(running.output), false, 'The crashed Worker must stay stopped until explicitly restarted.');
    const persisted = await json(`/api/tasks/${expected.id}`);
    assert.equal(persisted.status, 'RUNNING');
    assert.ok(persisted.checkpoint >= observed.checkpoint && persisted.checkpoint < expected.rows);
    assert.equal(persisted.processedRows, persisted.checkpoint);
    assert.equal(persisted.successRows + persisted.errorRows, persisted.checkpoint);
    await writeFile(join(reportDir, 'recovery-after-kill.json'), `${JSON.stringify(persisted, null, 2)}\n`);
    await compose('worker-restart', ['start', 'worker']);
    const final = await waitTask(expected.id, task => terminalStatuses.has(task.status));
    assertFinished(final, expected);
    assert.ok(final.attempt >= 2, 'Expected a new lease attempt after the forced crash.');
    assert.ok(final.events.some(event => event.type === 'RECOVERED'), 'Missing persisted recovery event.');
    const products = await json(`/api/products?q=${expected.prefix}&size=1`);
    assert.equal(products.total, expected.rows - expected.errors);
    await writeFile(join(reportDir, 'recovery-final.json'), `${JSON.stringify(final, null, 2)}\n`);
    return { observed, persisted, final, productCount: products.total };
  });
  await check('database_counts_and_sku_uniqueness', async () => {
    const data = JSON.parse(await sql('database-invariants', `SELECT json_build_object(
      'invalidCounts', (SELECT count(*) FROM tasks WHERE processed_rows <> success_rows + error_rows OR checkpoint <> processed_rows OR processed_rows > total_rows),
      'duplicateSkus', (SELECT count(*) FROM (SELECT sku FROM products GROUP BY sku HAVING count(*) > 1) d),
      'tasks', (SELECT json_agg(json_build_object('id',t.id,'products',(SELECT count(*) FROM products p WHERE p.source_task_id=t.id),'errors',(SELECT count(*) FROM row_errors e WHERE e.task_id=t.id))) FROM tasks t));`));
    assert.equal(data.invalidCounts, 0);
    assert.equal(data.duplicateSkus, 0);
    assert.equal(data.tasks.length, expectations.length);
    for (const expected of expectations) {
      const task = data.tasks.find(value => value.id === expected.id);
      assert.ok(task, `Missing database task ${expected.id}`);
      assert.equal(task.products, expected.rows - expected.errors);
      assert.equal(task.errors, expected.errors);
    }
    return data;
  });
  await check('outbox_published_and_broker_queue_drained', async () => {
    const deadline = Date.now() + 20000;
    let last;
    while (Date.now() < deadline) {
      const pending = Number(await sql('outbox-pending', 'SELECT count(*) FROM task_outbox WHERE published_at IS NULL;'));
      const queues = await compose('rabbitmq-queue-state', ['exec', '-T', 'rabbitmq', 'rabbitmqctl', 'list_queues',
        'name', 'messages', 'messages_ready', 'messages_unacknowledged', '--no-table-headers', '--quiet']);
      const queue = queues.output.split(/\r?\n/).map(line => line.trim().split(/\s+/)).find(parts => parts[0] === 'batchflow.imports');
      assert.ok(queue && queue.length === 4, 'Expected the declared RabbitMQ task queue.');
      const counts = queue.slice(1).map(Number);
      assert.ok(Number.isInteger(pending) && pending >= 0 && counts.every(value => Number.isInteger(value) && value >= 0));
      last = { pendingOutbox: pending, queue: queue[0], messages: counts[0], ready: counts[1], unacknowledged: counts[2] };
      if (pending === 0 && counts.every(value => value === 0)) return last;
      await sleep(500);
    }
    throw new Error(`Outbox or RabbitMQ queue did not drain: ${JSON.stringify(last)}`);
  });
}

await saveReport();
try {
  await verify();
} catch (error) {
  failure = error;
  report.error = { message: error.message, stack: error.stack };
} finally {
  const cleanupErrors = [];
  const cleanupAttempt = async operation => {
    try { return await operation(); }
    catch (error) { cleanupErrors.push(error.message); return { ok: false }; }
  };
  await cleanupAttempt(releasePorts);
  // Try every cleanup action even if collecting an earlier log fails.
  if (managedProject) {
    const logs = await cleanupAttempt(() => compose('compose-container-logs', ['logs', '--no-color', '--timestamps'], { allowFailure: true }));
    const state = await cleanupAttempt(() => compose('compose-final-state', ['ps', '--all', '--format', 'json'], { allowFailure: true }));
    const cleanup = await cleanupAttempt(() => compose('compose-down', ['down', '--volumes', '--remove-orphans'], { allowFailure: true }));
    report.cleanup = { logsSaved: logs.ok, stateSaved: state.ok, projectRemoved: cleanup.ok };
    if (!logs.ok || !state.ok || !cleanup.ok) cleanupErrors.push('Could not preserve all container evidence or remove the isolated Compose project.');
  }
  if (imageBuilt) {
    const cleanup = await cleanupAttempt(() => command('remove-smoke-image', ['image', 'rm', report.image], { allowFailure: true }));
    report.imageRemoved = cleanup.ok;
    if (!cleanup.ok) cleanupErrors.push('Could not remove the isolated smoke image (it may not have been built).');
  }
  if (cleanupErrors.length) {
    report.cleanupErrors = cleanupErrors;
    failure ??= new Error(cleanupErrors.join(' '));
    report.failureStage ??= 'cleanup';
  }
  report.status = !failure ? 'PASSED' : report.failureStage === 'docker_preflight' ? 'BLOCKED' : 'FAILED';
  report.finishedAt = new Date().toISOString();
  await saveReport();
}
console.log(JSON.stringify({ status: report.status, reportDir, project, failureStage: report.failureStage ?? null,
  error: report.error?.message ?? report.cleanupErrors?.join(' ') ?? null }, null, 2));
if (failure) process.exitCode = 1;
