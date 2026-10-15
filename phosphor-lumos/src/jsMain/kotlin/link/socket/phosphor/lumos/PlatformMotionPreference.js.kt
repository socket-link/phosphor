package link.socket.phosphor.lumos

// The global `matchMedia`, guarded, so a worker or a non-browser host reads "no preference" instead of throwing.
internal actual fun platformReducesMotion(): Boolean =
    js("typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches")
