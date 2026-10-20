package com.bittool.signer.core.common.log

/**
 * The only logger allowed in the codebase. A no-op until `:app` sets [enabled] (`BuildConfig.DEBUG`) in
 * `Application.onCreate`, so release builds log nothing. Messages are lambdas: when disabled they are never built.
 *
 * Iron rule: never log recovery phrases, passphrases, passwords, seeds, keys, scanned text, engine requests or
 * results, addresses or receipts. Log operation names, error codes, sizes and timings only.
 */
object Log {
    @Volatile
    var enabled: Boolean = false

    /** Where enabled messages go; `:app` installs `android.util.Log`, tests may capture. */
    @Volatile
    var sink: Sink = Sink { _, _, _, _ -> }

    fun interface Sink {
        fun write(priority: Priority, tag: String, message: String, throwable: Throwable?)
    }

    enum class Priority { Debug, Info, Warn, Error }

    inline fun d(tag: String, message: () -> String) {
        if (enabled) sink.write(Priority.Debug, tag, message(), null)
    }

    inline fun i(tag: String, message: () -> String) {
        if (enabled) sink.write(Priority.Info, tag, message(), null)
    }

    inline fun w(tag: String, throwable: Throwable? = null, message: () -> String) {
        if (enabled) sink.write(Priority.Warn, tag, message(), throwable)
    }

    inline fun e(tag: String, throwable: Throwable? = null, message: () -> String) {
        if (enabled) sink.write(Priority.Error, tag, message(), throwable)
    }

    /** The `android.util.Log` sink `:app` installs in debug builds. */
    val AndroidSink: Sink = Sink { priority, tag, message, throwable ->
        when (priority) {
            Priority.Debug -> android.util.Log.d(tag, message, throwable)
            Priority.Info -> android.util.Log.i(tag, message, throwable)
            Priority.Warn -> android.util.Log.w(tag, message, throwable)
            Priority.Error -> android.util.Log.e(tag, message, throwable)
        }
    }
}
