#!/usr/bin/env python3
"""只读检查模块边界，不运行 Gradle 或子进程。"""

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
API = ":project:api"
CORE = ":project:core"
PACK = ":project:pack"
BUKKIT = ":project:bukkit"
AUTHORING = ":project:authoring"
CLI = ":tools:authoring-cli"
PLUGIN = ":plugin"
EXPECTED = {API, CORE, PACK, AUTHORING, BUKKIT, PLUGIN, CLI}
ALLOWED = {
    API: set(),
    CORE: {API},
    PACK: {CORE},
    AUTHORING: {CORE, PACK},
    CLI: {CORE, PACK, AUTHORING},
    BUKKIT: {API, CORE, PACK, AUTHORING},
    PLUGIN: {API, CORE, PACK, AUTHORING, BUKKIT},
}
BASE = "com.hiusers.rafter"
PORTABLE_API = {BASE + ".api." + name for name in ("ProviderDescriptor", "ParameterSpec", "ParameterType")}
PLATFORM = (
    "org.bukkit", "org.spigotmc", "io.papermc", "net.minecraft", "taboolib.platform",
)
PACKET_SCRIPT = (
    "com.github.retrooper.packetevents", "io.github.retrooper.packetevents",
    "taboolib.module.kether", "taboolib.library.kether",
)
FILE_IO = (
    "java.io.File", "java.io.FileInputStream", "java.io.FileOutputStream",
    "java.io.FileReader", "java.io.FileWriter", "java.io.RandomAccessFile",
    "java.io.FileDescriptor", "java.io.FileFilter", "java.io.FilenameFilter",
    "java.nio.channels.FileChannel", "java.nio.channels.AsynchronousFileChannel",
    "java.util.zip.ZipFile", "kotlin.io.path", "kotlin.io.FileTreeWalk",
)
TOKENS = re.compile(
    r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/'
)
QUALIFIED = re.compile(
    r"\b(?:com|io|org|net|java|javax|kotlin|taboolib)(?:\.[A-Za-z_]\w*)+(?:\.\*)?"
)
PACKAGE = re.compile(r"(?m)^\s*package\s+([\w.]+)")
DECLARATION = re.compile(
    r"(?m)^(?:(?:public|internal|private|protected|data|sealed|enum|annotation|value|"
    r"inline|open|abstract|final|expect|actual|suspend|const|lateinit|fun)\s+)*"
    r"(?:(?:class|interface|object|typealias|val|var)\s+|"
    r"fun\s+(?:<[^>\n]+>\s*)?(?:[\w?.<>]+\.)?)([A-Za-z_]\w*)"
)
CONFIGURATIONS = {
    "api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly",
    "annotationProcessor", "taboo",
}


def masked(text, keep_strings=False):
    def replace(match):
        token = match.group()
        if keep_strings and not token.startswith(("//", "/*")):
            return token
        return re.sub(r"[^\n]", " ", token)
    return TOKENS.sub(replace, text)


def within(name, prefix):
    return name == prefix or name.startswith(prefix + ".")


def module_dir(module):
    return ROOT.joinpath(*module.lstrip(":").split(":"))


def problem(errors, path, text, offset, message):
    line = text.count("\n", 0, offset) + 1
    errors.add(f"{path.relative_to(ROOT)}:{line}: {message}")


def read(path, errors):
    try:
        return path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeError) as error:
        problem(errors, path, "", 0, f"无法读取：{error}")
        return ""


def closing(code, start, left, right):
    depth = 1
    for position in range(start, len(code)):
        depth += (code[position] == left) - (code[position] == right)
        if depth == 0:
            return position
    raise ValueError(f"未闭合的 {left}{right} 块")


def project_name(value):
    if not re.fullmatch(r":?[\w-]+(?::[\w-]+)*", value):
        raise ValueError(f"不支持的项目路径 {value!r}；使用默认模块目录和字面量路径")
    return ":" + value.lstrip(":")


