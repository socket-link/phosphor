package link.socket.phosphor.lumos

import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

internal actual fun platformReducesMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()
