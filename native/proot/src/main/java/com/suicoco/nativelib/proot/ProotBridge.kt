package com.suicoco.nativelib.proot

object ProotBridge {
    init { System.loadLibrary("suicoco_proot") }
    external fun nativeProotVersion(): String
}
