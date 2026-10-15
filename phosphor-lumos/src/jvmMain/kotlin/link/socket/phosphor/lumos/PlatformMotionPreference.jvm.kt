package link.socket.phosphor.lumos

// No desktop toolkit hands the OS setting to the JVM, so a desktop orb moves unless its config says otherwise.
internal actual fun platformReducesMotion(): Boolean = false
