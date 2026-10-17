// PHO-69 measurement server: serves the four prototype distributions and the trace fixture,
// collects the JSON each page POSTs to /report into results.jsonl. Node only, no packages.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', '..');
const port = +(process.env.PORT || 8769);
const results = path.join(here, 'results.jsonl');

const mounts = {
  '/compose/': 'phosphor-lumos-compose/build/dist/wasmJs/productionExecutable',
  '/dom-js/': 'phosphor-lumos-dom/build/dist/js/productionExecutable',
  '/dom-wasm/': 'phosphor-lumos-dom/build/dist/wasmJs/productionExecutable',
  '/trace-js/': 'phosphor-trace/build/dist/js/productionExecutable',
  '/trace-wasm/': 'phosphor-trace/build/dist/wasmJs/productionExecutable',
  '/fixtures/': 'phosphor-trace/build/pho-69',
};
const types = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.wasm': 'application/wasm', '.json': 'application/json', '.vxt': 'application/octet-stream',
  '.map': 'application/json', '.css': 'text/css',
};

http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${port}`);
  if (req.method === 'POST' && url.pathname === '/report') {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      const record = { ...JSON.parse(body), receivedAt: new Date().toISOString() };
      fs.appendFileSync(results, JSON.stringify(record) + '\n');
      console.log('report', record.page, record.run, 'render p95', record.renderP95 ?? record.decodeMedianMs);
      res.writeHead(204, { 'Access-Control-Allow-Origin': '*' }).end();
    });
    return;
  }
  const prefix = Object.keys(mounts).find((p) => url.pathname.startsWith(p));
  if (!prefix) return res.writeHead(404).end('no mount');
  let rel = url.pathname.slice(prefix.length) || 'index.html';
  const file = path.join(root, mounts[prefix], rel);
  if (!file.startsWith(path.join(root, mounts[prefix]))) return res.writeHead(403).end();
  fs.readFile(file, (err, data) => {
    if (err) return res.writeHead(404).end('not found: ' + rel);
    res.writeHead(200, {
      'Content-Type': types[path.extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-store',
    });
    res.end(data);
  });
}).listen(port, () => console.log(`pho-69 server on http://localhost:${port}`));
