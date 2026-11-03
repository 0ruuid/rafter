package com.hiusers.rafter

import com.hiusers.rafter.api.*
import com.hiusers.rafter.asset.*
import com.hiusers.rafter.compile.*
import com.hiusers.rafter.model.*
import com.hiusers.rafter.render.*
import com.hiusers.rafter.runtime.RafterEngine
import com.hiusers.rafter.text.CompositeText
import com.hiusers.rafter.transport.DialogTransport
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Logger
import kotlin.test.*
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import taboolib.common.platform.PlatformFactory
import taboolib.common.platform.service.PlatformExecutor

/** 捕获发送边界验证状态迁移，不启动服务器。 */
class RuntimeTest {
    @TempDir lateinit var temp: Path
    private lateinit var engine: RafterEngine
    private lateinit var wire: Capture
    private lateinit var plugin: Plugin
    private lateinit var player: Player
    private lateinit var assets: AssetCatalog
    private var permitted = true
    private var online = true
    private var now = 0L
    private val posted = mutableListOf<() -> Unit>()
    private var previousExecutor: Any? = null

    private fun nextTick() {
        val tasks = posted.toList()
        posted.clear()
        tasks.forEach { it() }
    }

    companion object {
        private val players = mutableMapOf<UUID, Player>()
        private var mainThread: Thread? = null
        private val mainTasks = ConcurrentLinkedQueue<() -> Unit>()
        private val executor =
            object : PlatformExecutor {
                override fun start() = Unit

                override fun submit(
                    runnable: PlatformExecutor.PlatformRunnable
                ): PlatformExecutor.PlatformTask {
                    val task =
                        object : PlatformExecutor.PlatformTask {
                            override fun cancel() = Unit
                        }
                    mainTasks += { runnable.executor(task) }
                    return task
                }
            }

        @JvmStatic
        @BeforeAll
        fun server() {
            if (Bukkit.getServer() == null)
                Bukkit.setServer(
                    proxy(Server::class.java) { method, args ->
                        when (method) {
                            "isPrimaryThread" ->
                                mainThread == null || Thread.currentThread() === mainThread
                            "getPlayer" -> players[args?.firstOrNull()]
                            "getLogger" -> Logger.getLogger("RafterTest")
                            "getName" -> "RafterFixture"
                            "getVersion" -> "Fixture (MC: 1.20.4)"
                            "getBukkitVersion" -> "1.20.4-R0.1"
                            else -> null
                        }
                    }
                )
        }

        private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T {
            return Mockito.mock(
                type,
                Answer { invocation ->
                    call(invocation.method.name, invocation.arguments)
                        ?: Answers.RETURNS_DEFAULTS.answer(invocation)
                },
            )
        }
    }

    @BeforeEach
    fun setup() {
        taboolib.module.chat.Components.useAdventure = true
        mainThread = Thread.currentThread()
        mainTasks.clear()
        previousExecutor =
            PlatformFactory.serviceMap.put(PlatformExecutor::class.java.name, executor)
        players.clear()
        now = 0L
        permitted = true
        online = true
        posted.clear()
        val id = UUID.randomUUID()
        player =
            proxy(Player::class.java) { method, _ ->
                if (method in setOf("getUniqueId", "isOnline", "hasPermission", "getName"))
                    check(Thread.currentThread() === mainThread) {
                        "Player access off main thread: $method"
                    }
                when (method) {
                    "getUniqueId" -> id
                    "isOnline" -> online
                    "hasPermission" -> permitted
                    "getName" -> "Fixture"
                    else -> null
                }
            }
        players[id] = player
        plugin =
            proxy(Plugin::class.java) { method, _ ->
                when (method) {
                    "isEnabled" -> true
                    "getName" -> "Fixture"
                    "getLogger" -> Logger.getLogger("RafterTest")
                    else -> null
                }
            }
        val ui = temp.resolve("core/ui")
        copyLegacyUiTo(ui)
        assets = AssetCompiler.compile(ui, AutoAssets.document(ui))
        wire = Capture()
        engine = RafterEngine(plugin, temp, wire, post = { posted += it }) { now }
    }

    @AfterEach
    fun shutdown() {
        try {
            if (::engine.isInitialized) engine.close()
        } finally {
            mainTasks.clear()
            mainThread = null
            val previous = previousExecutor
            if (previous == null)
                PlatformFactory.serviceMap.remove(PlatformExecutor::class.java.name)
            else PlatformFactory.serviceMap[PlatformExecutor::class.java.name] = previous
        }
    }

    private class Capture : DialogTransport() {
        val shown = mutableListOf<DialogView>()
        var visible: DialogView? = null
        var clears = 0
        var afterShow: ((DialogView) -> Unit)? = null
        var nativeItems: Map<String, org.bukkit.inventory.ItemStack> = emptyMap()

        override fun supported(player: Player) = true

        override fun show(
            player: Player,
            view: DialogView,
            text: CompositeText,
            values: Map<BindingSpec, Any?>,
            items: Map<String, org.bukkit.inventory.ItemStack>,
        ) {
            assertSame(mainThread, Thread.currentThread())
            shown += view
            visible = view
            nativeItems = items.toMap()
            afterShow?.invoke(view)
        }

        override fun clear(player: Player) {
            assertSame(mainThread, Thread.currentThread())
            clears++
            visible = null
        }

        fun plain() =
            visible?.messages?.flatMap { it.parts }?.joinToString("") { it.text.toPlainText() }
                ?: ""
    }

    private fun candidate(vararg docs: Map<String, Any?>): Candidate {
        val result =
            MenuCompiler(assets.dimensions())
                .compile(docs.mapIndexed { i, d -> "$i.yml" to d }.toMap())
        val snapshot = assertNotNull(result.snapshot, result.diagnostics.toString())
        return Candidate(snapshot, assets, docs.first()["id"] as String, 600_000)
    }

    private fun slow(visible: Map<String, Any?>? = null): Map<String, Any?> {
        val label =
            mutableMapOf<String, Any?>(
                "id" to "label",
                "type" to "text",
                "binding" to mapOf("type" to "data", "key" to "test:slow"),
            )
        if (visible != null) label["visible"] = visible
        return minimal(components = listOf(label))
    }

    private fun other() =
        minimal(
            components = listOf(mapOf("id" to "label", "type" to "text", "text" to "replacement"))
        ) + mapOf("id" to "test:other")

    private fun canvasControls(): Map<String, Any?> {
        fun action(type: String, vararg values: Pair<String, Any?>) = mapOf("type" to type) + values
        fun hit(id: String, y: Int, actions: List<Map<String, Any?>>) =
            mapOf(
                "id" to id,
                "type" to "hit-area",
                "x" to 0,
                "y" to y,
                "width" to 90,
                "height" to 18,
                "actions" to actions,
            )
        fun label(id: String, key: String, y: Int) =
            mapOf(
                "id" to id,
                "type" to "text",
                "width" to 180,
                "height" to 18,
                "y" to y,
                "binding" to mapOf("type" to "state", "key" to key),
            )
        return mapOf(
            "id" to "test:controls",
            "entry" to "home",
            "state" to mapOf("notice" to true, "choice" to "normal", "name" to "Before"),
            "pages" to
                mapOf(
                    "home" to
                        mapOf(
                            "mode" to "canvas",
                            "title" to "Controls",
                            "width" to 180,
                            "height" to 126,
                            "elements" to
                                listOf(
                                    hit(
                                        "toggle",
                                        0,
                                        listOf(action("toggle-state", "state" to "notice")),
                                    ),
                                    hit(
                                        "open",
                                        18,
                                        listOf(action("open-popup", "popup" to "choices")),
                                    ),
                                    hit("edit", 36, listOf(action("open-page", "page" to "input"))),
                                    label("choice-label", "choice", 72),
                                    label("notice-label", "notice", 90),
                                    label("name-label", "name", 108),
                                ),
                            "popups" to
                                mapOf(
                                    "choices" to
                                        mapOf(
                                            "elements" to
                                                listOf(
                                                    hit(
                                                        "hard",
                                                        27,
                                                        listOf(
                                                            action(
                                                                "set-state",
                                                                "state" to "choice",
                                                                "value" to "hard",
                                                            ),
                                                            action("close-popup"),
                                                        ),
                                                    )
                                                )
                                        )
                                ),
                        ),
                    "input" to
                        mapOf(
                            "mode" to "canvas",
                            "title" to "Name",
                            "width" to 180,
                            "elements" to
                                listOf(
                                    mapOf(
                                        "id" to "input-heading",
                                        "type" to "text",
                                        "width" to 180,
                                        "height" to 18,
                                        "text" to "Edit name",
                                    )
                                ),
                            "components" to
                                listOf(
                                    mapOf(
                                        "id" to "name-input",
                                        "type" to "input",
                                        "text" to "Name",
                                        "state" to "name",
                                    ),
                                    mapOf(
                                        "id" to "apply",
                                        "type" to "button",
                                        "text" to "Apply",
                                        "actions" to listOf(action("submit"), action("back")),
                                    ),
                                    mapOf(
                                        "id" to "cancel",
                                        "type" to "button",
                                        "text" to "Cancel",
                                        "actions" to listOf(action("back")),
                                    ),
                                ),
                        ),
                ),
        )
    }

    private fun canvasTokens(): List<String> =
        wire.visible!!.messages.flatMap { it.parts }.mapNotNull { it.handle }.distinct()

    @Test
    fun childCollectionsAndConditionsSeeParentSelectionOnFirstRender() {
        fun list(id: String, source: String, y: Int) =
            mapOf<String, Any?>(
                "id" to id,
                "type" to "list",
                "source" to source,
                "page-state" to "page",
                "page-size" to 1,
                "row-height" to 18,
                "template" to "test:row",
                "y" to y,
            )
        val doc =
            mapOf(
                "id" to "test:dependent",
                "entry" to "home",
                "state" to mapOf("page" to 0, "selected" to null),
                "data" to
                    mapOf(
                        "parents" to mapOf("type" to "document", "key" to "test:data"),
                        "children" to
                            mapOf(
                                "type" to "record",
                                "source" to "parents",
                                "state" to "selected",
                                "key" to "children",
                            ),
                    ),
                "pages" to
                    mapOf(
                        "home" to
                            mapOf(
                                "mode" to "canvas",
                                "width" to 180,
                                "height" to 36,
                                "elements" to
                                    listOf(
                                        list("children", "children", 0) +
                                            mapOf(
                                                "visible" to
                                                    mapOf(
                                                        "type" to "state-equals",
                                                        "state" to "selected",
                                                        "value" to "parent",
                                                    )
                                            ),
                                        list("parents", "parents", 18) +
                                            mapOf("selection-state" to "selected"),
                                    ),
                            )
                    ),
            )
        val template =
            mapOf(
                "id" to "test:row",
                "component" to
                    mapOf(
                        "id" to "label",
                        "type" to "text",
                        "width" to 180,
                        "binding" to mapOf("type" to "item", "key" to "name"),
                    ),
            )
        val data =
            mapOf(
                "id" to "test:data",
                "items" to
                    listOf(
                        mapOf(
                            "id" to "parent",
                            "name" to "Parent",
                            "children" to
                                listOf(
                                    mapOf("id" to "child", "name" to "Child visible immediately")
                                ),
                        )
                    ),
            )
        val result =
            MenuCompiler(assets.dimensions())
                .compile(
                    mapOf("menu.yml" to doc),
                    mapOf("row.yml" to template),
                    mapOf("data.yml" to data),
                )
        engine.install(
            Candidate(
                assertNotNull(result.snapshot, result.diagnostics.toString()),
                assets,
                "test:dependent",
                600_000,
            )
        )
        engine.open(player, "test:dependent").join()
        assertTrue(wire.plain().contains("Child visible immediately"))
    }

