package com.hiusers.rafter.runtime

import com.hiusers.rafter.api.UiContext
import com.hiusers.rafter.model.Values
import java.util.concurrent.CompletableFuture

/** 显式数据源每帧只读一次，再投影固定槽位，不阻塞主线程。 */
internal fun prepareSessionData(
    session: UiSession,
    context: UiContext,
    evaluate: Evaluation,
    concurrency: Int,
    guard: () -> Unit,
    click: Boolean,
): CompletableFuture<Void> {
    val page = session.current().spec
    val aliases =
        session.data.required(page, session.displayedNodes(), session.activeLists()).toList()
    val values = linkedMapOf<String, Any?>()
    return readBatch(
            aliases,
            concurrency,
            guard,
            { alias ->
                evaluate.binding(session.menu.spec.data.getValue(alias).binding, context, guard)
            },
            { alias, value -> values[alias] = value },
        )
        .thenCompose {
            mainFuture<Void> {
                guard()
                session.data.install(values, click)
                var future = CompletableFuture.completedFuture<Void>(null)
                session.data.projectionOrder(session.activeLists()).forEach { list ->
                    future = future.thenCompose {
                        mainFuture<Void> {
                            guard()
                            // 前一投影可能修正选择，后续条件读取新状态。
                            val current = context.copy(state = Values.map(session.state))
                            evaluate
                                .condition(list.filter?.condition, current, guard)
                                .thenCompose { filter ->
                                    evaluate.condition(list.visible, current, guard).thenCompose {
                                        visible ->
                                        main<Void> {
                                            guard()
                                            session.data.project(
                                                listOf(list),
                                                mapOf(list.id to filter),
                                                mapOf(list.id to visible),
                                            )
                                            null
                                        }
                                    }
                                }
                        }
                    }
                }
                future
            }
        }
}
