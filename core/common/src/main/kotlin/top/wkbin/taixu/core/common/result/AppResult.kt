package top.wkbin.taixu.core.common.result

/**
 * Standardized result wrapper for operations that can succeed or fail.
 * Used throughout the codebase instead of exceptions for expected failure cases.
 */
sealed interface AppResult<out T> {
    data class Success<out T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>

    companion object {
        fun <T> success(data: T): AppResult<T> = Success(data)
        fun <T> failure(error: AppError): AppResult<T> = Failure(error)
        fun <T> failure(code: ErrorCode, message: String, cause: Throwable? = null): AppResult<T> =
            Failure(AppError(code, message, cause))
    }

    fun isSuccess(): Boolean = this is Success<*>
    fun isFailure(): Boolean = this is Failure

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw error.toException()
    }

    fun errorOrNull(): AppError? = when (this) {
        is Success -> null
        is Failure -> error
    }

    fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    fun <R> flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
        is Success -> transform(data)
        is Failure -> this
    }

    fun onSuccess(action: (T) -> Unit): AppResult<T> = when (this) {
        is Success -> { action(data); this }
        is Failure -> this
    }

    fun onFailure(action: (AppError) -> Unit): AppResult<T> = when (this) {
        is Success -> this
        is Failure -> { action(error); this }
    }
}

data class AppError(
    val code: ErrorCode,
    val message: String,
    val cause: Throwable? = null
) {
    fun toException(): RuntimeException = RuntimeException(message, cause)
}

enum class ErrorCode {
    UNKNOWN,
    NETWORK,
    IO,
    PARSE,
    VALIDATION,
    PERMISSION_DENIED,
    NOT_FOUND,
    ALREADY_EXISTS,
    INSTALLATION_FAILED,
    UNSUPPORTED_ARCHITECTURE,
    INSUFFICIENT_STORAGE,
    RUNTIME_NOT_INITIALIZED,
    CONFIGURATION_ERROR,
    TIMEOUT,
    CANCELLED,
    SECURITY_VIOLATION
}