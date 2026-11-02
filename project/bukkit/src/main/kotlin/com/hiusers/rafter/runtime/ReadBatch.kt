package com.hiusers.rafter.runtime

import java.util.concurrent.CompletableFuture

/** 主线程协调读取结果；外部 I/O 可异步完成，失败仅丢弃迟到结果。 */
internal fun <K, V> readBatch(
    keys: List<K>,
    concurrency: Int,
    guard: () -> Unit,
    read: (K) -> CompletableFuture<V>,
    accept: (K, V) -> Unit,
): CompletableFuture<Void> {
    require(concurrency > 0)
    val result = CompletableFuture<Void>()
    var next = 0
    var active = 0
    var draining = false

    fun drain() {
        if (draining || result.isDone) return
        draining = true
        try {
            guard()
            while (!result.isDone && next < keys.size && active < concurrency) {
                guard()
                val key = keys[next++]
                active++
                val pending =
                    try {
                        read(key)
                    } catch (failure: Throwable) {
                        result.completeExceptionally(failure)
                        break
                    }
                pending.whenComplete { value, failure ->
                    main {
                        active--
                        if (!result.isDone) {
                            try {
                                guard()
                                if (failure != null) result.completeExceptionally(failure)
                                else accept(key, value)
                            } catch (error: Throwable) {
                                result.completeExceptionally(error)
                            }
                            drain()
                        }
                    }
                }
            }
            if (!result.isDone && next == keys.size && active == 0) result.complete(null)
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
        } finally {
            draining = false
        }
    }
    drain()
    return result
}
