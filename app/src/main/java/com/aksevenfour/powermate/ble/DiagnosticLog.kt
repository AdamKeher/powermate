package com.aksevenfour.powermate.ble

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * In-memory log of Bluetooth traffic, shown on the diagnostics screen so users can share what their
 * Powerpal actually sends. That is how the remaining unknowns in the protocol get filled in.
 */
class DiagnosticLog(private val capacity: Int = 600) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(message: String) {
        Log.i(TAG, message)
        val line = "${LocalDateTime.now().format(STAMP)}  $message"
        _lines.update { (it + line).takeLast(capacity) }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    private companion object {
        const val TAG = "PowerMate"
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    }
}
