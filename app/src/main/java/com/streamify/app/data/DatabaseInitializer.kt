package com.streamify.app.data

import kotlinx.coroutines.CompletableDeferred

object DatabaseInitializer {
    private val initDeferred = CompletableDeferred<Unit>()

    fun startInitialization(dbPath: String) {
        try {
            NativeBridge.initDatabase(dbPath)
        } catch (e: Throwable) {
            android.util.Log.e("DatabaseInitializer", "Failed to initialize NativeBridge Database", e)
        } finally {
            if (!initDeferred.isCompleted) {
                initDeferred.complete(Unit)
            }
        }
    }

    suspend fun ensureInitialized() {
        initDeferred.await()
    }
}
