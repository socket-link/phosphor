// build.gradle.kts asks the distribution's webpack for an ES module (`libraryTarget: "module"`), which
// webpack 5 emits only behind the output-module experiment. Guarded, so the test bundles keep their
// own output.
if (config.output && config.output.libraryTarget === 'module') {
    config.experiments = Object.assign({}, config.experiments, { outputModule: true });
}
