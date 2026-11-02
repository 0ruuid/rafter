package com.hiusers.rafter.asset

import com.hiusers.rafter.model.MenuSnapshot
import com.hiusers.rafter.model.NativeGeometry
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.model.NodeSpec
import java.util.Collections

/** 构包前收集所有变体，不在玩家渲染时发现资源。 */
object ButtonSkins {
    fun requests(snapshot: MenuSnapshot): Map<ImageRequest, Int> {
        val result = linkedMapOf<ImageRequest, Int>()
        fun visit(node: NodeSpec) {
            (node.content as? NodeContent.Button)?.skin?.let { skin ->
                result[skin.request(node.width ?: NativeGeometry.BUTTON_WIDTH)] = skin.inset
            }
            node.variants.forEach { visit(it.node) }
        }
        snapshot.menus.values.forEach { menu ->
            menu.spec.pages.values.forEach { page -> page.components.forEach(::visit) }
        }
        return Collections.unmodifiableMap(result)
    }
}
