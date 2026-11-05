package com.hiusers.rafter.render

import com.hiusers.rafter.asset.CanvasItems
import com.hiusers.rafter.asset.ModalEncoding
import com.hiusers.rafter.model.*
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import com.hiusers.rafter.text.ModalText
import taboolib.module.chat.Components

/** 按编译槽位绘制面板，不移动原生控件及命中区。 */
object NativeModalRenderer {
    fun render(session: UiSession, view: DialogView, text: CompositeText): DialogView {
        val page = session.current().spec
        val modal = page.modal ?: return view
        val snapshot =
            requireNotNull(session.modalBackdrop) { "Modal has no captured Canvas backdrop" }
        val variant =
            snapshot.flowKey?.let {
                text.assets.canvasModalFlows[
                        CanvasItems.modalKey(session.menu.spec.id, page.id, it)]
            }
        val plan = variant?.layout?.plan ?: requireNotNull(session.current().modalLayout)
        val key = variant?.layout?.key ?: ModalEncoding.key(session.menu.spec.id, page.id)
        val backdropIndex = text.assets.modalBackdrops.indexOf(snapshot.layout)
        require(backdropIndex >= 0) { "The captured Canvas layout was not compiled into this pack" }
        require(view.inputs.map { it.node.id } == plan.inputs.map { it.id }) {
            "Native input flow changed after compilation"
        }
        require(view.bodies.size == plan.descriptions.size + view.canvasBodyCount) {
            "Native body flow changed after compilation"
        }
        val actual = (view.buttons + view.close).associateBy { it.node.id }
        fun coveredSkin(button: RenderButton): RenderButton {
            if ((button.node.content as NodeContent.Button).skin == null) return button
            val halfLabel = text.width(button.label).toInt() / 2
            val width = button.node.width ?: NativeGeometry.BUTTON_WIDTH
            val label =
                Components.empty()
                    .append(text.spaces(halfLabel))
                    .append(ModalText.inertCell(text, width, modal.background))
                    .append(text.spaces(-halfLabel))
                    .append(button.label)
            return button.copy(label = label)
        }
        val buttons =
            plan.buttons.mapIndexed { index, cell ->
                cell.target?.let { coveredSkin(actual.getValue(it)) }
                    ?: run {
                        val node =
                            NodeSpec(
                                "__modal-cell-$index",
                                NodeContent.Button(),
                                width = cell.nativeWidth,
                            )
                        val paint =
                            if (cell.role == ModalCellRole.CLIP_SPACER) Components.empty()
                            else ModalText.inertCell(
                                text,
                                cell.bounds.width,
                                modal.background,
                                cell.preserveInputCounter,
                            )
                        // 操作行决定列宽，补偿较窄填充单元的原生居中。
                        val shift = cell.labelShift
                        val label =
                            Components.empty()
                                .append(text.spaces(shift))
                                .append(paint)
                                .append(text.spaces(-shift))
                        RenderButton(node, label, null, null)
                    }
            }
        val title = ModalText.backdrop(text, snapshot, backdropIndex, modal.dim)
        title.append(ModalText.panel(text, text.assets.modalFrames.getValue(key)))
        if (variant != null) {
            title.append(
                ModalText.canvas(
                    text,
                    requireNotNull(view.canvasTitle),
                    requireNotNull(view.painting),
                    requireNotNull(view.canvasLayout),
                )
            )
        }
        val bodies =
            view.bodies.mapIndexed { index, body ->
                if (index < view.canvasBodyCount || body !is Message) body
                else {
                    val width = plan.descriptions[index - view.canvasBodyCount].bounds.width
                    val full = Components.empty().apply { body.parts.forEach { append(it.text) } }
                    val line = text.fitLine(full, width)
                    if (line.toPlainText() != full.toPlainText()) line.hoverText(full)
                    val advance = text.width(line)
                    body.copy(
                        parts =
                            Values.list(
                                listOf(Fragment(line), Fragment(text.spaces(width - advance)))
                            ),
                        width = width + NativeGeometry.TEXT_PADDING * 2,
                    )
                }
            }
        return view.copy(
            title = title,
            bodies = Values.list(bodies),
            buttons = Values.list(buttons),
            columns = plan.columns,
            fallbackTitle = null,
            modal = true,
        )
    }
}
