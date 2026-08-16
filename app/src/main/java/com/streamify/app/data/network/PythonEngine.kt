package com.streamify.app.data.network

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.streamify.app.data.TrackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-Performance Lazy Chaquopy Engine
 * Ensures Python runtime is NEVER loaded at app startup, avoiding the 800-1200ms cold-start penalty.
 * Initializes the Python VM lazily in a background thread only if a fallback is triggered.
 */
object PythonEngine {
    private const val TAG = "PythonEngine"
    private var isInitializing = false

    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context? = null) {
        if (initialized || isInitializing) return
        synchronized(this) {
            if (initialized || isInitializing) return
            isInitializing = true
            try {
                val ctx = context ?: TrackRepository.appContext
                if (ctx != null && !Python.isStarted()) {
                    val startTime = System.currentTimeMillis()
                    Python.start(AndroidPlatform(ctx))
                    Log.i(TAG, "Chaquopy Python VM lazy initialized in ${System.currentTimeMillis() - startTime}ms")
                }
                initialized = Python.isStarted()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to lazy initialize Python VM", e)
            } finally {
                isInitializing = false
            }
        }
    }

    fun isAvailable(): Boolean {
        if (!initialized && !Python.isStarted()) {
            ensureInitialized()
        }
        return Python.isStarted()
    }

    suspend fun <T> executeFallback(
        moduleName: String,
        function: String,
        vararg args: Any,
        parser: (PyObject) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            if (!Python.isStarted()) {
                return@withContext Result.failure(IllegalStateException("Python runtime unavailable"))
            }

            val py = Python.getInstance()
            val module = py.getModule(moduleName)
            val pyResult = module.callAttr(function, *args)
            Result.success(parser(pyResult))
        } catch (e: Throwable) {
            Log.e(TAG, "Python fallback execution failed ($moduleName.$function): ${e.message}", e)
            Result.failure(e)
        }
    }
}
