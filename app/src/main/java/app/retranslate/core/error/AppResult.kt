package app.retranslate.core.error

import kotlin.coroutines.cancellation.CancellationException

/**
 * The single result type that crosses every layer boundary (PLAN.md §3.3).
 * Nothing in providers / data / domain throws across a boundary: failures are
 * returned as [Err] carrying a typed [AppError].
 */
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>

    val isOk: Boolean get() = this is Ok

    fun getOrNull(): T? = (this as? Ok)?.value

    fun errorOrNull(): AppError? = (this as? Err)?.error

    fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    fun <R> flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
        is Ok -> transform(value)
        is Err -> this
    }

    fun onOk(action: (T) -> Unit): AppResult<T> {
        if (this is Ok) action(value)
        return this
    }

    fun onErr(action: (AppError) -> Unit): AppResult<T> {
        if (this is Err) action(error)
        return this
    }

    fun <R> fold(onOk: (T) -> R, onErr: (AppError) -> R): R = when (this) {
        is Ok -> onOk(value)
        is Err -> onErr(error)
    }
}

/**
 * Runs [block] and converts any thrown exception into [AppResult.Err] via
 * [mapException]. The one exception that is never swallowed is
 * [CancellationException] — coroutine cancellation is control flow, not a
 * failure (PLAN.md §10, coroutine discipline).
 */
inline fun <T> runCatchingApp(
    mapException: (Throwable) -> AppError = { AppError.Unknown(it) },
    block: () -> T
): AppResult<T> = try {
    AppResult.Ok(block())
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    AppResult.Err(mapException(t))
}

/** Suspending variant of [runCatchingApp]. */
suspend inline fun <T> runCatchingAppSuspend(
    mapException: (Throwable) -> AppError = { AppError.Unknown(it) },
    block: suspend () -> T
): AppResult<T> = try {
    AppResult.Ok(block())
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    AppResult.Err(mapException(t))
}
