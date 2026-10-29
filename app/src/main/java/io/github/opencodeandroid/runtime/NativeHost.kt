package io.github.opencodeandroid.runtime

object NativeHost {
    init { System.loadLibrary("runtime-host") }
    external fun pageSize(): Long
    external fun spawnPty(args: Array<String>, environment: Array<String>, rows: Int, columns: Int): IntArray
    external fun waitFor(pid: Int): Int
    external fun signalGroup(pid: Int, signal: Int)
    external fun resize(fd: Int, rows: Int, columns: Int)
    external fun spawnProcess(args: Array<String>, environment: Array<String>): IntArray
    external fun signalProcess(pid: Int, signal: Int)
    /** Raw UTF-8 avoids JNI modified-UTF-8 changing supplementary filename characters. */
    external fun setSymlinkModifiedTime(pathUtf8: ByteArray, milliseconds: Long): Int
}