def included_projects(errors):
    path = ROOT / "settings.gradle.kts"
    text = read(path, errors)
    code, literals = masked(text), masked(text, keep_strings=True)
    modules = set()
    for call in re.finditer(r"\binclude\s*\(", code):
        try:
            end = closing(code, call.end(), "(", ")")
            body = literals[call.end():end]
            names = re.findall(r'"([^"\n]+)"', body)
            if not names or re.sub(r'"[^"\n]*"|[\s,]', "", body):
                raise ValueError("include 只支持字面量路径；请同步更新检查器")
            modules.update(project_name(name) for name in names)
        except ValueError as error:
            problem(errors, path, text, call.start(), str(error))
    if re.search(r"\bprojectDir\s*=", code):
        problem(errors, path, text, 0, "自定义 projectDir 尚未支持；请更新检查器的目录映射")
    for module in sorted(EXPECTED - modules):
        problem(errors, path, text, 0, f"缺少 include(\"{module}\")（预期目录 {module_dir(module).relative_to(ROOT)}）")
    return modules


def dependencies(path, modules, errors):
    text = read(path, errors)
    code, literals = masked(text), masked(text, keep_strings=True)
    edges = set()
    # ponytail: 仅解析当前字面 Gradle DSL；语法变化时同步更新检查器。
    for block in re.finditer(r"\bdependencies\s*\{", code):
        try:
            end = closing(code, block.end(), "{", "}")
        except ValueError as error:
            problem(errors, path, text, block.start(), str(error))
            continue
        for call in re.finditer(r"\bproject\s*\(", code[block.end():end]):
            start = block.end() + call.start()
            argument = block.end() + call.end()
            try:
                finish = closing(code, argument, "(", ")")
                parent = re.search(r"\b(\w+)\s*\(\s*$", code[block.end():start])
                config = parent.group(1) if parent else ""
                if config.lower().startswith("test"):
                    continue
                value = literals[argument:finish].strip()
                if path == ROOT / "plugin/build.gradle.kts" and config == "taboo" and value == "it.path":
                    continue  # 现有自动装配不属于业务反向依赖。
                if config not in CONFIGURATIONS:
                    raise ValueError("项目依赖需直接写成 implementation(project(\"路径\")) 等声明；间接 DSL 请先更新检查器")
                target = re.fullmatch(r'(?:path\s*=\s*)?"([^"\n]+)"', value)
                if not target:
                    raise ValueError("生产项目依赖必须使用字面量路径；动态依赖请先更新检查器")
                target = project_name(target.group(1))
                if target not in modules:
                    raise ValueError(f"项目依赖 {target} 未在 settings 中 include")
                edges.add(target)
            except ValueError as error:
                problem(errors, path, text, start, str(error))
    return edges


def forbidden(module, name):
    if within(name, "net.kyori.adventure.text.minimessage"):
        return "保留 TabooLib ComponentText / SimpleComponent，不引入 MiniMessage"
    if module in {API, CORE, PACK, AUTHORING, CLI} and any(within(name, p) for p in PACKET_SCRIPT):
        return "PacketEvents / Kether 只能留在 bukkit 实现"
    if module in {CORE, PACK, AUTHORING, CLI} and any(within(name, p) for p in PLATFORM):
        return "平台引用应移到 bukkit，通过值对象传入当前模块"
    if module == API and within(name, BASE) and not within(name, BASE + ".api"):
        return "api 不能引用内部 model / renderer / transport 或其他实现类型"
    if module != CORE:
        return ""
    if within(name, "taboolib.common.platform") or within(name, "taboolib.library.xseries"):
        return "core 不引用平台调度或物品工具；交由 bukkit"
    if within(name, BASE + ".api") and not any(within(name, p) for p in PORTABLE_API):
        return "core 只显式引用可移植参数描述；不导入 api.* 或带 Player 的 API"
    if within(name, "java.awt") and (not within(name, "java.awt.Color") or name.endswith(".*")):
        return "core 仅例外允许 java.awt.Color 颜色值；字体／图片／Graphics 留在 pack"
    if within(name, "javax.imageio"):
        return "ImageIO 留在 pack，不进入 core"
    if within(name, "java.nio.file") and not within(name, "java.nio.file.Path"):
        return "core 仅允许 Path 值对象，文件操作留在 pack / bukkit"
    if name in {"java.io.*", "java.nio.channels.*", "kotlin.io.*"} or any(within(name, p) for p in FILE_IO):
        return "文件 IO 留在 pack / bukkit，core 只接收数据和 Path 值"
    return ""


