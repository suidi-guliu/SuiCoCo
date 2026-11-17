package com.suicoco.nativelib.pty

object PtyBridge {
    init { System.loadLibrary("suicoco_pty") }
    external fun nativeAvailable(): Boolean
}
