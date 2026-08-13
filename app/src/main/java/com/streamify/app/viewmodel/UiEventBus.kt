package com.streamify.app.viewmodel

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
}

object UiEventBus {
    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    suspend fun emitEvent(event: UiEvent) {
        _events.emit(event)
    }
}