def check_sources(modules, graph, errors):
    sources, symbols, packages = [], {}, {}
    for module in sorted(modules):
        for language, suffix in (("kotlin", ".kt"), ("java", ".java")):
            directory = module_dir(module) / "src/main" / language
            for path in sorted(directory.rglob("*" + suffix)):
                text = read(path, errors)
                code = masked(text)
                package = PACKAGE.search(code)
                names = set()
                if package:
                    package = package.group(1)
                    packages.setdefault(package, set()).add(module)
                    names = {package + "." + m.group(1) for m in DECLARATION.finditer(code)}
                    for name in names:
                        symbols.setdefault(name, set()).add(module)
                sources.append((module, path, text, code))
    for module, path, text, code in sources:
        for reference in QUALIFIED.finditer(code):
            name = reference.group()
            reason = forbidden(module, name)
            owners = packages.get(name[:-2], set()) if name.endswith(".*") else set()
            candidate = name
            while not owners and "." in candidate:
                owners = symbols.get(candidate, set())
                candidate = candidate.rsplit(".", 1)[0]
            for owner in sorted(owners - {module}):
                if owner not in ALLOWED.get(module, modules):
                    reason = f"{module} 不允许引用 {owner}；共享包请显式导入允许的 DTO，平台实现留在 bukkit"
                elif owner not in graph.get(module, set()):
                    reason = f"引用 {owner} 需在本模块 dependencies 中显式声明生产项目依赖"
            if reason:
                problem(errors, path, text, reference.start(), f"{name}：{reason}")
        if module == CORE:
            for call in re.finditer(r"\.\s*(?:toFile|toRealPath)\s*\(", code):
                problem(errors, path, text, call.start(), "core 的 Path 仅作值对象，不转换为文件或访问真实文件路径")
    return len(sources)


def check_cycles(graph, errors):
    done, active = set(), []

    def visit(module):
        if module in active:
            cycle = active[active.index(module):] + [module]
            errors.add("settings.gradle.kts:1: 生产项目依赖有环：" + " -> ".join(cycle))
            return
        if module in done:
            return
        active.append(module)
        for target in sorted(graph.get(module, set())):
            visit(target)
        active.pop()
        done.add(module)

    for module in sorted(graph):
        visit(module)


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    errors = set()
    modules = included_projects(errors)
    graph = {}
    for module in sorted(modules | EXPECTED):
        path = module_dir(module) / "build.gradle.kts"
        if module in EXPECTED or path.is_file():
            graph[module] = dependencies(path, modules, errors)
            for target in sorted(graph[module] - ALLOWED.get(module, modules)):
                problem(errors, path, "", 0, f"禁止生产依赖 {module} -> {target}；按 api ← core ← pack / authoring ← bukkit；CLI 不进入插件装配 单向分层")
    root_build = ROOT / "build.gradle.kts"
    if dependencies(root_build, modules, errors):
        problem(errors, root_build, "", 0, "生产项目依赖必须在各模块显式声明，不能从根脚本注入")
    check_cycles(graph, errors)
    count = check_sources(modules | EXPECTED, graph, errors)
    if errors:
        print("FAIL: P1 模块边界（只读）")
        for error in sorted(errors):
            print(error)
        return 1
    print(f"PASS: 运行库／独立创作工具路径、生产依赖 DAG 与 {count} 个 main 源文件的静态引用边界")
    print("INFO: plugin 自动装配单列；未扫描测试／资源，未构建、测试、联网或启动服务。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
