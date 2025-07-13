package io.github.qavlad.adbrandomizer.core

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: Exception, val message: String? = null) : Result<Nothing>()

    inline fun onError(action: (exception: Exception, message: String?) -> Unit): Result<T> {
        if (this is Error) action(exception, message)
        return this
    }
    
    fun getOrNull(): T? = if (this is Success) data else null
    
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw exception
    }
    
    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }
    
    inline fun <R> flatMap(transform: (T) -> Result<R>): Result<R> = when (this) {
        is Success -> transform(data)
        is Error -> this
    }
    
    fun isSuccess(): Boolean = this is Success
}

// Функции для создания Result
inline fun <T> runCatchingWithMessage(message: String, block: () -> T): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: Exception) {
        Result.Error(e, message)
    }
}

// Специализированные функции для ADB операций
inline fun <T> runAdbOperation(operation: String, block: () -> T): Result<T> {
    return runCatchingWithMessage("ADB operation failed: $operation", block)
}

inline fun <T> runDeviceOperation(deviceName: String, operation: String, block: () -> T): Result<T> {
    return runCatchingWithMessage("Device operation failed: $operation on $deviceName", block)
} 