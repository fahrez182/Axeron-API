package frb.axeron.server.util

import android.util.Log
import java.io.IOException
import java.util.Locale
import java.util.logging.FileHandler
import java.util.logging.SimpleFormatter

open class Logger {

    private val tag: String
    private val logger: java.util.logging.Logger?

    constructor(tag: String) {
        this.tag = tag
        this.logger = null
    }

    constructor(tag: String, file: String) {
        this.tag = tag
        val logger = java.util.logging.Logger.getLogger(tag)
        this.logger = logger
        try {
            val fh = FileHandler(file)
            fh.formatter = SimpleFormatter()
            logger.addHandler(fh)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    open fun isLoggable(tag: String, level: Int): Boolean = true

    private fun format(fmt: String, args: Array<out Any?>): String =
        String.format(Locale.ENGLISH, fmt, *args)

    private fun withTrace(msg: String, tr: Throwable?): String = "$msg\n${Log.getStackTraceString(tr)}"

    fun v(msg: String) {
        if (isLoggable(tag, Log.VERBOSE)) println(Log.VERBOSE, msg)
    }

    fun v(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.VERBOSE)) println(Log.VERBOSE, format(fmt, args))
    }

    fun v(msg: String, tr: Throwable?) {
        if (isLoggable(tag, Log.VERBOSE)) println(Log.VERBOSE, withTrace(msg, tr))
    }

    fun d(msg: String) {
        if (isLoggable(tag, Log.DEBUG)) println(Log.DEBUG, msg)
    }

    fun d(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.DEBUG)) println(Log.DEBUG, format(fmt, args))
    }

    fun d(msg: String, tr: Throwable?) {
        if (isLoggable(tag, Log.DEBUG)) println(Log.DEBUG, withTrace(msg, tr))
    }

    fun i(msg: String) {
        if (isLoggable(tag, Log.INFO)) println(Log.INFO, msg)
    }

    fun i(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.INFO)) println(Log.INFO, format(fmt, args))
    }

    fun i(msg: String, tr: Throwable?) {
        if (isLoggable(tag, Log.INFO)) println(Log.INFO, withTrace(msg, tr))
    }

    fun w(msg: String) {
        if (isLoggable(tag, Log.WARN)) println(Log.WARN, msg)
    }

    fun w(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.WARN)) println(Log.WARN, format(fmt, args))
    }

    fun w(tr: Throwable?, fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.WARN)) println(Log.WARN, withTrace(format(fmt, args), tr))
    }

    fun w(msg: String, tr: Throwable?) {
        if (isLoggable(tag, Log.WARN)) println(Log.WARN, withTrace(msg, tr))
    }

    fun e(msg: String) {
        if (isLoggable(tag, Log.ERROR)) println(Log.ERROR, msg)
    }

    fun e(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.ERROR)) println(Log.ERROR, format(fmt, args))
    }

    fun e(msg: String, tr: Throwable?) {
        if (isLoggable(tag, Log.ERROR)) println(Log.ERROR, withTrace(msg, tr))
    }

    fun e(tr: Throwable?, fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.ERROR)) println(Log.ERROR, withTrace(format(fmt, args), tr))
    }

    open fun println(priority: Int, msg: String?): Int {
        logger?.info(msg)
        return Log.println(priority, tag, msg.toString())
    }
}
