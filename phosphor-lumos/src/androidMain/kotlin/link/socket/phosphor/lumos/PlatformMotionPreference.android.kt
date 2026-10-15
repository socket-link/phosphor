package link.socket.phosphor.lumos

/*
 * Compiled once this module has an Android target (PHO-52). Android keeps "Remove animations"
 * (Settings.Global.ANIMATOR_DURATION_SCALE == 0) behind a Context, which this function cannot
 * reach, so an Android orb moves unless its config says otherwise until a driver can hand one in.
 */
internal actual fun platformReducesMotion(): Boolean = false
