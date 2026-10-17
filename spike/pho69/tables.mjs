// results.jsonl -> markdown tables for the decision doc.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const rows = fs.readFileSync(path.join(here, 'results.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l))
  .filter((r) => r.run && !r.run.startsWith('smoke') && !r.run.startsWith('real1'));
const browser = (r) => (r.run.endsWith('-Safari') ? 'Safari' : 'Chrome');
const f = (v, d = 2) => (v == null || Number.isNaN(v) ? '–' : Number(v).toFixed(d));
const latest = new Map();
for (const r of rows) latest.set(r.run, r); // last report per run id wins

const orbs = [...latest.values()].filter((r) => r.page !== 'trace-js' && r.page !== 'trace-wasm');
console.log('### Orb surfaces\n');
console.log('| Page | Browser | Res | Cells | Size px | Style | TTFF ms | render p50 | render p95 | project p95 | frame gap p50 | frame gap p95 | DOM writes/frame |');
console.log('|---|---|---|---|---|---|---|---|---|---|---|---|---|');
const order = (r) => [r.page, r.resolution, r.size, r.style, browser(r)].join('|');
for (const r of orbs.sort((a, b) => order(a).localeCompare(order(b)))) {
  const writes = r.attributeWrites != null ? f(r.attributeWrites / r.frames, 0) : '–';
  console.log(`| ${r.page} | ${browser(r)} | ${r.resolution} | ${r.cells ?? (r.resolution === 10 ? '4801*' : r.resolution === 2 ? '81*' : '–')} | ${r.size} | ${r.style}${r.tint === 'CURRENT_COLOR' ? ' (currentColor)' : ''} | ${f(r.firstFrameMs, 0)} | ${f(r.renderP50)} | ${f(r.renderP95)} | ${f(r.projectP95)} | ${f(r.frameGapP50, 1)} | ${f(r.frameGapP95, 1)} | ${writes} |`);
}
const traces = [...latest.values()].filter((r) => r.page === 'trace-js' || r.page === 'trace-wasm');
console.log('\n### Trace decode (20 s, 20 fps, resolution 10, v3, 3.83 MB .vxt → 21.2 MB CBOR)\n');
console.log('| Page | Browser | decode first ms | decode median ms (5) | gunzip ms | frameAt ms/frame | toFrames ms (400) | buffer copy ms | checksums match JVM |');
console.log('|---|---|---|---|---|---|---|---|---|');
const ref = JSON.parse(fs.readFileSync(path.join(here, '..', '..', 'phosphor-trace/build/pho-69/idle-20s.json'), 'utf8'));
for (const r of traces.sort((a, b) => order(a).localeCompare(order(b)))) {
  const ok = r.checksums && r.checksums.every((c, i) => Math.abs(c - ref.checksums[i]) < 0.05);
  console.log(`| ${r.page} | ${browser(r)} | ${f(r.decodeFirstMs, 0)} | ${f(r.decodeMedianMs, 0)} | ${f(r.gunzipMs, 0)} | ${f(r.frameAtMs)} | ${f(r.toFramesMs, 0)} | ${f(r.copyMs, 0)} | ${r.error ? 'ERROR ' + r.error : ok ? 'yes' : 'NO'} |`);
}
console.log(`| JVM reference | – | – | ${f(ref.jvmDecodeMedianMs, 0)} | – | – | ${f(ref.jvmToFramesMs, 0)} | – | – |`);
