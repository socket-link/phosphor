package link.socket.phosphor.color

/**
 * Generic adapter contract from neutral Phosphor colors to platform color types.
 */
interface PlatformColorAdapter<T> {
    fun adapt(color: NeutralColor): T
}
