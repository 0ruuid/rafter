package com.hiusers.rafter.compile

import com.hiusers.rafter.model.*

/** 仅导出离线数据，不执行脚本、提供器或玩家操作。 */
object AuthoringContract {
    const val VERSION = 17

    fun manifest(engineVersion: String, extensions: Map<String, Any?>): Map<String, Any?> =
        Values.contract(
            Contract.manifest() +
                mapOf(
                    "contract-version" to VERSION,
                    "engine-version" to engineVersion,
                    "extensions" to extensions,
                    "service-api" to
                        mapOf(
                            "item-context" to "node-row-record-or-null",
                            "native-lists" to "client-flow-template-rows",
                            "action-result" to
                                mapOf(
                                    "state-patch" to "declared-state-atomic",
                                    "redraw" to "after-action-sequence",
                                    "stale-result" to "discard-without-business-rollback",
                                ),
                            "open" to
                                mapOf(
                                    "initial-state" to "declared-state-atomic-before-first-read",
                                    "initial-page" to "entry-or-existing-page",
                                    "initial-modal" to "present-entry-canvas-then-modal",
                                    "completion" to "final-target-sent",
                                ),
                            "refresh" to "expected-session-id-required",
                            "close" to "expected-session-id-required",
                        ),
                    "native-geometry" to
                        mapOf(
                            "line-height" to NativeGeometry.LINE_HEIGHT,
                            "body-gap" to NativeGeometry.BODY_GAP,
                            "label-gap" to NativeGeometry.LABEL_GAP,
                            "checkbox-size" to NativeGeometry.CHECKBOX_SIZE,
                            "button-height" to NativeGeometry.BUTTON_HEIGHT,
                            "button-width" to NativeGeometry.BUTTON_WIDTH,
                            "input-width" to NativeGeometry.INPUT_WIDTH,
                            "grid-gap" to NativeGeometry.GRID_GAP,
                            "text-padding" to NativeGeometry.TEXT_PADDING,
                            "counter-offset" to NativeGeometry.COUNTER_OFFSET,
                            "canvas-row-height" to CanvasGeometry.ROW_HEIGHT,
                        ),
                    "authoring-capabilities" to
                        mapOf(
                            "canvas" to
                                listOf(
                                    "position",
                                    "size",
                                    "paint-order",
                                    "follow-hit-target",
                                    "native-item-flow",
                                    "native-modal-from-pure-canvas",
                                ),
                            "native-body" to listOf("width", "same-region-order"),
                            "native-input" to listOf("width", "same-region-order"),
                            "native-button" to listOf("width", "same-region-order", "skin"),
                            "native-free-position" to false,
                            "global-z-index" to false,
                            "modal-region-fields" to listOf("top", "content-top", "button-gap"),
                            "source-authority" to "authored-files",
                            "preview-fidelity" to "approximate-client-owned",
                        ),
                    "diagnostics" to
                        mapOf(
                            "severity" to listOf("ERROR", "WARNING"),
                            "line-base" to 1,
                            "column-base" to 1,
                            "column-unit" to "unicode-code-point",
                            "end-exclusive" to true,
                            "path" to "canonical-opaque-source-path",
                            "missing-field-range" to "enclosing-authored-node",
                        ),
                    "preview-policy" to
                        mapOf(
                            "kether" to "disabled",
                            "papi" to "disabled",
                            "providers" to "explicit-stubs-only",
                            "text" to "taboolib-colored-simple-or-structured",
                            "authored-string-format" to "colored",
                            "dynamic-string-format" to "literal",
                            "native-layout" to "client-owned",
                            "conditions" to "explicit-preview-state-or-stubs",
                            "variants" to "first-match-base-fallback",
                            "variant-layout" to "fixed-position-natural-text-bounds",
                            "actions" to "disabled",
                            "inline-kether" to "explicit-stubs-only",
                            "fonts" to "exported-advances-only",
                            "dialog-frame" to "client-shader-viewport-backdrop-not-simulated",
                            "native-modal" to
                                "frozen-canvas-item-stack-snapshot-with-compiled-native-slots-no-provider-reread",
                            "canvas-items" to
                                mapOf(
                                    "flow" to "title-paint-native-carrier-flow",
                                    "carrier-height" to "1..256",
                                    "carrier-gap" to NativeGeometry.BODY_GAP,
                                    "allocation" to "interactive-outside-blank-bands-first",
                                    "inset" to "compiler-adds-9px-leading-inset-on-shortfall",
                                    "background" to "paint-only-no-hover-or-click",
                                    "modal" to "frozen-independent-item-stack-no-provider-reread",
                                    "layout" to
                                        "item-layouts.json.routes-flows-modal-flows-and-minimum-gui-budgets",
                                    "panel-clipping" to "frozen-model-routes-only",
                                    "new-modal-items" to "not-supported",
                                    "arbitrary-z-mask-scroll" to "not-supported",
                                ),
                            "form-feedback" to "replace-existing-text-without-reflow",
                            "native-button-skin" to
                                "image-behind-independent-measured-label-native-hitbox",
                            "images" to "source-ratio-nearest-neighbor-automatic-transport-padding",
                            "collections" to
                                "document-or-provider-records-with-selected-record-collections",
                            "meters" to "readonly-clamped-ratio-zero-max-empty",
                            "popup-occlusion" to "explicit-underlay-region-expansion",
                            "page-skins" to "resolve-explicit-content-slots-no-inheritance",
                            "row-context" to "readonly-item-record-stable-key",
                            "source-policy" to "preserve-authored-yaml-and-text-source",
                        ),
                )
        )

