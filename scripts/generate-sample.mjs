import { createWriteStream } from 'node:fs';
import { mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const options = {};
for (let index = 0; index < args.length; index += 2) {
  const key = args[index];
  const value = args[index + 1];
  if (!['--rows', '--invalid-every', '--output'].includes(key) || !value) {
    throw new Error('Usage: node scripts/generate-sample.mjs [--rows 20000] [--invalid-every 25] [--output .runtime/samples/demo.csv]');
  }
  options[key] = value;
}
const rows = Number(options['--rows'] ?? 20000);
const invalidEvery = Number(options['--invalid-every'] ?? 0);
if (!Number.isInteger(rows) || rows < 1 || rows > 1000000) throw new Error('--rows must be an integer between 1 and 1000000.');
if (!Number.isInteger(invalidEvery) || invalidEvery < 0) throw new Error('--invalid-every must be a non-negative integer.');
const output = resolve(projectRoot, options['--output'] ?? `.runtime/samples/products-${rows}.csv`);
await mkdir(dirname(output), { recursive: true });
const prefix = `DEMO-${randomUUID().slice(0, 8)}`;
const stream = createWriteStream(output, { encoding: 'utf8', flags: 'wx' });
// Register a single error handler before asynchronous writes so failures are never lost.
const finished = once(stream, 'finish');
stream.write('\uFEFFsku,name,category,price,stock\r\n');
let invalidRows = 0;
for (let index = 1; index <= rows; index++) {
  const invalid = invalidEvery > 0 && index % invalidEvery === 0;
  if (invalid) invalidRows++;
  const line = `${prefix}-${index},"Product ${index}",Office,${invalid ? '-1' : '12.50'},10\r\n`;
  if (!stream.write(line)) await once(stream, 'drain');
}
stream.end();
await finished;
process.stdout.write(`${JSON.stringify({ output, rows, expectedValidRows: rows - invalidRows, expectedErrorRows: invalidRows, skuPrefix: prefix }, null, 2)}\n`);
