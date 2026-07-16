package com.example.messagesender

/**
 * Live, in-process status of the sender so the UI can show whether the loop is
 * actually running and how many messages have been sent. Reset on a fresh start.
 */
object SenderStatus {
    @Volatile var running: Boolean = false
    @Volatile var sentCount: Int = 0
    @Volatile var lastSentAt: Long = 0L
    @Volatile var lastError: String? = null

    fun reset() {
        sentCount = 0
        lastSentAt = 0L
        lastError = null
    }
}