    fun previewData(snapshot: MenuSnapshot): Map<String, Any?> =
        Values.contract(
            mapOf(
                "contract-version" to VERSION,
                "menus" to
                    snapshot.menus.mapValues { (_, menu) ->
                        mapOf(
                            "args" to emptyMap<String, Any?>(),
                            "state" to menu.spec.state,
                            "data" to
                                menu.spec.data
                                    .filterValues { it.binding.type == "document" }
                                    .mapValues { (_, source) ->
                                        menu.spec.documents.getValue(source.binding.key)
                                    },
                            "sources" to
                                menu.spec.data.mapValues { (_, source) ->
                                    mapOf(
                                        "type" to source.binding.type,
                                        "key" to source.binding.key,
                                        "id-field" to source.idField,
                                        "args" to source.binding.args,
                                    )
                                },
                            "unresolved-bindings" to "explicit-stubs-required",
                        )
                    },
            )
        )

    fun layouts(snapshot: MenuSnapshot): Map<String, Any?> =
        Values.contract(
            mapOf(
                "contract-version" to VERSION,
                "menus" to
                    snapshot.menus.mapValues { (_, menu) ->
                        menu.pages.mapValues { (_, page) ->
                            mapOf(
                                "mode" to page.spec.mode.name.lowercase(),
                                "skin" to page.spec.skin,
                                "lists" to
                                    page.spec.lists.map { list ->
                                        val native =
                                            page.spec.components.any {
                                                it.rowScope?.list == list.id
                                            }
                                        mapOf(
                                            "id" to list.id,
                                            "source" to list.source,
                                            "page-state" to list.pageState,
                                            "selection-state" to list.selectionState,
                                            "page-size" to list.pageSize,
                                            "layout-owner" to
                                                if (native) "native-client" else "rafter-compiler",
                                            "popup" to list.popup,
                                            "slots" to
                                                list.placements.map {
                                                    if (native) mapOf("params" to it.params)
                                                    else
                                                        mapOf(
                                                            "x" to it.x,
                                                            "y" to it.y,
                                                            "params" to it.params,
                                                        )
                                                },
                                            "filter" to
                                                list.filter?.let {
                                                    mapOf(
                                                        "field" to it.field,
                                                        "value" to it.value,
                                                        "state" to it.state,
                                                    )
                                                },
                                        )
                                    },
                                "title" to textOffsets(page.spec.title),
                                "width" to page.spec.width,
                                "height" to
                                    if (page.spec.mode == PageMode.CANVAS) page.height else null,
                                "layout-owner" to
                                    if (page.spec.mode == PageMode.CANVAS) "rafter-compiler"
                                    else "native-client",
                                "native-layout-owner" to "native-client",
                                "frame" to
                                    page.spec.frame?.let {
                                        mapOf(
                                            "asset" to it.asset,
                                            "padding" to it.padding,
                                            "inset" to it.inset,
                                            "width" to it.width,
                                            "placement" to "viewport-backdrop",
                                        )
                                    },
                                "modal" to
                                    page.spec.modal?.let { modal ->
                                        mapOf(
                                            "asset" to modal.asset,
                                            "background" to modal.background,
                                            "dim" to modal.dim,
                                            "top" to modal.requestedTop,
                                            "content-top" to modal.contentTop,
                                            "resolved-top" to modal.offsetY,
                                            "header-translation-y" to
                                                modal.offsetY - CanvasGeometry.TEXT_PADDING,
                                            "button-gap" to modal.buttonGap,
                                            "placement" to "frozen-canvas-backdrop",
                                            "feedback" to modal.feedback,
                                            "layout" to
                                                modalLayout(requireNotNull(page.modalLayout)),
                                            "clear" to modal.clear.map(::rect),
                                        )
                                    },
                                "native-body-position" to
                                    if (page.spec.mode == PageMode.CANVAS)
                                        page.spec.nativeBodyPosition.id
                                    else null,
                                "native-components" to
                                    page.spec.components.map { node ->
                                        mapOf(
                                            "id" to node.id,
                                            "type" to node.type,
                                            "text" to textOffsets(node.text),
                                            "input" to
                                                (node.content as? NodeContent.TextInput)?.let {
                                                    mapOf(
                                                        "label-visible" to it.labelVisible,
                                                        "multiline" to it.multiline,
                                                        "multiline-height" to it.multilineHeight,
                                                        "max-lines" to it.maxLines,
                                                        "input-height" to it.inputHeight,
                                                    ) +
                                                        (it.appearance?.let { appearance ->
                                                            mapOf(
                                                                "appearance" to
                                                                    mapOf(
                                                                        "background" to appearance.background,
                                                                        "text-color" to appearance.textColor,
                                                                    )
                                                            )
                                                        } ?: emptyMap())
                                                },
                                            "button" to
                                                (node.content as? NodeContent.Button)?.let { button
                                                    ->
                                                    mapOf(
                                                        "role" to button.role.id,
                                                        "skin" to
                                                            button.skin?.let { skin ->
                                                                mapOf(
                                                                    "source-asset" to skin.asset,
                                                                    "inset" to skin.inset,
                                                                    "image" to
                                                                        skin
                                                                            .request(
                                                                                node.width
                                                                                    ?: NativeGeometry
                                                                                        .BUTTON_WIDTH
                                                                            )
                                                                            .id,
                                                                )
                                                            },
                                                    )
                                                },
                                            "source" to
                                                node.origin?.let { source ->
                                                    mapOf(
                                                        "file" to source.file,
                                                        "path" to source.path,
                                                    )
                                                },
                                            "origins" to
                                                node.origins.map { source ->
                                                    mapOf(
                                                        "file" to source.file,
                                                        "path" to source.path,
                                                    )
                                                },
                                        )
                                    },
                                "scene" to page.scene.map(::placed),
                                "popups" to
                                    page.popups.mapValues { (_, nodes) -> nodes.map(::placed) },
                                "hidden-underlay" to
                                    page.spec.popups.mapValues { (_, popup) ->
                                        popup.hideUnderlay.toList()
                                    },
                            )
                        }
                    },
            )
        )

