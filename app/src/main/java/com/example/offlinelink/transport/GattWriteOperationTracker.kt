package com.example.offlinelink.transport

internal class GattWriteOperationTracker {
  private var nextToken = 1L
  private var activeToken: Long? = null
  private var completedResult: Result<Unit>? = null

  fun begin(): Long {
    val token = nextToken
    nextToken = if (nextToken == Long.MAX_VALUE) 1L else nextToken + 1L
    activeToken = token
    completedResult = null
    return token
  }

  fun activeToken(): Long? = activeToken

  fun complete(
    token: Long,
    result: Result<Unit>,
  ): Boolean {
    if (activeToken != token) return false
    activeToken = null
    completedResult = result
    return true
  }

  fun timeout(
    token: Long,
    exception: Throwable,
  ): Boolean = complete(token, Result.failure(exception))

  fun cancel(token: Long): Boolean {
    if (activeToken != token) return false
    activeToken = null
    completedResult = null
    return true
  }

  fun failActive(exception: Throwable): Boolean {
    val token = activeToken ?: return false
    return complete(token, Result.failure(exception))
  }

  fun result(): Result<Unit>? = completedResult
}
