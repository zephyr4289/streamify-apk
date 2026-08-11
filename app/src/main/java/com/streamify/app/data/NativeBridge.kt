package com.streamify.app.data

object NativeBridge {
    init { System.loadLibrary("streamify_core") }
    external fun stringFromJNI(): String
}
