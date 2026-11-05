package com.hiusers.rafter.runtime

import com.hiusers.rafter.compile.Contract

/** 会话渲染与动作时限，不是资源预算；完成时及现有定时任务检查，不回滚业务。 */
data class RuntimePolicy(
    val renderTimeoutMillis: Long = 10_000,
    val actionTimeoutMillis: Long = 30_000,
    val readConcurrency: Int = 4,
    val experimentalInputAppearance: Boolean = false,
) {
    init {
        require(renderTimeoutMillis in 1_000..120_000)
        require(actionTimeoutMillis in 1_000..300_000)
        require(readConcurrency in 1..16)
    }

    companion object {
        fun read(raw: Map<String, Any?>): RuntimePolicy {
            Contract.check(raw, Contract.runtime, "config.yml", "$.runtime")
            val values = Contract.withDefaults(raw, Contract.runtime)
            return RuntimePolicy(
                renderTimeoutMillis =
                    (values.getValue("render-timeout-seconds") as Number).toLong() * 1_000,
                actionTimeoutMillis =
                    (values.getValue("action-timeout-seconds") as Number).toLong() * 1_000,
                readConcurrency = (values.getValue("read-concurrency") as Number).toInt(),
                experimentalInputAppearance = values["experimental-input-appearance"] == true,
            )
        }
    }
}
