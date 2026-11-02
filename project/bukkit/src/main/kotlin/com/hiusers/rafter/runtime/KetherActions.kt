package com.hiusers.rafter.runtime

import com.hiusers.rafter.api.UiContext
import taboolib.module.kether.*

/** 只读会话状态，修改仍经过结构化动作校验。 */
object KetherActions {
    @KetherParser(["ui-state"], namespace = "rafter", shared = false)
    fun state() = combinationParser {
        it.group(text()).apply(it) { key ->
            now {
                variables()
                    .get<UiContext>("rafter-context")
                    .orElseThrow { IllegalStateException("No Rafter session") }
                    .state[key]
            }
        }
    }
}