    @Test
    fun canvasPopupsBlockUnderlyingHandlesAndDismissWithoutCommittingASelection() {
        engine.install(candidate(canvasControls()))
        assertTrue(engine.open(player, "test:controls").getNow(null).opened)
        engine.click(player, canvasTokens()[0], emptyMap())
        assertTrue(
            wire.plain().contains("false"),
            "A visual toggle must change only the player's own state",
        )
        val underlay = canvasTokens()
        assertEquals(3, underlay.size)
        engine.click(player, underlay[1], emptyMap())
        val popup = canvasTokens()
        assertEquals(2, popup.size, "Only outside-dismiss and the option receive capabilities")
        assertTrue(popup.none { it in underlay })
        val shown = wire.shown.size
        engine.click(player, underlay[2], emptyMap())
        assertEquals(shown, wire.shown.size, "A stale underlay token must not open the input page")
        assertTrue(wire.visible!!.inputs.isEmpty())
        engine.click(player, popup[0], emptyMap())
        assertTrue(wire.plain().contains("normal"), "Outside dismiss must not select an option")
        engine.click(player, canvasTokens()[1], emptyMap())
        engine.click(player, canvasTokens()[1], emptyMap())
        assertEquals(3, canvasTokens().size)
        assertTrue(wire.plain().contains("hard"))
        val oldOption = popup[1]
        val settled = wire.shown.size
        engine.click(player, oldOption, emptyMap())
        assertEquals(settled, wire.shown.size)
    }

    @Test
    fun canvasInputBridgeSubmitsOnlyOnConfirmationAndKeepsCancelState() {
        engine.install(candidate(canvasControls()))
        assertTrue(engine.open(player, "test:controls").getNow(null).opened)
        engine.click(player, canvasTokens()[2], emptyMap())
        assertEquals("Before", wire.visible!!.inputs.single().initial)
        engine.clickFromPacket(
            player,
            wire.visible!!.buttons[1].handle!!,
            mapOf("input_0" to "Cancelled"),
        )
        assertTrue(wire.plain().contains("Before"))
        assertFalse(wire.plain().contains("Cancelled"))
        engine.click(player, canvasTokens()[2], emptyMap())
        assertEquals("Before", wire.visible!!.inputs.single().initial)
        val apply = wire.visible!!.buttons[0].handle!!
        val count = wire.shown.size
        engine.clickFromPacket(player, apply, mapOf("name-input" to "forged"))
        assertEquals(count, wire.shown.size, "Author IDs must not bypass the wire field mapping")
        engine.clickFromPacket(player, apply, mapOf("input_0" to "After"))
        assertTrue(wire.plain().contains("After"))
        engine.click(player, canvasTokens()[2], emptyMap())
        assertEquals("After", wire.visible!!.inputs.single().initial)
    }

    @Test
    fun inlineScriptsRunInOrderAndLateResultsDoNotReopenClosedViews() {
        lateinit var sessionId: UUID
        engine.close()
        val first = CompletableFuture<Any?>()
        val second = CompletableFuture<Any?>()
        val calls = mutableListOf<String>()
        engine =
            RafterEngine(
                plugin,
                temp,
                wire,
                post = { posted += it },
                scriptEvaluator = { source, context ->
                    sessionId = context.sessionId
                    calls += source
                    when (calls.size) {
                        1 -> first
                        2 -> second
                        else -> error("Only the two visible inline expressions may execute")
                    }
                },
            ) {
                now
            }
        val button =
            mapOf(
                "id" to "run",
                "type" to "button",
                "text" to "Run",
                "tooltip" to "&a{{ repeat }}\n&7{{ repeat }}",
                "actions" to listOf(mapOf("type" to "close")),
            )
        val hidden =
            mapOf(
                "id" to "hidden",
                "type" to "text",
                "text" to "{{ never }}",
                "visible" to mapOf("type" to "constant", "value" to false),
            )
        engine.install(candidate(minimal(components = listOf(button, hidden))))
        val opened = engine.open(player, "test:menu")
        assertFalse(opened.isDone)
        assertEquals(listOf("repeat"), calls)
        first.complete("&c[OP](command=op me) {{ never }}")
        assertEquals(
            listOf("repeat", "repeat"),
            calls,
            "Repeated source must have distinct occurrences and preserve order",
        )
        assertFalse(opened.isDone)
        engine.close(player, sessionId).getNow(null)
        second.complete("late")
        assertTrue(opened.isDone)
        assertFalse(opened.getNow(null).opened)
        assertTrue(wire.shown.isEmpty(), "Closed view must not be sent by an old inline Future")
        assertNull(wire.visible)
        assertEquals(listOf("repeat", "repeat"), calls)
    }

    @Test
    fun defaultMenuRequiresAnInstalledSnapshotAndOpeningUsesItsId() {
        val missing = assertFailsWith<UiOperationException> { engine.defaultMenu() }
        assertEquals(UiFailureCode.NO_VALID_SNAPSHOT, missing.code)
        assertTrue(wire.shown.isEmpty())
        engine.install(candidate(other()))
        assertEquals("test:other", engine.defaultMenu())
        assertTrue(engine.open(player, engine.defaultMenu()).getNow(null).opened)
        assertEquals("replacement", wire.plain())
        engine.close()
        assertEquals(
            UiFailureCode.ENGINE_STOPPED,
            engine.open(player, "test:other").getNow(null).failure,
        )
    }

