package top.wkbin.taixu.core.common.logging

import android.util.Log

/**
 * Application logger wrapper for consistent logging across the project.
 * Provides tagged logging with configurable log levels.
 */
class AppLogger(private val tag: String) {

    companion object {
        private var sLogLevel = Log.INFO
        private val loggers = mutableMapOf<String, AppLogger>()

        fun setLogLevel(level: Int) {
            sLogLevel = level
        }

        fun get(tag: String): AppLogger {
            return loggers.getOrPut(tag) { AppLogger(tag) }
        }

        fun get(clazz: Class<*>): AppLogger = get(clazz.simpleName)
    }

    fun v(message: String) { if (sLogLevel <= Log.VERBOSE) Log.v(tag, message) }
    fun v(message: String, throwable: Throwable) { if (sLogLevel <= Log.VERBOSE) Log.v(tag, message, throwable) }

    fun d(message: String) { if (sLogLevel <= Log.DEBUG) Log.d(tag, message) }
    fun d(message: String, throwable: Throwable) { if (sLogLevel <= Log.DEBUG) Log.d(tag, message, throwable) }

    fun i(message: String) { if (sLogLevel <= Log.INFO) Log.i(tag, message) }
    fun i(message: String, throwable: Throwable) { if (sLogLevel <= Log.INFO) Log.i(tag, message, throwable) }

    fun w(message: String) { if (sLogLevel <= Log.WARN) Log.w(tag, message) }
    fun w(message: String, throwable: Throwable) { if (sLogLevel <= Log.WARN) Log.w(tag, message, throwable) }

    fun e(message: String) { if (sLogLevel <= Log.ERROR) Log.e(tag, message) }
    fun e(message: String, throwable: Throwable) { if (sLogLevel <= Log.ERROR) Log.e(tag, message, throwable) }

    fun wtf(message: String) { Log.wtf(tag, message) }
    fun wtf(message: String, throwable: Throwable) { Log.wtf(tag, message, throwable) }
}