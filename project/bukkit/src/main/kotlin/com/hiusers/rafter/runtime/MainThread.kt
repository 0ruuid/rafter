package com.hiusers.rafter.runtime

import java.util.concurrent.CompletableFuture
import org.bukkit.Bukkit
import taboolib.common.platform.function.submit

/** 主线程直接执行，否则派发且不阻塞；完成 Future 前先发布或分离状态，防止同步重入。 */
internal fun <T> main(block: () -> T?): CompletableFuture<T> {
    val result = CompletableFuture<T>()
    fun run() {
        try {
            result.complete(block())
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
        }
    }
    try {
        if (Bukkit.isPrimaryThread()) run() else submit { run() }
    } catch (failure: Throwable) {
        result.completeExceptionally(failure)
    }
    return result
}

/** 只派发调用，不保证 Future 的完成线程或后续延续线程。 */
internal fun <T> mainFuture(block: () -> CompletableFuture<T>): CompletableFuture<T> =
    main(block).thenCompose { it }
