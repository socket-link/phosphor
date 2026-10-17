// Bundle sizes, raw and gzip -9, for every file in each prototype distribution.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', '..');
const dists = {
  'compose-wasm': 'phosphor-lumos-compose/build/dist/wasmJs/productionExecutable',
  'dom-js': 'phosphor-lumos-dom/build/dist/js/productionExecutable',
  'dom-wasm': 'phosphor-lumos-dom/build/dist/wasmJs/productionExecutable',
  'trace-js': 'phosphor-trace/build/dist/js/productionExecutable',
  'trace-wasm': 'phosphor-trace/build/dist/wasmJs/productionExecutable',
};
const kb = (n) => (n / 1024).toFixed(1).padStart(9) + ' KB';
const out = {};
for (const [name, dir] of Object.entries(dists)) {
  const full = path.join(root, dir);
  if (!fs.existsSync(full)) { console.log(`${name}: (not built)`); continue; }
  let raw = 0, gz = 0;
  console.log(`\n${name}  (${dir})`);
  for (const f of fs.readdirSync(full).sort()) {
    if (f.endsWith('.map') || f === 'index.html' || fs.statSync(path.join(full, f)).isDirectory()) continue;
    const data = fs.readFileSync(path.join(full, f));
    const z = zlib.gzipSync(data, { level: 9 }).length;
    raw += data.length; gz += z;
    console.log(`  ${f.padEnd(44)} raw ${kb(data.length)}   gzip ${kb(z)}`);
  }
  console.log(`  ${'TOTAL (loaded by the page)'.padEnd(44)} raw ${kb(raw)}   gzip ${kb(gz)}`);
  out[name] = { rawBytes: raw, gzipBytes: gz };
}
fs.writeFileSync(path.join(here, 'sizes.json'), JSON.stringify(out, null, 2));
