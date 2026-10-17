package link.socket.phosphor.trace

/**
 * Marks a test that compares `Float` values exactly, or quantizes them on the encode side, and so
 * needs real binary32 arithmetic. Kotlin/JS does not have it: a `Float` there is a JS double, rounded
 * to binary32 only when it is stored in a `FloatArray`, and a `Float` literal compiles to its shortest
 * decimal rather than its exact value. Ignored on `js` only; `wasmJs` has binary32 and runs it. The
 * web's decode itself is held on JS by `TraceV3FixtureTest`.
 */
@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
internal expect annotation class NeedsBinary32()