    private fun rect(value: Rect): Map<String, Int> =
        mapOf(
            "x" to value.x,
            "y" to value.y,
            "width" to value.width,
            "height" to value.height,
        )

    private fun modalLayout(value: ModalLayoutPlan): Map<String, Any?> =
        mapOf(
            "body-width" to value.bodyWidth,
            "body-height" to value.bodyHeight,
            "columns" to value.columns,
            "button-row-width" to value.buttonRowWidth,
            "counter-clearance" to value.counterClearance?.let(::rect),
            "feedback" to value.feedback?.let { rect(it.bounds) + ("id" to it.id) },
            "descriptions" to value.descriptions.map { rect(it.bounds) + ("id" to it.id) },
            "inputs" to
                value.inputs.map {
                    rect(it.bounds) +
                        mapOf(
                            "id" to it.id,
                            "control-bounds" to rect(it.controlBounds),
                            "label-bounds" to it.labelBounds?.let(::rect),
                            "counter-region" to it.counterRegion?.let(::rect),
                        )
                },
            "buttons" to
                value.buttons.map {
                    rect(it.bounds) +
                        mapOf(
                            "target" to it.target,
                            "role" to it.role.id,
                            "author-editable" to (it.role == ModalCellRole.ACTION),
                            "preserve-input-counter" to it.preserveInputCounter,
                            "native-width" to it.nativeWidth,
                        )
                },
        )

    private fun textOffsets(value: TextSpec): Map<String, Any?> =
        mapOf(
            "offset-y" to value.offsetY,
            "parts" to value.parts.map(::textOffsets),
        )

    private fun placed(value: PositionedNode): Map<String, Any?> =
        mapOf(
            "id" to value.node.id,
            "type" to value.node.type,
            "hit-target" to value.node.hitTarget,
            "row-scope" to value.node.rowScope?.let { mapOf("list" to it.list, "slot" to it.slot) },
            "overflow" to value.node.overflow,
            "text" to textOffsets(value.node.text),
            "x" to value.bounds.x,
            "y" to value.bounds.y,
            "width" to value.bounds.width,
            "height" to value.bounds.height,
            "source" to value.node.origin?.let { mapOf("file" to it.file, "path" to it.path) },
            "origins" to value.node.origins.map { mapOf("file" to it.file, "path" to it.path) },
        ) +
            ((value.node.content as? NodeContent.Image)?.request?.let { request ->
                mapOf(
                    "image" to
                        mapOf(
                            "asset" to (value.node.content as NodeContent.Image).asset,
                            "source-asset" to request.asset,
                            "requested-width" to request.width,
                            "requested-height" to request.height,
                            "fit" to request.fit,
                            "transport-top-padding" to request.topPadding,
                            "transport-y" to value.node.y / 9 * 9,
                        )
                )
            } ?: emptyMap()) +
            ((value.node.content as? NodeContent.Meter)?.let { meter ->
                fun binding(spec: BindingSpec) =
                    mapOf("type" to spec.type, "key" to spec.key, "args" to spec.args)
                mapOf(
                    "meter" to
                        mapOf(
                            "value" to binding(meter.value),
                            "max" to binding(meter.maximum),
                            "fill" to meter.fill,
                            "track" to meter.track,
                            "interactive" to false,
                        )
                )
            } ?: emptyMap())
}
