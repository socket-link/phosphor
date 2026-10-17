// The wasmJs browser tests' per-test timeout. KGP ignores `useMocha { timeout }` on a wasm target
// (it logs that Mocha is unsupported for Wasm and skips the block), so without this they run at
// karma-mocha's 2 s default and an overrun fails as a bare `Error`. Merged into `client.mocha`, not
// over `client`, which carries KGP's own arguments. The JS target's useMocha sets the same 60 s.
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 60000 });