    @Test
    fun nativeItemsAreResolvedAtTheBukkitBoundaryWithoutEnteringTheRenderPlan() {
        val item = org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE)
        engine.registerItems(
            plugin,
            "test:items",
            ProviderDescriptor("items"),
            ItemProvider { _, id ->
                assertEquals("one", id)
                CompletableFuture.completedFuture(item)
            },
        )
        val node =
            mapOf(
                "id" to "display",
                "type" to "item",
                "item" to "test:items#one",
                "text" to "A native item",
            )
        engine.install(candidate(minimal(components = listOf(node))))
        val opened = engine.open(player, "test:menu").getNow(null)
        assertTrue(opened.opened, opened.failure?.name)
        assertEquals("display", wire.visible!!.items.single().node.id)
        assertEquals(org.bukkit.Material.STONE, wire.nativeItems.getValue("display").type)
        assertNotSame(
            item,
            wire.nativeItems.getValue("display"),
            "Provider stacks are snapshotted before transport",
        )
    }

    @Test
    fun typedOptionalDefaultsReachDataConditionAndActionCallbacks() {
        val optionalWord =
            ParameterSpec(
                ParameterType.STRING,
                required = false,
                hasDefault = true,
                defaultValue = "hello",
            )
        val optionalFlag =
            ParameterSpec(
                ParameterType.BOOLEAN,
                required = false,
                hasDefault = true,
                defaultValue = true,
            )
        val optionalCount =
            ParameterSpec(
                ParameterType.INTEGER,
                required = false,
                hasDefault = true,
                defaultValue = 3,
                minimum = 0,
                maximum = 10,
            )
        var performed = 0
        engine.registerData(
            plugin,
            "test:label",
            ProviderDescriptor("label", mapOf("word" to optionalWord)),
            DataProvider { _, args ->
                CompletableFuture.completedFuture(args["word"])
            },
        )
        engine.registerCondition(
            plugin,
            "test:flag",
            ProviderDescriptor("flag", mapOf("flag" to optionalFlag)),
            ConditionProvider { _, args ->
                CompletableFuture.completedFuture(args["flag"] as Boolean)
            },
        )
        engine.registerAction(
            plugin,
            "test:act",
            ProviderDescriptor("act", mapOf("count" to optionalCount)),
            ActionProvider { _, args ->
                performed = (args["count"] as Number).toInt()
                CompletableFuture.completedFuture(UiActionResult.empty())
            },
        )
        val label =
            mapOf(
                "id" to "label",
                "type" to "text",
                "binding" to mapOf("type" to "data", "key" to "test:label"),
                "visible" to mapOf("type" to "provider", "provider" to "test:flag"),
            )
        val button =
            mapOf(
                "id" to "act",
                "type" to "button",
                "text" to "Act",
                "actions" to listOf(mapOf("type" to "provider", "provider" to "test:act")),
            )
        engine.install(candidate(minimal(components = listOf(label, button))))
        assertTrue(engine.open(player, "test:menu").getNow(null).opened)
        assertEquals("hello", wire.plain())
        engine.click(player, wire.visible!!.buttons.single().handle!!, emptyMap())
        assertEquals(3, performed)
    }

    @Test
    fun authoringExportsContainOfflineDataAndNeverCallProviders() {
        var calls = 0
        engine.registerData(
            plugin,
            "test:unused",
            ProviderDescriptor(
                "offline",
                mapOf(
                    "value" to
                        ParameterSpec(
                            ParameterType.ANY,
                            required = false,
                            nullable = true,
                            hasDefault = true,
                        )
                ),
            ),
            DataProvider { _, _ ->
                calls++
                CompletableFuture.completedFuture("private player data")
            },
        )
        engine.install(candidate(minimal()))
        val directory = engine.exportContract()
        for (name in
            listOf(
                "menu.schema.json",
                "assets.schema.json",
                "template.schema.json",
                "authoring.json",
                "preview-data.json",
                "layouts.json",
            )) {
            assertTrue(Files.isRegularFile(directory.resolve(name)), name)
        }
        val editor = Json.map(directory.resolve("authoring.json"))
        assertEquals(
            com.hiusers.rafter.compile.AuthoringContract.VERSION.toDouble(),
            editor["contract-version"],
        )
        assertTrue(editor.toString().contains("explicit-stubs-only"))
        @Suppress("UNCHECKED_CAST") val extensions = editor["extensions"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val providers = extensions["data"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val parameters = providers.getValue("test:unused")["parameters"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val properties = parameters["properties"] as Map<String, Map<String, Any?>>
        assertTrue(
            properties.getValue("value").containsKey("default"),
            "A null default must not disappear during JSON serialization",
        )
        assertNull(properties.getValue("value")["default"])
        assertEquals(0, calls)
        assertFalse(
            Files.readString(directory.resolve("preview-data.json")).contains("private player data")
        )
        assertTrue(Files.readString(directory.resolve("layouts.json")).contains("native-client"))
    }

    @Test
    fun collectionRowsCarryContextAndClicksCannotRetargetAfterReorderOrClose() {
        lateinit var sessionId: UUID
        engine.close()
        val scripted = mutableListOf<String>()
        engine =
            RafterEngine(
                plugin,
                temp,
                wire,
                post = { posted += it },
                scriptEvaluator = { _, context ->
                    val name =
                        context.item?.get("name") as? String ?: error("Missing row script context")
                    scripted += name
                    CompletableFuture.completedFuture(name)
                },
                clock = { now },
            )
        var records = listOf(mapOf("id" to "a", "name" to "A"), mapOf("id" to "b", "name" to "B"))
        var pending: CompletableFuture<Any?>? = null
        engine.registerData(
            plugin,
            "test:people",
            ProviderDescriptor("People"),
            DataProvider { context, _ ->
                sessionId = context.sessionId

                pending ?: CompletableFuture.completedFuture(records)
            },
        )
        val readRows = mutableListOf<String>()
        engine.registerData(
            plugin,
            "test:row-label",
            ProviderDescriptor("Label"),
            DataProvider { context, _ ->
                val name =
                    context.item?.get("name") as? String ?: error("Missing row provider context")
                readRows += name
                CompletableFuture.completedFuture(name)
            },
        )
        val selected = mutableListOf<String>()
        engine.registerAction(
            plugin,
            "test:select",
            ProviderDescriptor("Select"),
            ActionProvider { context, _ ->
                selected +=
                    context.item?.get("id") as? String ?: error("Missing row action context")
                CompletableFuture.completedFuture(UiActionResult.empty())
            },
        )
        val row =
            mapOf(
                "id" to "test:row",
                "component" to
                    mapOf(
                        "id" to "row",
                        "type" to "group",
                        "elements" to
                            listOf(
                                mapOf(
                                    "id" to "name",
                                    "type" to "text",
                                    "width" to 80,
                                    "height" to 10,
                                    "binding" to mapOf("type" to "data", "key" to "test:row-label"),
                                ),
                                mapOf(
                                    "id" to "hit",
                                    "type" to "hit-area",
                                    "width" to 80,
                                    "height" to 18,
                                    "tooltip" to "{{ label }}",
                                    "actions" to
                                        listOf(
                                            mapOf("type" to "select-item"),
                                            mapOf(
                                                "type" to "provider",
                                                "provider" to "test:select",
                                            ),
                                        ),
                                ),
                            ),
                    ),
            )
        val document =
            mapOf(
                "id" to "test:people",
                "entry" to "home",
                "state" to mapOf("page" to 0, "selected" to null),
                "data" to mapOf("people" to mapOf("type" to "data", "key" to "test:people")),
                "pages" to
                    mapOf(
                        "home" to
                            mapOf(
                                "mode" to "canvas",
                                "width" to 180,
                                "elements" to
                                    listOf(
                                        mapOf(
                                            "id" to "people",
                                            "type" to "list",
                                            "template" to "test:row",
                                            "source" to "people",
                                            "page-state" to "page",
                                            "selection-state" to "selected",
                                            "page-size" to 2,
                                            "row-height" to 18,
                                        )
                                    ),
                            )
                    ),
            )
        val result =
            MenuCompiler(assets.dimensions())
                .compile(mapOf("people.yml" to document), mapOf("row.yml" to row))
        engine.install(
            Candidate(
                assertNotNull(result.snapshot, result.diagnostics.toString()),
                assets,
                "test:people",
                600_000,
            )
        )
        assertTrue(engine.open(player, "test:people").join().opened)
        assertEquals(listOf("A", "B"), readRows)
        assertEquals(listOf("A", "B"), scripted)
        val first = canvasTokens().first()
        records = records.reversed()
        engine.click(player, first, emptyMap())
        assertEquals(
            listOf("a"),
            selected,
            "Presented row zero must keep a, not become reordered b",
        )
        val next = canvasTokens().first()
        records = records.filter { it["id"] != "b" }
        engine.click(player, next, emptyMap())
        assertEquals(listOf("a"), selected, "A removed record must not execute its old action")
        engine.close(player, sessionId).join()
        pending = CompletableFuture()
        val opening = engine.open(player, "test:people")
        assertFalse(opening.isDone)
        val shown = wire.shown.size
        engine.close(player, sessionId).join()
        pending!!.complete(records)
        assertEquals(
            shown,
            wire.shown.size,
            "Late collection results cannot reopen a closed Dialog",
        )
    }

    @Test
    fun supersededDataFutureCannotOverwriteNewFrame() {
        lateinit var sessionId: UUID
        val reads = mutableListOf<CompletableFuture<Any?>>()
        engine.registerData(
            plugin,
            "test:slow",
            ProviderDescriptor("slow"),
            DataProvider { context, _ ->
                sessionId = context.sessionId
                CompletableFuture<Any?>().also(reads::add)
            },
        )
        engine.install(candidate(slow()))
        val first = engine.open(player, "test:menu")
        val refreshed = engine.refresh(player, sessionId)
        assertFalse(first.isDone)
        assertEquals(1, reads.size)
        reads[0].complete("old")
        nextTick()
        assertFalse(first.getNow(null).opened)
        assertEquals(UiFailureCode.SUPERSEDED, first.getNow(null).failure)
        assertEquals(2, reads.size)
        assertTrue(wire.shown.isEmpty())
        reads[1].complete("new")
        assertTrue(refreshed.isDone)
        assertEquals("new", wire.plain())
        assertEquals(1, wire.shown.size)
    }

    @Test
    fun closeCompletesOldOperationBeforeItsContinuationOpensReplacement() {
        lateinit var sessionId: UUID
        engine.registerData(
            plugin,
            "test:slow",
            ProviderDescriptor("slow"),
            DataProvider { context, _ ->
                sessionId = context.sessionId
                CompletableFuture()
            },
        )
        engine.install(candidate(slow(), other()))
        val first = engine.open(player, "test:menu")
        first.thenAccept { engine.open(player, "test:other") }
        engine.close(player, sessionId)
        assertEquals("replacement", wire.plain())
        assertEquals(1, wire.clears)
    }

    @Test
    fun refreshContinuationCanSupersedeRefreshWithoutLosingNewScreen() {
        lateinit var sessionId: UUID
        val reads = mutableListOf<CompletableFuture<Any?>>()
        engine.registerData(
            plugin,
            "test:slow",
            ProviderDescriptor("slow"),
            DataProvider { context, _ ->
                sessionId = context.sessionId
                CompletableFuture<Any?>().also(reads::add)
            },
        )
        engine.install(candidate(slow(), other()))
        engine.open(player, "test:menu").thenAccept { engine.open(player, "test:other") }
        val refresh = engine.refresh(player, sessionId)
        reads[0].complete("discarded")
        nextTick()
        assertTrue(refresh.isCompletedExceptionally)
        assertEquals("replacement", wire.plain())
        reads.last().complete("late")
        assertEquals("replacement", wire.plain())
    }

    @Test
    fun publishedSnapshotIsAvailableToCancellationContinuation() {
        engine.registerData(
            plugin,
            "test:slow",
            ProviderDescriptor("slow"),
            DataProvider { _, _ -> CompletableFuture() },
        )
        engine.install(candidate(slow()))
        engine.open(player, "test:menu").thenAccept { engine.open(player, engine.defaultMenu()) }
        engine.install(candidate(other()))
        assertEquals("replacement", wire.plain())
    }

    @Test
    fun permissionRevocationWhileWaitingNeverShowsProtectedData() {
        val data = CompletableFuture<Any?>()
        engine.registerData(
            plugin,
            "test:slow",
            ProviderDescriptor("slow"),
            DataProvider { _, _ -> data },
        )
        engine.install(candidate(slow() + mapOf("permission" to "test:view")))
        val opened = engine.open(player, "test:menu")
        permitted = false
        data.complete("secret")
        assertFalse(opened.getNow(null).opened)
        assertTrue(wire.shown.isEmpty())
        assertNull(wire.visible)
    }

    @Test
    fun hiddenComponentsDoNotReadTheirDataProvider() {
        var reads = 0
        engine.registerData(
            plugin,
            "test:slow",
            ProviderDescriptor("slow"),
            DataProvider { _, _ ->
                reads++
                CompletableFuture.completedFuture("hidden")
            },
        )
        engine.install(
            candidate(slow(mapOf("type" to "state-equals", "state" to "enabled", "value" to false)))
        )
        assertTrue(engine.open(player, "test:menu").getNow(null).opened)
        assertEquals(0, reads)
        assertEquals("", wire.plain())
    }

    @Test
    fun duplicateActionAndLateActionCompletionCannotReopenClosedUi() {
        val action = CompletableFuture<UiActionResult>()
        var calls = 0
        engine.registerAction(
            plugin,
            "test:action",
            ProviderDescriptor("action"),
            ActionProvider { _, _ ->
                calls++
                action
            },
        )
        val button =
            mapOf(
                "id" to "button",
                "type" to "button",
                "text" to "Run",
                "actions" to listOf(mapOf("type" to "provider", "provider" to "test:action")),
            )
        engine.install(candidate(minimal(components = listOf(button))))
        val opened = engine.open(player, "test:menu").getNow(null)
        assertTrue(opened.opened)
        val sessionId = assertNotNull(opened.sessionId)
        val handle = wire.visible!!.buttons.single().handle!!
        engine.click(player, handle, emptyMap())
        engine.click(player, handle, emptyMap())
        assertEquals(1, calls)
        engine.close(player, sessionId)
        action.complete(UiActionResult.empty())
        assertNull(wire.visible)
        assertEquals(1, wire.shown.size)
    }

    @Test
    fun unregisteringProviderInvalidatesPendingUiBeforeDataCompletes() {
        val data = CompletableFuture<Any?>()
        val registration =
            engine.registerData(
                plugin,
                "test:slow",
                ProviderDescriptor("slow"),
                DataProvider { _, _ -> data },
            )
        engine.install(candidate(slow()))
        val opened = engine.open(player, "test:menu")
        registration.close()
        data.complete("late")
        assertFalse(opened.getNow(null).opened)
        assertTrue(wire.shown.isEmpty())
        assertNull(wire.visible)
    }

    @Test
    fun repeatedRefreshesShareOneFollowUpAndDoNotLaunchUnboundedReads() {
        lateinit var sessionId: UUID
        val reads = mutableListOf<CompletableFuture<Any?>>()
        engine.registerData(
            plugin,
            "test:slow",
            ProviderDescriptor("slow"),
            DataProvider { context, _ ->
                sessionId = context.sessionId
                CompletableFuture<Any?>().also(reads::add)
            },
        )
        engine.install(candidate(slow()))
        engine.open(player, "test:menu")
        val requests = List(20) { engine.refresh(player, sessionId) }
        assertEquals(1, reads.size)
        reads[0].complete("discarded")
        nextTick()
        assertEquals(2, reads.size)
        assertTrue(wire.shown.isEmpty())
        reads[1].complete("latest")
        assertTrue(requests.all { it.isDone && !it.isCompletedExceptionally })
        assertEquals(1, wire.shown.size)
        assertEquals("latest", wire.plain())
    }

    @Test
    fun renderDeadlineClosesAndLateCompletionCannotRedisplay() {
        lateinit var sessionId: UUID
        val pending = CompletableFuture<Any?>()
        engine.registerData(
            plugin,
            "test:slow",
            ProviderDescriptor("slow"),
            DataProvider { context, _ ->
                sessionId = context.sessionId
                pending
            },
        )
        engine.install(
            candidate(slow())
                .copy(
                    runtime = com.hiusers.rafter.runtime.RuntimePolicy(renderTimeoutMillis = 1000)
                )
        )
        val opened = engine.open(player, "test:menu")
        val refreshed = engine.refresh(player, sessionId)
        now = 1_000_000_000L
        engine.tick()
        assertEquals(UiFailureCode.TIMED_OUT, opened.getNow(null).failure)
        assertTrue(refreshed.isCompletedExceptionally)
        pending.complete("late")
        assertTrue(wire.shown.isEmpty())
        assertNull(wire.visible)
    }

    @Test
    fun deadlineIsAlsoCheckedOnCompletionBeforeTheNextTicker() {
        val pending = CompletableFuture<Any?>()
        engine.registerData(
            plugin,
            "test:slow",
            ProviderDescriptor("slow"),
            DataProvider { _, _ -> pending },
        )
        engine.install(
            candidate(slow())
                .copy(
                    runtime = com.hiusers.rafter.runtime.RuntimePolicy(renderTimeoutMillis = 1000)
                )
        )
        val opened = engine.open(player, "test:menu")
        now = 1_000_000_001L
        pending.complete("too late")
        assertEquals(UiFailureCode.TIMED_OUT, opened.getNow(null).failure)
        assertTrue(wire.shown.isEmpty())
    }

    @Test
    fun refreshDuringAnActionWaitsForOnePostActionFrame() {
        val pending = CompletableFuture<UiActionResult>()
        var reads = 0
        engine.registerData(
            plugin,
            "test:label",
            ProviderDescriptor("label"),
            DataProvider { _, _ -> CompletableFuture.completedFuture("read${++reads}") },
        )
        engine.registerAction(
            plugin,
            "test:action",
            ProviderDescriptor("action"),
            ActionProvider { _, _ -> pending },
        )
        val label =
            mapOf(
                "id" to "label",
                "type" to "text",
                "binding" to mapOf("type" to "data", "key" to "test:label"),
            )
        val button =
            mapOf(
                "id" to "run",
                "type" to "button",
                "text" to "Run",
                "actions" to listOf(mapOf("type" to "provider", "provider" to "test:action")),
            )
        engine.install(candidate(minimal(components = listOf(label, button))))
        val sessionId = assertNotNull(engine.open(player, "test:menu").getNow(null).sessionId)
        engine.click(player, wire.visible!!.buttons.single().handle!!, emptyMap())
        val refreshes = List(10) { engine.refresh(player, sessionId) }
        assertEquals(1, reads)
        assertTrue(refreshes.none { it.isDone })
        pending.complete(UiActionResult.empty())
        assertEquals(2, reads)
        assertEquals(2, wire.shown.size)
        assertTrue(refreshes.all { it.isDone && !it.isCompletedExceptionally })
    }

    @Test
    fun actionTimeoutDoesNotRunTheRestOfTheChainOrRetrySideEffects() {
        val pending = CompletableFuture<UiActionResult>()
        var calls = 0
        var nextCalls = 0
        engine.registerAction(
            plugin,
            "test:action",
            ProviderDescriptor("action"),
            ActionProvider { _, _ ->
                calls++
                pending
            },
        )
        engine.registerAction(
            plugin,
            "test:next",
            ProviderDescriptor("next"),
            ActionProvider { _, _ ->
                nextCalls++
                CompletableFuture.completedFuture(UiActionResult.empty())
            },
        )
        val button =
            mapOf(
                "id" to "run",
                "type" to "button",
                "text" to "Run",
                "actions" to
                    listOf(
                        mapOf("type" to "provider", "provider" to "test:action"),
                        mapOf("type" to "provider", "provider" to "test:next"),
                    ),
            )
        engine.install(
            candidate(minimal(components = listOf(button)))
                .copy(
                    runtime = com.hiusers.rafter.runtime.RuntimePolicy(actionTimeoutMillis = 1000)
                )
        )
        val sessionId = assertNotNull(engine.open(player, "test:menu").getNow(null).sessionId)
        val handle = wire.visible!!.buttons.single().handle!!
        engine.click(player, handle, emptyMap())
        val refresh = engine.refresh(player, sessionId)
        now = 1_000_000_000L
        engine.tick()
        assertTrue(refresh.isCompletedExceptionally)
        assertNull(wire.visible)
        pending.complete(UiActionResult.empty())
        engine.click(player, handle, emptyMap())
        assertEquals(1, calls)
        assertEquals(0, nextCalls)
        assertNull(wire.visible)
    }

    @Test
    fun independentDataReadsAreBoundedAndDuplicateBindingsAreDeduplicated() {
        val reads = mutableListOf<CompletableFuture<Any?>>()
        engine.registerData(
            plugin,
            "test:read",
            ProviderDescriptor("read", mapOf("key" to ParameterSpec(ParameterType.INTEGER))),
            DataProvider { _, _ -> CompletableFuture<Any?>().also(reads::add) },
        )
        val components =
            (0..5).map { index ->
                mapOf(
                    "id" to "label$index",
                    "type" to "text",
                    "binding" to
                        mapOf(
                            "type" to "data",
                            "key" to "test:read",
                            "args" to mapOf("key" to index),
                        ),
                )
            }
        engine.install(
            candidate(
                    minimal(components = components + components.first().plus("id" to "duplicate"))
                )
                .copy(runtime = com.hiusers.rafter.runtime.RuntimePolicy(readConcurrency = 2))
        )
        val opened = engine.open(player, "test:menu")
        assertEquals(2, reads.size)
        reads[1].complete("B")
        assertEquals(3, reads.size)
        reads[0].complete("A")
        assertEquals(4, reads.size)
        reads[2].complete("C")
        reads[3].complete("D")
        assertEquals(6, reads.size)
        reads[4].complete("E")
        reads[5].complete("F")
        assertTrue(opened.getNow(null).opened)
        assertEquals("ABCDEFA", wire.plain())
    }

    @Test
    fun staleRegistrationCloseCannotInvalidateAReplacementSession() {
        val old =
            engine.registerData(
                plugin,
                "test:slow",
                ProviderDescriptor("old"),
                DataProvider { _, _ -> CompletableFuture.completedFuture("old") },
            )
        old.close()
        engine.registerData(
            plugin,
            "test:slow",
            ProviderDescriptor("new"),
            DataProvider { _, _ -> CompletableFuture.completedFuture("new") },
        )
        engine.install(candidate(slow()))
        val sessionId = assertNotNull(engine.open(player, "test:menu").getNow(null).sessionId)
        old.close()
        assertEquals("new", wire.plain())
        assertTrue(engine.refresh(player, sessionId).isDone)
        assertEquals("new", wire.plain())
    }

    @Test
    fun unknownOpenHasAStableFailureWithoutReplacingTheLiveUi() {
        engine.install(candidate(minimal()))
        assertTrue(engine.open(player, "test:menu").getNow(null).opened)
        assertEquals(
            UiFailureCode.UNKNOWN_MENU,
            engine.open(player, "test:missing").getNow(null).failure,
        )
        assertEquals(1, wire.shown.size)
        engine.close()
        assertEquals(
            UiFailureCode.ENGINE_STOPPED,
            engine.open(player, "test:menu").getNow(null).failure,
        )
    }

    @Test
    fun clickRechecksTheCurrentConditionInsteadOfTrustingAnIssuedHandle() {
        var allowed = true
        var calls = 0
        engine.registerCondition(
            plugin,
            "test:allowed",
            ProviderDescriptor("allowed"),
            ConditionProvider { _, _ -> CompletableFuture.completedFuture(allowed) },
        )
        engine.registerAction(
            plugin,
            "test:action",
            ProviderDescriptor("action"),
            ActionProvider { _, _ ->
                calls++
                CompletableFuture.completedFuture(UiActionResult.empty())
            },
        )
        val button =
            mapOf(
                "id" to "run",
                "type" to "button",
                "text" to "Run",
                "enabled" to mapOf("type" to "provider", "provider" to "test:allowed"),
                "actions" to listOf(mapOf("type" to "provider", "provider" to "test:action")),
            )
        engine.install(candidate(minimal(components = listOf(button))))
        engine.open(player, "test:menu")
        val handle = wire.visible!!.buttons.single().handle!!
        allowed = false
        engine.click(player, handle, emptyMap())
        engine.click(player, handle, emptyMap())
        assertEquals(0, calls)
        assertEquals(2, wire.shown.size)
        assertNull(wire.visible!!.buttons.single().handle)
    }

    @Test
    fun invalidSubmittedFieldsNeverCommitOrInvokeTheBusinessAction() {
        var calls = 0
        engine.registerAction(
            plugin,
            "test:action",
            ProviderDescriptor("action"),
            ActionProvider { context, _ ->
                calls++
                assertEquals("new", context.state["name"])
                CompletableFuture.completedFuture(UiActionResult.empty())
            },
        )
        val input = mapOf("id" to "name", "type" to "input", "state" to "name", "max-length" to 4)
        val submit =
            mapOf(
                "id" to "save",
                "type" to "button",
                "text" to "Save",
                "actions" to
                    listOf(
                        mapOf("type" to "submit"),
                        mapOf("type" to "provider", "provider" to "test:action"),
                    ),
            )
        engine.install(candidate(minimal(components = listOf(input, submit))))
        engine.open(player, "test:menu")
        engine.click(
            player,
            wire.visible!!.buttons.single().handle!!,
            mapOf("name" to "new", "injected" to "bad"),
        )
        assertEquals(0, calls)
        assertEquals("old", wire.visible!!.inputs.single().initial)
        engine.click(player, wire.visible!!.buttons.single().handle!!, mapOf("name" to "too long"))
        assertEquals(0, calls)
        assertEquals("old", wire.visible!!.inputs.single().initial)
        engine.click(player, wire.visible!!.buttons.single().handle!!, mapOf("name" to "new"))
        assertEquals(1, calls)
        assertEquals("new", wire.visible!!.inputs.single().initial)
    }

    @Test
    fun navigationSendsOneFrameAndDoesNotAutoRefreshTwice() {
        val button =
            mapOf(
                "id" to "next",
                "type" to "button",
                "text" to "Next",
                "actions" to listOf(mapOf("type" to "open-page", "page" to "next")),
            )
        val document = minimal(components = listOf(button))
        @Suppress("UNCHECKED_CAST") val pages = document.getValue("pages") as Map<String, Any?>
        val next =
            mapOf(
                "mode" to "native",
                "title" to "Next",
                "components" to
                    listOf(mapOf("id" to "label", "type" to "text", "text" to "next page")),
            )
        engine.install(candidate(document + mapOf("pages" to pages.plus("next" to next))))
        engine.open(player, "test:menu")
        engine.click(player, wire.visible!!.buttons.single().handle!!, emptyMap())
        assertEquals(2, wire.shown.size)
        assertEquals("next page", wire.plain())
    }

    @Test
    fun refreshIssuedFromAReadYieldsInsteadOfRecursivelyRenderingOnTheSameStack() {
        var reads = 0
        engine.registerData(
            plugin,
            "test:slow",
            ProviderDescriptor("slow"),
            DataProvider { context, _ ->
                reads++
                if (reads < 3) engine.refresh(player, context.sessionId)
                CompletableFuture.completedFuture("read$reads")
            },
        )
        engine.install(candidate(slow()))
        engine.open(player, "test:menu")
        assertEquals(1, reads)
        assertEquals(1, posted.size)
        assertTrue(wire.shown.isEmpty())
        nextTick()
        assertEquals(2, reads)
        assertEquals(1, posted.size)
        assertTrue(wire.shown.isEmpty())
        nextTick()
        assertEquals(3, reads)
        assertTrue(posted.isEmpty())
        assertEquals("read3", wire.plain())
        assertEquals(1, wire.shown.size)
    }

    @Test
    fun delayedDisconnectDoesNotDetachANewOnlineSessionAndOfflineResultsStayStale() {
        val pending = CompletableFuture<Any?>()
        engine.registerData(
            plugin,
            "test:slow",
            ProviderDescriptor("slow"),
            DataProvider { _, _ -> pending },
        )
        engine.install(candidate(slow(), other()))
        val old = engine.open(player, "test:menu")
        online = false
        engine.forgetIfOffline(player.uniqueId)
        assertEquals(UiFailureCode.SUPERSEDED, old.getNow(null).failure)
        online = true
        val sessionId = assertNotNull(engine.open(player, "test:other").getNow(null).sessionId)
        engine.forgetIfOffline(player.uniqueId) // 旧连接的退出事件晚于新登录到达。
        pending.complete("old connection")
        assertEquals("replacement", wire.plain())
        assertEquals(1, wire.shown.size)
        assertFalse(engine.refresh(player, sessionId).isCompletedExceptionally)
        assertEquals("replacement", wire.plain())
    }

    @Test
    fun canvasNativeFormUsesRealControlsAndRechecksInputVariantsBeforeAtomicSubmit() {
        var alternate = true
        val committed = mutableListOf<Map<String, Any?>>()
        engine.registerCondition(
            plugin,
            "test:alternate",
            ProviderDescriptor("alternate"),
            ConditionProvider { _, _ ->
                CompletableFuture.completedFuture(alternate)
            },
        )
        engine.registerAction(
            plugin,
            "test:commit",
            ProviderDescriptor("commit"),
            ActionProvider { context, _ ->
                committed += context.state.toMap()
                CompletableFuture.completedFuture(UiActionResult.empty())
            },
        )
        engine.registerItems(
            plugin,
            "test:items",
            ProviderDescriptor("items"),
            ItemProvider { _, _ ->
                CompletableFuture.completedFuture(
                    org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE)
                )
            },
        )
        val components =
            listOf(
                mapOf(
                    "id" to "caption",
                    "type" to "text",
                    "binding" to mapOf("type" to "state", "key" to "name"),
                ),
                mapOf("id" to "item", "type" to "item", "item" to "test:items#one", "width" to 200),
                mapOf(
                    "id" to "name",
                    "type" to "input",
                    "state" to "name",
                    "max-length" to 4,
                    "variants" to
                        listOf(
                            mapOf(
                                "when" to
                                    mapOf("type" to "provider", "provider" to "test:alternate"),
                                "override" to mapOf("text" to "Alternate"),
                            )
                        ),
                ),
                mapOf("id" to "check", "type" to "checkbox", "state" to "enabled"),
                mapOf(
                    "id" to "choice",
                    "type" to "select",
                    "state" to "choice",
                    "options" to mapOf("a" to "A", "b" to "B"),
                ),
                mapOf(
                    "id" to "range",
                    "type" to "slider",
                    "state" to "number",
                    "min" to 0,
                    "max" to 10,
                ),
                mapOf(
                    "id" to "save",
                    "type" to "button",
                    "text" to "Save",
                    "actions" to
                        listOf(
                            mapOf("type" to "submit"),
                            mapOf("type" to "provider", "provider" to "test:commit"),
                        ),
                ),
            )
        val doc =
            minimal() +
                ("pages" to
                    mapOf(
                        "main" to
                            mapOf(
                                "mode" to "canvas",
                                "title" to "Mixed",
                                "columns" to 2,
                                "elements" to
                                    listOf(
                                        mapOf(
                                            "id" to "hit",
                                            "type" to "hit-area",
                                            "width" to 9,
                                            "height" to 9,
                                            "actions" to listOf(mapOf("type" to "close")),
                                        )
                                    ),
                                "components" to components,
                            )
                    ))
        val installed = candidate(doc)
        val focus = DialogFocusShaders.bind(assets, installed.snapshot)
        assertTrue(
            focus.focusLayouts.isEmpty(),
            "Mixed flow must not reuse the fixed Canvas-only tail",
        )
        assertEquals(
            setOf("caption", "item"),
            focus.nativeFocusLayouts.map { it.component }.toSet(),
        )
        assertEquals(
            listOf(32, 32, -202, 1),
            focus.nativeFocusLayouts.single { it.kind == NativeFocusKind.ITEM }.vector(),
        )
        engine.install(installed)
        val opened = engine.open(player, "test:menu").getNow(null)
        assertTrue(opened.opened)
        val sessionId = assertNotNull(opened.sessionId)
        assertEquals(4, wire.visible!!.inputs.size)
        assertEquals("Alternate", wire.visible!!.inputs.first().label.toPlainText())
        assertEquals("item", wire.visible!!.items.single().node.id)
        assertEquals(org.bukkit.Material.STONE, wire.nativeItems.getValue("item").type)
        assertEquals(2, wire.visible!!.columns)
        fun token() = assertNotNull(wire.visible!!.buttons.single().handle)
        val values = mapOf("name" to "new", "check" to "false", "choice" to "b", "range" to "6")
        val stale = token()
        alternate = false
        engine.click(player, stale, values)
        assertTrue(
            committed.isEmpty(),
            "A changed input variant must invalidate the published form",
        )
        val beforeInvalid = wire.shown.size
        engine.click(player, token(), values + ("name" to "too long"))
        assertTrue(committed.isEmpty())
        assertTrue(wire.shown.size > beforeInvalid)
        assertTrue(
            wire.visible!!.messages.size >= 3,
            "Canvas must include native form errors and caption",
        )
        assertEquals("old", wire.visible!!.messages[1].parts.single().text.toPlainText())
        assertEquals("b", wire.visible!!.inputs.single { it.node.id == "choice" }.initial)
        val valid = token()
        engine.click(player, valid, values)
        assertEquals(1, committed.size)
        assertEquals(
            mapOf("name" to "new", "enabled" to false, "choice" to "b", "number" to 6.0),
            committed.single(),
        )
        engine.click(player, valid, values)
        engine.click(player, stale, values)
        assertEquals(1, committed.size, "Consumed and previous-frame handles stay invalid")
        val closed = token()
        engine.close(player, sessionId).getNow(null)
        engine.click(player, closed, values)
        assertEquals(1, committed.size)
        assertNull(wire.visible)
    }

    private fun actionDocument(vararg providers: String): Map<String, Any?> =
        minimal(
            components =
                listOf(
                    mapOf(
                        "id" to "status",
                        "type" to "text",
                        "binding" to mapOf("type" to "state", "key" to "status"),
                    ),
                    mapOf(
                        "id" to "run",
                        "type" to "button",
                        "text" to "Run",
                        "actions" to
                            providers.map { mapOf("type" to "provider", "provider" to it) },
                    ),
                )
        ) + mapOf("state" to mapOf("status" to "before", "result" to null))

    private fun itemListCandidate(): Candidate {
        val row =
            mapOf(
                "id" to "test:item-row",
                "component" to mapOf("id" to "icon", "type" to "item", "item" to "test:items#row"),
            )
        val document =
            minimal(
                components =
                    listOf(
                        mapOf(
                            "id" to "attachments",
                            "type" to "list",
                            "source" to "attachments",
                            "page-state" to "page",
                            "selection-state" to "selected",
                            "template" to "test:item-row",
                            "page-size" to 2,
                            "filter" to
                                mapOf(
                                    "field" to "shown",
                                    "value" to true,
                                    "when" to
                                        mapOf(
                                            "type" to "state-equals",
                                            "state" to "filtered",
                                            "value" to true,
                                        ),
                                ),
                        ),
                        mapOf("id" to "preview", "type" to "item", "item" to "test:items#preview"),
                        mapOf(
                            "id" to "next",
                            "type" to "button",
                            "text" to "Next",
                            "actions" to
                                listOf(
                                    mapOf(
                                        "type" to "page-list",
                                        "list" to "attachments",
                                        "step" to 1,
                                    )
                                ),
                        ),
                        mapOf(
                            "id" to "filter",
                            "type" to "button",
                            "text" to "Filter",
                            "actions" to
                                listOf(mapOf("type" to "toggle-state", "state" to "filtered")),
                        ),
                    )
            ) +
                mapOf(
                    "state" to mapOf("page" to 0, "selected" to null, "filtered" to false),
                    "data" to
                        mapOf(
                            "attachments" to mapOf("type" to "data", "key" to "test:attachments")
                        ),
                )
        val result =
            MenuCompiler(assets.dimensions())
                .compile(mapOf("items.yml" to document), mapOf("row.yml" to row))
        val snapshot = assertNotNull(result.snapshot, result.diagnostics.toString())
        return Candidate(snapshot, assets, "test:menu", 600_000)
    }

    private fun clickButton(id: String) =
        engine.click(
            player,
            assertNotNull(wire.visible!!.buttons.single { it.node.id == id }.handle),
            emptyMap(),
        )

    private fun rowItemTypes() =
        wire.visible!!
            .items
            .filter { it.node.rowScope != null }
            .map { wire.nativeItems.getValue(it.node.id).type }

    private fun failure(future: CompletableFuture<*>): UiFailureCode =
        assertIs<UiOperationException>(
                assertFailsWith<java.util.concurrent.CompletionException> { future.join() }.cause
            )
            .code

    private fun background(block: () -> Unit) {
        val done = CompletableFuture<Void>()
        val worker =
            Thread(
                {
                    try {
                        block()
                        done.complete(null)
                    } catch (error: Throwable) {
                        done.completeExceptionally(error)
                    }
                },
                "Rafter-JVM-fixture",
            )
        worker.start()
        worker.join(5_000)
        assertFalse(worker.isAlive, "Fixture must not block on UI work")
        done.join()
    }

    private fun drainMain() {
        while (true) (mainTasks.poll() ?: return).invoke()
    }

    @Test
    fun listItemsUseTheirOwnReadonlyRecordsWithOutOfOrderCompletionPagingAndFiltering() {
        val records =
            listOf(
                mapOf("id" to "a", "shown" to true),
                mapOf("id" to "b", "shown" to false),
                mapOf("id" to "c", "shown" to true),
            )
        engine.registerData(
            plugin,
            "test:attachments",
            ProviderDescriptor("attachments"),
            DataProvider { _, _ -> CompletableFuture.completedFuture(records) },
        )
        val requests =
            mutableListOf<Pair<UiContext, CompletableFuture<org.bukkit.inventory.ItemStack>>>()
        engine.registerItems(
            plugin,
            "test:items",
            ProviderDescriptor("items"),
            ItemProvider { context, id ->
                assertSame(mainThread, Thread.currentThread())
                if (id == "preview") {
                    assertNull(context.item)
                    assertEquals("a", context.args["preview-id"])
                    assertEquals("a", context.state["selected"])
                    val material =
                        if (context.state["selected"] == "a") org.bukkit.Material.STONE
                        else org.bukkit.Material.GOLD_INGOT
                    CompletableFuture.completedFuture(org.bukkit.inventory.ItemStack(material))
                } else {
                    assertNotNull(context.item)
                    CompletableFuture<org.bukkit.inventory.ItemStack>().also {
                        requests += context to it
                    }
                }
            },
        )
        engine.install(itemListCandidate())
        val opened = engine.open(player, "test:menu", mapOf("preview-id" to "a"))
        assertEquals(listOf("a", "b"), requests.map { it.first.item!!["id"] })
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (requests.first().first.item as MutableMap<String, Any?>)["id"] = "injected"
        }
        val first = Mockito.spy(org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER))
        Mockito.doAnswer {
                assertSame(
                    mainThread,
                    Thread.currentThread(),
                    "ItemStack cloning must stay on the main thread",
                )
                it.callRealMethod()
            }
            .`when`(first)
            .clone()
        background {
            requests[1].second.complete(org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND))
        }
        drainMain()
        assertFalse(opened.isDone)
        assertTrue(wire.shown.isEmpty())
        background { requests[0].second.complete(first) }
        assertFalse(opened.isDone)
        drainMain()
        assertTrue(opened.join().opened)
        assertEquals(listOf(org.bukkit.Material.PAPER, org.bukkit.Material.DIAMOND), rowItemTypes())
        assertNotSame(first, wire.nativeItems.getValue(wire.visible!!.items.first().node.id))
        Mockito.verify(first).clone()
        assertEquals(org.bukkit.Material.STONE, wire.nativeItems.getValue("preview").type)
        clickButton("next")
        assertEquals(listOf("a", "b", "c"), requests.map { it.first.item!!["id"] })
        requests[2].second.complete(org.bukkit.inventory.ItemStack(org.bukkit.Material.EMERALD))
        assertEquals(
            listOf(org.bukkit.Material.EMERALD),
            rowItemTypes(),
            "An empty slot must not read an item",
        )
        clickButton("filter")
        assertEquals(listOf("a", "c"), requests.takeLast(2).map { it.first.item!!["id"] })
        requests[4].second.complete(org.bukkit.inventory.ItemStack(org.bukkit.Material.EMERALD))
        requests[3].second.complete(org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER))
        assertEquals(listOf(org.bukkit.Material.PAPER, org.bukkit.Material.EMERALD), rowItemTypes())
        assertEquals("a", requests[0].first.item!!["id"], "Earlier contexts must remain frozen")
    }

    @Test
    fun lateItemsCannotEnterAReplacementSessionOrASupersedingFrame() {
        engine.registerData(
            plugin,
            "test:attachments",
            ProviderDescriptor("attachments"),
            DataProvider { _, _ ->
                CompletableFuture.completedFuture(listOf(mapOf("id" to "a", "shown" to true)))
            },
        )
        val requests =
            mutableListOf<Pair<UiContext, CompletableFuture<org.bukkit.inventory.ItemStack>>>()
        engine.registerItems(
            plugin,
            "test:items",
            ProviderDescriptor("items"),
            ItemProvider { context, _ ->
                CompletableFuture<org.bukkit.inventory.ItemStack>().also {
                    requests += context to it
                }
            },
        )
        val list = itemListCandidate()
        val replacement = candidate(other()).snapshot.menus.getValue("test:other")
        engine.install(
            list.copy(
                snapshot =
                    list.snapshot.copy(menus = list.snapshot.menus + ("test:other" to replacement))
            )
        )
        val opening = engine.open(player, "test:menu")
        val sessionId = requests.first().first.sessionId
        val queued = engine.refresh(player, sessionId)
        requests.toList().forEach {
            it.second.complete(org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND))
        }
        nextTick()
        assertEquals(UiFailureCode.SUPERSEDED, opening.join().failure)
        assertTrue(wire.shown.isEmpty())
        assertFalse(queued.isDone)
        val newer = engine.open(player, "test:other").join()
        assertTrue(newer.opened)
        assertEquals(UiFailureCode.SUPERSEDED, failure(queued))
        val count = wire.shown.size
        requests.drop(2).forEach {
            it.second.complete(org.bukkit.inventory.ItemStack(org.bukkit.Material.GOLD_INGOT))
        }
        assertEquals(count, wire.shown.size)
        assertEquals("replacement", wire.plain())
        assertTrue(wire.nativeItems.isEmpty())
    }

    @Test
    fun actionPatchesAreFrozenOnReceiptVisibleToTheNextActionAndShareTheAutomaticFrame() {
        val pending = CompletableFuture<UiActionResult>()
        var nextContext: UiContext? = null
        engine.registerAction(
            plugin,
            "test:result",
            ProviderDescriptor("result"),
            ActionProvider { _, _ -> pending },
        )
        engine.registerAction(
            plugin,
            "test:after",
            ProviderDescriptor("after"),
            ActionProvider { context, _ ->
                assertSame(mainThread, Thread.currentThread())
                nextContext = context
                CompletableFuture.completedFuture(UiActionResult.empty())
            },
        )
        engine.install(candidate(actionDocument("test:result", "test:after")))
        val sessionId = assertNotNull(engine.open(player, "test:menu").join().sessionId)
        clickButton("run")
        lateinit var refreshed: CompletableFuture<Void>
        background { refreshed = engine.refresh(player, sessionId) }
        assertFalse(refreshed.isDone)
        drainMain()
        assertFalse(refreshed.isDone)
        val list = mutableListOf<Any?>("original")
        val patch =
            mutableMapOf<String, Any?>("status" to "declined", "result" to mapOf("items" to list))
        background {
            pending.complete(UiActionResult(patch))
            patch["status"] = "mutated"
            list[0] = "mutated"
        }
        assertEquals("before", wire.plain())
        assertNull(nextContext)
        drainMain()
        assertEquals(
            "declined",
            wire.plain(),
            "A normal business refusal is a UI result, not an exception",
        )
        assertEquals(
            mapOf("items" to listOf("original")),
            assertNotNull(nextContext).state["result"],
        )
        assertEquals("declined", assertNotNull(nextContext).state["status"])
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (assertNotNull(nextContext).state as MutableMap<String, Any?>)["status"] = "injected"
        }
        assertTrue(refreshed.isDone)
        assertFalse(refreshed.isCompletedExceptionally)
        assertEquals(
            2,
            wire.shown.size,
            "The action and queued refresh must share one post-action frame",
        )
    }

    @Test
    fun invalidActionPatchesLeaveTheWholeStateUnchangedAndStopTheSequence() {
        var patch: Map<String, Any?> = emptyMap()
        var after = 0
        engine.registerAction(
            plugin,
            "test:result",
            ProviderDescriptor("result"),
            ActionProvider { _, _ -> CompletableFuture.completedFuture(UiActionResult(patch)) },
        )
        engine.registerAction(
            plugin,
            "test:after",
            ProviderDescriptor("after"),
            ActionProvider { _, _ ->
                after++
                CompletableFuture.completedFuture(UiActionResult())
            },
        )
        engine.install(candidate(actionDocument("test:result", "test:after")))
        engine.open(player, "test:menu").join()
        var tooDeep: Any? = "leaf"
        repeat(33) { tooDeep = listOf(tooDeep) }
        for (invalid in
            listOf(
                mapOf("undeclared" to true),
                mapOf("status" to true),
                mapOf("result" to Any()),
                mapOf("result" to org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE)),
                mapOf("result" to tooDeep),
            )) {
            patch = linkedMapOf<String, Any?>("status" to "partial") + invalid
            clickButton("run")
            assertEquals("before", wire.plain())
            assertEquals(0, after)
        }
        patch = emptyMap()
        val before = wire.shown.size
        clickButton("run")
        assertEquals(1, after)
        assertEquals(before + 1, wire.shown.size)
        assertEquals("before", wire.plain())
    }

    @Test
    fun lateActionPatchesCannotWriteAfterTimeoutCloseReplacementOrUnregistration() {
        for (reason in listOf("timeout", "completion-timeout", "close", "replace", "unregister")) {
            val pending = CompletableFuture<UiActionResult>()
            var after = 0
            val action = "test:result-$reason"
            val tail = "test:after-$reason"
            val registration =
                engine.registerAction(
                    plugin,
                    action,
                    ProviderDescriptor(reason),
                    ActionProvider { _, _ -> pending },
                )
            val trailing =
                engine.registerAction(
                    plugin,
                    tail,
                    ProviderDescriptor(reason),
                    ActionProvider { _, _ ->
                        after++
                        CompletableFuture.completedFuture(UiActionResult())
                    },
                )
            engine.install(
                candidate(actionDocument(action, tail))
                    .copy(
                        runtime =
                            com.hiusers.rafter.runtime.RuntimePolicy(actionTimeoutMillis = 1_000)
                    )
            )
            val sessionId = assertNotNull(engine.open(player, "test:menu").join().sessionId)
            clickButton("run")
            val queued = engine.refresh(player, sessionId)
            var replacement: UUID? = null
            when (reason) {
                "timeout" -> {
                    now += 1_000_000_001L
                    engine.tick()
                }
                "completion-timeout" -> now += 1_000_000_001L
                "close" -> engine.close(player, sessionId).join()
                "replace" -> replacement = engine.open(player, "test:menu").join().sessionId
                "unregister" -> registration.close()
            }
            val expected =
                when (reason) {
                    "timeout",
                    "completion-timeout" -> UiFailureCode.TIMED_OUT
                    "unregister" -> UiFailureCode.PROVIDER_UNAVAILABLE
                    else -> UiFailureCode.SUPERSEDED
                }
            if (reason == "completion-timeout") assertFalse(queued.isDone)
            else assertEquals(expected, failure(queued), reason)
            val shown = wire.shown.size
            background { pending.complete(UiActionResult(mapOf("status" to "late"))) }
            drainMain()
            assertEquals(expected, failure(queued), reason)
            assertEquals(shown, wire.shown.size, reason)
            assertEquals(0, after, reason)
            if (replacement != null) {
                assertEquals("before", wire.plain())
                engine.refresh(player, replacement).join()
                assertEquals("before", wire.plain())
                engine.close(player, replacement).join()
            } else assertNull(wire.visible, reason)
            registration.close()
            trailing.close()
        }
    }

    @Test
    fun expectedSessionIdsFenceCrossThreadRefreshAndCloseIncludingNoSession() {
        engine.install(candidate(minimal(), other()))
        val a = assertNotNull(engine.open(player, "test:menu").join().sessionId)
        engine.refresh(player, a).join()
        assertEquals(2, wire.shown.size)
        val b = assertNotNull(engine.open(player, "test:other").join().sessionId)
        assertNotEquals(a, b)
        val shown = wire.shown.size
        val clears = wire.clears
        lateinit var staleRefresh: CompletableFuture<Void>
        lateinit var staleClose: CompletableFuture<Void>
        background {
            staleRefresh = engine.refresh(player, a)
            staleClose = engine.close(player, a)
        }
        assertFalse(staleRefresh.isDone)
        assertFalse(staleClose.isDone)
        drainMain()
        assertEquals(UiFailureCode.SUPERSEDED, failure(staleRefresh))
        assertEquals(UiFailureCode.SUPERSEDED, failure(staleClose))
        assertEquals(shown, wire.shown.size)
        assertEquals(clears, wire.clears)
        assertEquals("replacement", wire.plain())
        lateinit var matching: CompletableFuture<Void>
        background { matching = engine.refresh(player, b) }
        assertFalse(matching.isDone)
        drainMain()
        matching.join()
        assertEquals(shown + 1, wire.shown.size)
        background { matching = engine.close(player, b) }
        assertNotNull(wire.visible)
        drainMain()
        matching.join()
        assertNull(wire.visible)
        assertEquals(clears + 1, wire.clears)
        engine.close(player, b).join()
        assertEquals(UiFailureCode.SUPERSEDED, failure(engine.refresh(player, b)))
        assertEquals(clears + 1, wire.clears)
    }

    @Test
    fun publicServiceRequiresSessionIdsAndAnExplicitActionResult() {
        for (name in listOf("refresh", "close")) {
            val methods = RafterService::class.java.methods.filter { it.name == name }
            assertEquals(1, methods.size)
            assertEquals(
                listOf(Player::class.java, UUID::class.java),
                methods.single().parameterTypes.toList(),
            )
        }
        val execute =
            ActionProvider::class.java.getMethod("execute", UiContext::class.java, Map::class.java)
        val result = execute.genericReturnType as java.lang.reflect.ParameterizedType
        assertEquals(CompletableFuture::class.java, result.rawType)
        assertEquals(listOf(UiActionResult::class.java), result.actualTypeArguments.toList())
        assertTrue(UiActionResult().statePatch.isEmpty())
        assertTrue(
            (UiActionResult::class.java.getMethod("empty").invoke(null) as UiActionResult)
                .statePatch
                .isEmpty()
        )
    }

    @Test
    fun dataProvidersStillCannotReturnItemStacks() {
        engine.registerData(
            plugin,
            "test:slow",
            ProviderDescriptor("ordinary values only"),
            DataProvider { _, _ ->
                CompletableFuture.completedFuture(
                    org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE)
                )
            },
        )
        engine.install(candidate(slow()))
        assertFalse(engine.open(player, "test:menu").join().opened)
        assertTrue(wire.shown.isEmpty())
        assertNull(wire.visible)
    }

    private fun modalCandidate(
        originProvider: String? = null,
        formProvider: String? = null,
        canvasEntry: Boolean = true,
    ): Candidate {
        val ui = temp.resolve("core/ui")
        val panel = ui.resolve("images/form-panel.png")
        Files.createDirectories(panel.parent)
        javax.imageio.ImageIO.write(
            java.awt.image.BufferedImage(260, 220, java.awt.image.BufferedImage.TYPE_INT_ARGB),
            "png",
            panel.toFile(),
        )
        val document = AutoAssets.document(ui)
        val base = AssetCompiler.compile(ui, document)
        fun text(id: String, value: String, provider: String? = null) =
            mapOf<String, Any?>(
                "id" to id,
                "type" to "text",
                "width" to 240,
                "height" to 18,
            ) +
                if (provider == null) mapOf("text" to value)
                else mapOf("binding" to mapOf("type" to "data", "key" to provider))
        fun button(id: String, vararg actions: Map<String, Any?>) =
            mapOf(
                "id" to id,
                "type" to "button",
                "text" to id,
                "width" to 98,
                "actions" to actions.toList(),
            )
        fun page(target: String) = mapOf("type" to "open-page", "page" to target)
        val exit = button("cancel", mapOf("type" to "back")) + ("role" to "exit")
        val feedback = text("feedback", "", formProvider) + ("y" to 3)
        fun modal(components: List<Map<String, Any?>>, feedback: Map<String, Any?>) =
            mapOf(
                "mode" to "canvas",
                "title" to "",
                "width" to 240,
                "columns" to 2,
                "modal" to
                    mapOf(
                        "asset" to "rafter:form-panel",
                        "background" to "#101820",
                        "feedback" to "feedback",
                    ),
                "elements" to listOf(feedback),
                "components" to components,
            )
        val origin =
            if (canvasEntry)
                mapOf(
                    "mode" to "canvas",
                    "title" to "",
                    "width" to 240,
                    "height" to 90,
                    "elements" to
                        listOf(
                            text("background", "Workspace", originProvider),
                            mapOf(
                                "id" to "edit",
                                "type" to "hit-area",
                                "width" to 90,
                                "height" to 18,
                                "y" to 36,
                                "actions" to listOf(page("form")),
                            ),
                        ),
                )
            else
                mapOf(
                    "mode" to "native",
                    "components" to listOf(text("background", "Workspace") - "height"),
                )
        val form =
            modal(
                listOf(
                    mapOf(
                        "id" to "name",
                        "type" to "input",
                        "state" to "name",
                        "width" to 220,
                        "max-length" to 16,
                        "label-visible" to false,
                    ),
                    mapOf(
                        "id" to "message",
                        "type" to "input",
                        "state" to "message",
                        "width" to 220,
                        "max-length" to 200,
                        "multiline" to true,
                        "multiline-height" to 48,
                        "max-lines" to 6,
                        "label-visible" to false,
                    ),
                    exit,
                    button(
                        "review",
                        mapOf("type" to "submit"),
                        mapOf("type" to "provider", "provider" to "test:save"),
                        page("confirm"),
                    ),
                ),
                feedback,
            )
        val confirm =
            modal(
                listOf(
                    button("edit", page("form")),
                    button(
                        "finish",
                        mapOf("type" to "provider", "provider" to "test:finish"),
                        mapOf("type" to "close"),
                    ),
                ),
                text("feedback", "") + ("y" to 3),
            )
        val source =
            mapOf(
                "id" to "test:form",
                "entry" to "home",
                "state" to
                    mapOf(
                        "name" to "",
                        "message" to "",
                        "status" to "",
                        "payload" to emptyMap<String, Any?>(),
                    ),
                "pages" to
                    mapOf(
                        "home" to origin,
                        "form" to form,
                        "confirm" to confirm,
                    ),
            )
        val result =
            MenuCompiler(base.dimensions(), imageSources = base.sourceDimensions())
                .compile(mapOf("form.yml" to source, "other.yml" to other()))
        val snapshot = assertNotNull(result.snapshot, result.diagnostics.toString())
        assets =
            AssetCompiler.compile(
                ui,
                document,
                previous = base.allocation,
                modal = ModalAssets.collect(snapshot, base.sourceDimensions()),
            )
        return Candidate(snapshot, assets, "test:form", 600_000)
    }

    @Test
    fun openOptionsExposeBothOpenSignaturesAndJavaConstructors() {
        val signatures =
            RafterService::class.java.methods.filter { it.name == "open" }
                .map { it.parameterTypes.toList() }.toSet()
        val original = listOf(Player::class.java, String::class.java, Map::class.java)
        assertEquals(setOf(original, original + UiOpenOptions::class.java), signatures)
        val defaults = UiOpenOptions::class.java.getConstructor().newInstance()
        assertTrue(defaults.initialState.isEmpty())
        assertNull(defaults.initialPage)
        val stateOnly =
            UiOpenOptions::class.java.getConstructor(Map::class.java)
                .newInstance(mapOf("name" to "Prefilled"))
        assertEquals(mapOf("name" to "Prefilled"), stateOnly.initialState)
        assertNull(stateOnly.initialPage)
        val pageOnly = UiOpenOptions::class.java.getConstructor(String::class.java).newInstance("form")
        assertTrue(pageOnly.initialState.isEmpty())
        assertEquals("form", pageOnly.initialPage)
        assertTrue(
            UiOpenOptions::class.java.constructors.all { constructor ->
                constructor.parameterTypes.none { it.name.contains("DefaultConstructorMarker") }
            }
        )
    }

    @Test
    fun confirmationExecutesOnceAndClosesWithoutAnExtraFrameOrRefreshWaitCycle() {
        val finishing = CompletableFuture<UiActionResult>()
        var calls = 0
        engine.registerAction(
            plugin,
            "test:save",
            ProviderDescriptor("save"),
            ActionProvider { _, _ -> CompletableFuture.completedFuture(UiActionResult()) },
        )
        engine.registerAction(
            plugin,
            "test:finish",
            ProviderDescriptor("finish"),
            ActionProvider { context, _ ->
                assertEquals("Draft", context.state["name"])
                assertEquals("Notes", context.state["message"])
                calls++
                finishing
            },
        )
        engine.install(modalCandidate())
        val opened =
            engine.open(
                player,
                "test:form",
                emptyMap(),
                UiOpenOptions(mapOf("name" to "Draft", "message" to "Notes"), "form"),
            ).join()
        val review = wire.visible!!.buttons.single { it.node.id == "review" }.handle!!
        engine.click(player, review, mapOf("name" to "Draft", "message" to "Notes"))
        val confirm = wire.visible!!.buttons.single { it.node.id == "finish" }.handle!!
        engine.click(player, confirm, emptyMap())
        engine.click(player, confirm, emptyMap())
        val refresh = engine.refresh(player, assertNotNull(opened.sessionId))
        assertFalse(refresh.isDone)
        finishing.complete(UiActionResult())
        assertEquals(1, calls)
        assertNull(wire.visible)
        assertEquals(listOf(false, true, true), wire.shown.map { it.modal })
        assertEquals(UiFailureCode.SUPERSEDED, failure(refresh))
    }

    @Test
    fun initialStateAndArgsAreFrozenBeforeMainThreadDispatchAndFirstProviderRead() {
        lateinit var observed: UiContext
        engine.registerData(
            plugin,
            "test:slow",
            ProviderDescriptor("initial values"),
            DataProvider { context, _ ->
                observed = context
                CompletableFuture.completedFuture(context.state["status"])
            },
        )
        engine.install(
            candidate(
                slow() +
                    mapOf(
                        "state" to
                            mapOf(
                                "status" to "",
                                "payload" to emptyMap<String, Any?>(),
                            )
                    )
            )
        )
        val names = mutableListOf("original")
        val nested = linkedMapOf<String, Any?>("names" to names)
        val patch = linkedMapOf<String, Any?>("status" to "draft", "payload" to nested)
        val args = linkedMapOf<String, Any?>("source" to names)
        lateinit var opening: CompletableFuture<OpenResult>
        background {
            opening = engine.open(player, "test:menu", args, UiOpenOptions(patch))
        }
        assertFalse(opening.isDone)
        names += "changed"
        nested["extra"] = true
        patch["status"] = "changed"
        args.clear()
        drainMain()
        assertTrue(opening.join().opened)
        assertEquals("draft", wire.plain())
        assertEquals(mapOf("names" to listOf("original")), observed.state["payload"])
        assertEquals(mapOf("source" to listOf("original")), observed.args)
        assertFailsWith<UnsupportedOperationException> {
            (observed.state as MutableMap)["status"] = "bad"
        }
        assertFailsWith<UnsupportedOperationException> {
            (observed.state["payload"] as MutableMap<String, Any?>)["bad"] = true
        }
    }

    @Test
    fun invalidOpenOptionsKeepTheOldSessionAndNeverReadProviders() {
        var reads = 0
        engine.registerData(
            plugin,
            "test:origin",
            ProviderDescriptor("origin"),
            DataProvider { _, _ ->
                reads++
                CompletableFuture.completedFuture("Workspace")
            },
        )
        engine.install(modalCandidate(originProvider = "test:origin"))
        val old = assertNotNull(engine.open(player, "test:other").join().sessionId)
        val shown = wire.shown.size
        val clears = wire.clears
        var deep: Any? = "leaf"
        repeat(34) { deep = listOf(deep) }
        for (
            invalid in
                listOf(
                    mapOf("undeclared" to true),
                    mapOf("name" to 1),
                    mapOf("name" to "x".repeat(17)),
                    mapOf("message" to "x".repeat(201)),
                    mapOf("payload" to deep),
                    mapOf("payload" to mapOf("future" to CompletableFuture<Any?>())),
                )
        ) {
            val result =
                engine
                    .open(
                        player,
                        "test:form",
                        emptyMap(),
                        UiOpenOptions(
                            linkedMapOf<String, Any?>("status" to "partial") + invalid,
                            "form",
                        ),
                    )
                    .join()
            assertEquals(
                UiFailureCode.INVALID_ARGUMENT,
                result.failure,
                invalid.keys.toString(),
            )
        }
        for (target in listOf("", "missing")) {
            assertEquals(
                UiFailureCode.INVALID_ARGUMENT,
                engine
                    .open(
                        player,
                        "test:form",
                        emptyMap(),
                        UiOpenOptions(initialPage = target),
                    )
                    .join()
                    .failure,
            )
        }
        assertEquals(
            UiFailureCode.INVALID_ARGUMENT,
            engine
                .open(player, "test:form", mapOf("bad" to Any()), UiOpenOptions())
                .join()
                .failure,
        )
        assertEquals(shown, wire.shown.size)
        assertEquals(clears, wire.clears)
        assertEquals(0, reads)
        engine.refresh(player, old).join()
        assertEquals("replacement", wire.plain())
    }

    @Test
    fun initialListStateUsesTheExistingPageSelectionAndBooleanConstraints() {
        val list = itemListCandidate()
        val other = candidate(other())
        engine.install(
            list.copy(
                snapshot = list.snapshot.copy(menus = list.snapshot.menus + other.snapshot.menus)
            )
        )
        val old = assertNotNull(engine.open(player, "test:other").join().sessionId)
        for (
            invalid in
                listOf(
                    mapOf("page" to -1),
                    mapOf("page" to 0.5),
                    mapOf("selected" to 1),
                    mapOf("filtered" to "yes"),
                )
        ) {
            assertEquals(
                UiFailureCode.INVALID_ARGUMENT,
                engine
                    .open(player, "test:menu", emptyMap(), UiOpenOptions(invalid))
                    .join()
                    .failure,
            )
        }
        engine.refresh(player, old).join()
        assertEquals("replacement", wire.plain())
    }

    @Test
    fun initialNonModalPageAndOrdinaryOpenKeepTheirOwnEntryBehavior() {
        val doc =
            minimal() +
                mapOf(
                    "pages" to
                        mapOf(
                            "main" to
                                mapOf(
                                    "mode" to "native",
                                    "components" to
                                        listOf(
                                            mapOf(
                                                "id" to "label",
                                                "type" to "text",
                                                "text" to "entry",
                                            )
                                        ),
                                ),
                            "target" to
                                mapOf(
                                    "mode" to "native",
                                    "components" to
                                        listOf(
                                            mapOf(
                                                "id" to "label",
                                                "type" to "text",
                                                "text" to "target",
                                            )
                                        ),
                                ),
                        )
                )
        engine.install(candidate(doc))
        assertTrue(
            engine
                .open(
                    player,
                    "test:menu",
                    emptyMap(),
                    UiOpenOptions(initialPage = "target"),
                )
                .join()
                .opened
        )
        assertEquals("target", wire.plain())
        assertEquals(1, wire.shown.size)
        assertTrue(engine.open(player, "test:menu").join().opened)
        assertEquals("entry", wire.plain())
    }

    @Test
    fun prefilledModalOpensOnlyAfterARealCanvasAndFinalTargetSend() {
        val origin = CompletableFuture<Any?>()
        val form = CompletableFuture<Any?>()
        val contexts = mutableListOf<UiContext>()
        engine.registerData(
            plugin,
            "test:origin",
            ProviderDescriptor("origin"),
            DataProvider { context, _ ->
                contexts += context
                origin
            },
        )
        engine.registerData(
            plugin,
            "test:form",
            ProviderDescriptor("form"),
            DataProvider { context, _ ->
                contexts += context
                form
            },
        )
        engine.install(modalCandidate("test:origin", "test:form"))
        val opening =
            engine.open(
                player,
                "test:form",
                emptyMap(),
                UiOpenOptions(
                    mapOf("name" to "Prefilled", "message" to "First line\nSecond line"),
                    "form",
                ),
            )
        assertEquals("Prefilled", contexts.single().state["name"])
        assertEquals("home", contexts.single().page)
        assertFalse(opening.isDone)
        origin.complete("Workspace")
        assertEquals(listOf(false), wire.shown.map { it.modal })
        assertEquals("form", contexts.last().page)
        assertEquals("First line\nSecond line", contexts.last().state["message"])
        assertFalse(opening.isDone, "Sending the backdrop does not complete open")
        form.complete("")
        val result = opening.join()
        assertTrue(result.opened)
        assertEquals(listOf(false, true), wire.shown.map { it.modal })
        assertTrue(contexts.all { it.sessionId == result.sessionId })
        assertEquals(
            listOf("Prefilled", "First line\nSecond line"),
            wire.visible!!.inputs.map { it.initial },
        )
        assertFalse((wire.visible!!.inputs[0].node.content as NodeContent.TextInput).multiline)
        assertTrue((wire.visible!!.inputs[1].node.content as NodeContent.TextInput).multiline)
        assertTrue(wire.visible!!.title.toRawMessage().contains("Workspace"))
    }

    @Test
    fun modalReplacementSubmitsExplicitlyReusesBackdropAndDoesNotStackHistoryOrFrames() {
        var originReads = 0
        val saved = mutableListOf<UiContext>()
        engine.registerData(
            plugin,
            "test:origin",
            ProviderDescriptor("origin"),
            DataProvider { _, _ ->
                originReads++
                CompletableFuture.completedFuture("Workspace")
            },
        )
        engine.registerAction(
            plugin,
            "test:save",
            ProviderDescriptor("save"),
            ActionProvider { context, _ ->
                saved += context
                CompletableFuture.completedFuture(UiActionResult(mapOf("status" to "ready")))
            },
        )
        engine.install(modalCandidate(originProvider = "test:origin"))
        val opened =
            engine
                .open(
                    player,
                    "test:form",
                    emptyMap(),
                    UiOpenOptions(mapOf("name" to "Initial"), "form"),
                )
                .join()
        val stale = wire.visible!!.buttons.single { it.node.id == "review" }.handle!!
        engine.click(player, stale, mapOf("name" to "Updated", "message" to "One\nTwo"))
        assertTrue(wire.visible!!.modal)
        assertTrue(wire.visible!!.inputs.isEmpty())
        assertEquals("Updated", saved.single().state["name"])
        assertEquals("One\nTwo", saved.single().state["message"])
        assertEquals(opened.sessionId, saved.single().sessionId)
        val shown = wire.shown.size
        engine.click(player, stale, emptyMap())
        assertEquals(shown, wire.shown.size)
        clickButton("edit")
        assertEquals(listOf("Updated", "One\nTwo"), wire.visible!!.inputs.map { it.initial })
        assertEquals(1, originReads, "Replacing a modal must not render its frozen origin again")
        val cancel = wire.visible!!.buttons.single { it.node.id == "cancel" }.handle!!
        engine.click(player, cancel, mapOf("name" to "Not submitted", "message" to "discarded"))
        assertFalse(wire.visible!!.modal)
        assertEquals(listOf(false, true, true, true, false), wire.shown.map { it.modal })
        assertEquals(2, originReads)
        val edit = canvasTokens().first()
        engine.click(player, edit, emptyMap())
        assertEquals(listOf("Updated", "One\nTwo"), wire.visible!!.inputs.map { it.initial })
        assertEquals(1, saved.size)
    }

    @Test
    fun modalInitialPageRejectsAnUnsuitableEntryWithoutClosingTheOldView() {
        engine.install(modalCandidate(canvasEntry = false))
        val old = assertNotNull(engine.open(player, "test:other").join().sessionId)
        val clears = wire.clears
        assertEquals(
            UiFailureCode.INVALID_ARGUMENT,
            engine
                .open(
                    player,
                    "test:form",
                    emptyMap(),
                    UiOpenOptions(initialPage = "form"),
                )
                .join()
                .failure,
        )
        assertEquals(clears, wire.clears)
        engine.refresh(player, old).join()
        assertEquals("replacement", wire.plain())
    }

    @Test
    fun interruptedModalInitializationNeverReopensOrClearsItsReplacement() {
        val candidate =
            modalCandidate("test:origin", "test:form").copy(
                runtime =
                    com.hiusers.rafter.runtime.RuntimePolicy(renderTimeoutMillis = 1_000)
            )
        for (stage in 0..1) {
            for (
                interruption in
                    listOf("close", "replace", "reload", "timeout", "provider")
            ) {
                now = 0
                val origin = CompletableFuture<Any?>()
                val form = CompletableFuture<Any?>()
                lateinit var sessionId: UUID
                val registration =
                    engine.registerData(
                        plugin,
                        "test:origin",
                        ProviderDescriptor("origin"),
                        DataProvider { context, _ ->
                            sessionId = context.sessionId
                            origin
                        },
                    )
                val formRegistration =
                    engine.registerData(
                        plugin,
                        "test:form",
                        ProviderDescriptor("form"),
                        DataProvider { _, _ -> form },
                    )
                engine.install(candidate)
                val opening =
                    engine.open(
                        player,
                        "test:form",
                        emptyMap(),
                        UiOpenOptions(initialPage = "form"),
                    )
                if (stage == 1) origin.complete("Workspace")
                assertFalse(opening.isDone)
                when (interruption) {
                    "close" -> engine.close(player, sessionId).join()
                    "replace" -> assertTrue(engine.open(player, "test:other").join().opened)
                    "reload" -> engine.install(candidate)
                    "timeout" -> {
                        now = 1_000_000_001L
                        engine.tick()
                    }
                    "provider" -> registration.close()
                }
                val expected =
                    when (interruption) {
                        "timeout" -> UiFailureCode.TIMED_OUT
                        "provider" -> UiFailureCode.PROVIDER_UNAVAILABLE
                        else -> UiFailureCode.SUPERSEDED
                    }
                assertEquals(expected, opening.join().failure, "$stage/$interruption")
                if (interruption != "replace")
                    assertTrue(engine.open(player, "test:other").join().opened)
                val shown = wire.shown.size
                val clears = wire.clears
                background {
                    origin.complete("late")
                    form.complete("")
                }
                drainMain()
                assertEquals(shown, wire.shown.size, "$stage/$interruption")
                assertEquals(clears, wire.clears, "$stage/$interruption")
                assertEquals("replacement", wire.plain())
                registration.close()
                formRegistration.close()
            }
        }
    }

    @Test
    fun twoStageModalInitializationSharesOneRenderDeadline() {
        val origin = CompletableFuture<Any?>()
        val form = CompletableFuture<Any?>()
        engine.registerData(
            plugin,
            "test:origin",
            ProviderDescriptor("origin"),
            DataProvider { _, _ -> origin },
        )
        engine.registerData(
            plugin,
            "test:form",
            ProviderDescriptor("form"),
            DataProvider { _, _ -> form },
        )
        engine.install(
            modalCandidate("test:origin", "test:form").copy(
                runtime =
                    com.hiusers.rafter.runtime.RuntimePolicy(renderTimeoutMillis = 1_000)
            )
        )
        val opening =
            engine.open(
                player,
                "test:form",
                emptyMap(),
                UiOpenOptions(initialPage = "form"),
            )
        now = 900_000_000L
        origin.complete("Workspace")
        assertFalse(opening.isDone)
        now = 1_100_000_000L
        form.complete("")
        assertEquals(UiFailureCode.TIMED_OUT, opening.join().failure)
        assertEquals(listOf(false), wire.shown.map { it.modal })
        assertNull(wire.visible)
    }

    @Test
    fun reentrantReplacementDuringBackdropSendDoesNotRemoveTheNewPendingFrame() {
        val pending = CompletableFuture<Any?>()
        engine.registerData(
            plugin,
            "test:slow",
            ProviderDescriptor("pending"),
            DataProvider { _, _ -> pending },
        )
        val base = modalCandidate()
        val replacement = candidate(slow())
        engine.install(
            base.copy(
                snapshot =
                    base.snapshot.copy(
                        menus = base.snapshot.menus + replacement.snapshot.menus
                    )
            )
        )
        lateinit var newer: CompletableFuture<OpenResult>
        wire.afterShow = {
            wire.afterShow = null
            newer = engine.open(player, "test:menu")
        }
        val old = engine.open(player, "test:form", emptyMap(), UiOpenOptions(initialPage = "form"))
        assertEquals(UiFailureCode.SUPERSEDED, old.join().failure)
        assertFalse(newer.isDone)
        pending.complete("new frame")
        assertTrue(newer.join().opened)
        assertEquals("new frame", wire.plain())
        assertEquals(listOf(false, false), wire.shown.map { it.modal })
    }
}
