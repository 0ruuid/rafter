package com.hiusers.rafter.example

import com.hiusers.rafter.api.RafterService
import com.hiusers.rafter.api.UiOpenOptions
import org.bukkit.entity.Player

/** 覆盖默认、命名参数和完整构造，不执行玩家操作。 */
object KotlinConsumer {
    fun defaults() = UiOpenOptions()

    fun stateOnly(state: Map<String, Any?>) = UiOpenOptions(initialState = state)

    fun pageOnly(page: String?) = UiOpenOptions(initialPage = page)

    fun full(state: Map<String, Any?>, page: String?) =
        UiOpenOptions(initialState = state, initialPage = page)

    fun open(service: RafterService, player: Player, menu: String, options: UiOpenOptions) =
        service.open(player, menu, emptyMap(), options)
}
