#!/usr/bin/env python3
"""Compile Java/Kotlin API consumers and enforce the deployment JAR ABI."""

import argparse
from dataclasses import dataclass
import os
from pathlib import Path
import re
import subprocess
import tempfile
from typing import Iterable, Optional
import zipfile


API_PREFIX = "com/hiusers/rafter/api/"
ROOT = Path(__file__).resolve().parent.parent
ACC_PUBLIC = 0x0001
ACC_PROTECTED = 0x0004
ACC_SYNTHETIC = 0x1000
VISIBLE_ACCESS = ACC_PUBLIC | ACC_PROTECTED
KOTLIN_DESCRIPTOR = re.compile(r"Lkotlin(?:\d+)?/")


@dataclass(frozen=True)
class Member:
    access: int
    name: str
    descriptor: str
    signature: Optional[str]


@dataclass
class ClassFile:
    name: str
    access: int
    super_name: Optional[str]
    interfaces: tuple[str, ...]
    signature: Optional[str]
    fields: tuple[Member, ...]
    methods: tuple[Member, ...]
    constant_pool: list


class ClassReader:
    def __init__(self, data: bytes):
        self.data = data
        self.offset = 0

    def read(self, size: int) -> bytes:
        end = self.offset + size
        if end > len(self.data):
            raise ValueError("truncated class file")
        result = self.data[self.offset:end]
        self.offset = end
        return result

    def u1(self) -> int:
        return self.read(1)[0]

    def u2(self) -> int:
        return int.from_bytes(self.read(2), "big")

    def u4(self) -> int:
        return int.from_bytes(self.read(4), "big")


def cp_utf8(constant_pool: list, index: int) -> str:
    entry = constant_pool[index]
    if entry is None or entry[0] != "Utf8":
        raise ValueError(f"constant pool entry {index} is not Utf8")
    return entry[1]


def cp_class_name(constant_pool: list, index: int) -> str:
    entry = constant_pool[index]
    if entry is None or entry[0] != "Class":
        raise ValueError(f"constant pool entry {index} is not Class")
    return cp_utf8(constant_pool, entry[1])


def cp_name_and_type(constant_pool: list, index: int) -> tuple[str, str]:
    entry = constant_pool[index]
    if entry is None or entry[0] != "NameAndType":
        raise ValueError(f"constant pool entry {index} is not NameAndType")
    return cp_utf8(constant_pool, entry[1]), cp_utf8(constant_pool, entry[2])


def read_attributes(reader: ClassReader, constant_pool: list, count: int) -> Optional[str]:
    signature = None
    for _ in range(count):
        name = cp_utf8(constant_pool, reader.u2())
        length = reader.u4()
        payload = reader.read(length)
        if name == "Signature":
            if length != 2:
                raise ValueError("invalid Signature attribute length")
            signature = cp_utf8(constant_pool, int.from_bytes(payload, "big"))
    return signature


def read_members(reader: ClassReader, constant_pool: list, count: int) -> tuple[Member, ...]:
    members = []
    for _ in range(count):
        access = reader.u2()
        name = cp_utf8(constant_pool, reader.u2())
        descriptor = cp_utf8(constant_pool, reader.u2())
        signature = read_attributes(reader, constant_pool, reader.u2())
        members.append(Member(access, name, descriptor, signature))
    return tuple(members)


def parse_class(data: bytes) -> ClassFile:
    reader = ClassReader(data)
    if reader.u4() != 0xCAFEBABE:
        raise ValueError("not a JVM class file")
    reader.u2()
    reader.u2()
    constant_pool = [None] * reader.u2()
    index = 1
    while index < len(constant_pool):
        tag = reader.u1()
        if tag == 1:
            constant_pool[index] = ("Utf8", reader.read(reader.u2()).decode("utf-8", "replace"))
        elif tag == 3:
            reader.read(4)
            constant_pool[index] = ("Integer",)
        elif tag == 4:
            reader.read(4)
            constant_pool[index] = ("Float",)
        elif tag == 5:
            reader.read(8)
            constant_pool[index] = ("Long",)
            index += 1
        elif tag == 6:
            reader.read(8)
            constant_pool[index] = ("Double",)
            index += 1
        elif tag == 7:
            constant_pool[index] = ("Class", reader.u2())
        elif tag == 8:
            constant_pool[index] = ("String", reader.u2())
        elif tag in (9, 10, 11):
            constant_pool[index] = (
                {9: "Fieldref", 10: "Methodref", 11: "InterfaceMethodref"}[tag],
                reader.u2(),
                reader.u2(),
            )
        elif tag == 12:
            constant_pool[index] = ("NameAndType", reader.u2(), reader.u2())
        elif tag == 15:
            reader.u1()
            constant_pool[index] = ("MethodHandle", reader.u2())
        elif tag == 16:
            constant_pool[index] = ("MethodType", reader.u2())
        elif tag in (17, 18):
            constant_pool[index] = ("Dynamic" if tag == 17 else "InvokeDynamic", reader.u2(), reader.u2())
        elif tag == 19:
            constant_pool[index] = ("Module", reader.u2())
        elif tag == 20:
            constant_pool[index] = ("Package", reader.u2())
        else:
            raise ValueError(f"unsupported constant pool tag {tag}")
        index += 1

    access = reader.u2()
    name = cp_class_name(constant_pool, reader.u2())
    super_index = reader.u2()
    super_name = cp_class_name(constant_pool, super_index) if super_index else None
    interfaces = tuple(cp_class_name(constant_pool, reader.u2()) for _ in range(reader.u2()))
    fields = read_members(reader, constant_pool, reader.u2())
    methods = read_members(reader, constant_pool, reader.u2())
    signature = read_attributes(reader, constant_pool, reader.u2())
    return ClassFile(name, access, super_name, interfaces, signature, fields, methods, constant_pool)


def read_version() -> str:
    match = re.search(r"(?m)^version=([^\s#]+)\s*$", (ROOT / "gradle.properties").read_text())
    if not match:
        raise RuntimeError("gradle.properties does not declare version")
    return match.group(1)


def load_api_classes(jar: Path) -> dict[str, ClassFile]:
    with zipfile.ZipFile(jar) as archive:
        names = sorted(
            name for name in archive.namelist()
            if name.startswith(API_PREFIX) and name.endswith(".class")
        )
        if not names:
            raise ValueError(f"{jar} contains no classes under {API_PREFIX}")
        return {name[:-6]: parse_class(archive.read(name)) for name in names}


def kotlin_type_in_descriptor(descriptor: str) -> bool:
    return bool(KOTLIN_DESCRIPTOR.search(descriptor))


def is_kotlin_internal_name(name: Optional[str]) -> bool:
    return bool(name and re.match(r"^kotlin(?:\d+)?/", name))


def visible_non_synthetic(members: Iterable[Member]) -> set[tuple[str, str, int, Optional[str]]]:
    return {
        (member.name, member.descriptor, member.access & ~ACC_SYNTHETIC, member.signature)
        for member in members
        if member.access & VISIBLE_ACCESS and not member.access & ACC_SYNTHETIC
    }


def assert_no_kotlin_leaks(classes: dict[str, ClassFile], label: str) -> None:
    for class_name, class_file in classes.items():
        if class_file.access & VISIBLE_ACCESS and not class_file.access & ACC_SYNTHETIC:
            if class_file.signature and kotlin_type_in_descriptor(class_file.signature):
                raise ValueError(f"{label} {class_name} exposes Kotlin type in Signature {class_file.signature}")
        for relation in (class_file.super_name, *class_file.interfaces):
            if is_kotlin_internal_name(relation):
                raise ValueError(f"{label} {class_name} inherits from relocated Kotlin type {relation}")
        for kind, members in (("field", class_file.fields), ("method", class_file.methods)):
            for member in members:
                if member.access & VISIBLE_ACCESS and not member.access & ACC_SYNTHETIC:
                    if kotlin_type_in_descriptor(member.descriptor) or (
                        member.signature and kotlin_type_in_descriptor(member.signature)
                    ):
                        raise ValueError(
                            f"{label} {class_name} exposes Kotlin type in visible {kind} "
                            f"{member.name}{member.descriptor}"
                        )


def assert_abi_equal(api_classes: dict[str, ClassFile], plugin_classes: dict[str, ClassFile]) -> None:
    missing = sorted(set(api_classes) - set(plugin_classes))
    extra = sorted(set(plugin_classes) - set(api_classes))
    if missing or extra:
        details = []
        if missing:
            details.append("missing from deployment: " + ", ".join(missing))
        if extra:
            details.append("unexpected in deployment: " + ", ".join(extra))
        raise ValueError("API class set differs: " + "; ".join(details))

    for class_name in sorted(api_classes):
        expected = api_classes[class_name]
        actual = plugin_classes[class_name]
        expected_snapshot = (
            expected.access & ~ACC_SYNTHETIC,
            expected.super_name,
            expected.interfaces,
            expected.signature,
            visible_non_synthetic(expected.fields),
            visible_non_synthetic(expected.methods),
        )
        actual_snapshot = (
            actual.access & ~ACC_SYNTHETIC,
            actual.super_name,
            actual.interfaces,
            actual.signature,
            visible_non_synthetic(actual.fields),
            visible_non_synthetic(actual.methods),
        )
        if expected_snapshot != actual_snapshot:
            raise ValueError(f"public ABI differs for {class_name}")


def split_classpath(raw: str, label: str) -> list[Path]:
    paths = []
    for entry in raw.split(os.pathsep):
        if not entry or "*" in entry:
            raise ValueError(f"{label} entries must be explicit paths; no empty entries or wildcards")
        path = Path(entry).expanduser().resolve()
        if not path.is_file() and not path.is_dir():
            raise ValueError(f"{label} entry is not an existing file or directory: {path}")
        paths.append(path)
    if not paths:
        raise ValueError(f"{label} must not be empty")
    return paths


def dependency_classpath(raw: str) -> list[Path]:
    dependencies = []
    for path in split_classpath(raw, "dependency classpath"):
        if path.is_dir():
            contains_api = any((path / API_PREFIX).rglob("*.class"))
        elif zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                contains_api = any(
                    name.startswith(API_PREFIX) and name.endswith(".class")
                    for name in archive.namelist()
                )
        else:
            raise ValueError(f"dependency classpath entry is not a directory or JAR: {path}")
        if contains_api:
            raise ValueError(f"dependency classpath must not supply Rafter API classes: {path}")
        dependencies.append(path)
    return dependencies


def output_classes(output: Path) -> list[ClassFile]:
    class_files = [parse_class(path.read_bytes()) for path in sorted(output.rglob("*.class"))]
    if not class_files:
        raise ValueError(f"consumer compiler produced no class files: {output}")
    return class_files


class ClassRepository:
    def __init__(self, classes: dict[str, ClassFile], paths: Iterable[Path], java_home: Path):
        self.classes = classes
        self.paths = tuple(paths)
        self.java_base = java_home / "jmods" / "java.base.jmod"
        self.cache: dict[str, Optional[ClassFile]] = {}

    def load(self, name: str) -> Optional[ClassFile]:
        if name in self.classes:
            return self.classes[name]
        if name in self.cache:
            return self.cache[name]
        for path in (*self.paths, self.java_base):
            data = self._read(path, name)
            if data is None:
                continue
            class_file = parse_class(data)
            if class_file.name != name:
                raise ValueError(f"class path entry {path} contains {class_file.name}, expected {name}")
            self.cache[name] = class_file
            return class_file
        self.cache[name] = None
        return None

    @staticmethod
    def _read(path: Path, name: str) -> Optional[bytes]:
        if path.is_dir():
            class_path = path / f"{name}.class"
            return class_path.read_bytes() if class_path.is_file() else None
        if not path.is_file() or not zipfile.is_zipfile(path):
            return None
        entry = f"{name}.class"
        if path.suffix == ".jmod":
            entry = f"classes/{entry}"
        with zipfile.ZipFile(path) as archive:
            try:
                return archive.read(entry)
            except KeyError:
                return None


def resolve_member(
    repository: ClassRepository,
    owner: str,
    kind: str,
    name: str,
    descriptor: str,
) -> None:
    pending = [owner]
    visited = set()
    unresolved = set()
    while pending:
        class_name = pending.pop(0)
        if class_name in visited:
            continue
        visited.add(class_name)
        class_file = repository.load(class_name)
        if class_file is None:
            unresolved.add(class_name)
            continue
        members = class_file.fields if kind == "Fieldref" else class_file.methods
        if any(member.name == name and member.descriptor == descriptor for member in members):
            return
        if name == "<init>":
            break
        pending.extend(class_file.interfaces)
        if class_file.super_name:
            pending.append(class_file.super_name)

    reference = f"{kind} {owner}.{name}{descriptor}"
    if unresolved:
        paths = ", ".join(sorted(unresolved))
        raise ValueError(f"{reference} could not resolve API hierarchy types: {paths}")
    raise ValueError(f"{reference} is missing from the API hierarchy")


def constant_pool_references(class_file: ClassFile) -> Iterable[tuple[str, str, str, str]]:
    for entry in class_file.constant_pool:
        if entry is None or entry[0] not in {"Fieldref", "Methodref", "InterfaceMethodref"}:
            continue
        _, class_index, name_type_index = entry
        name, descriptor = cp_name_and_type(class_file.constant_pool, name_type_index)
        yield entry[0], cp_class_name(class_file.constant_pool, class_index), name, descriptor


def assert_consumer_links(output: Path, repository: ClassRepository, label: str) -> None:
    failures = set()
    for class_file in output_classes(output):
        for kind, owner, name, descriptor in constant_pool_references(class_file):
            if not owner.startswith(API_PREFIX):
                continue
            try:
                resolve_member(repository, owner, kind, name, descriptor)
            except ValueError as failure:
                failures.add(str(failure))
    if failures:
        raise ValueError(f"{label} has unresolved deployment API references: {'; '.join(sorted(failures))}")


def clean_java_environment() -> dict[str, str]:
    injected_options = {"JDK_JAVAC_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS"}
    return {key: value for key, value in os.environ.items() if key not in injected_options}


def run_compiler(command: list[str], timeout: int, env: dict[str, str]) -> None:
    subprocess.run(command, check=True, timeout=timeout, stdin=subprocess.DEVNULL, env=env)


def main() -> None:
    version = read_version()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", required=True, type=Path, help="JDK home containing javac and java")
    parser.add_argument("--api-jar", required=True, type=Path, help=f"Existing rafter-api-{version}-api.jar")
    parser.add_argument("--plugin-jar", required=True, type=Path, help="Existing final Rafter.jar")
    parser.add_argument(
        "--classpath", required=True,
        help=f"Explicit dependency paths separated by {os.pathsep!r}; exclude Rafter API/plugin/classes",
    )
    parser.add_argument(
        "--kotlin-compiler-classpath", required=True,
        help=f"Explicit resolved Kotlin compiler runtime paths separated by {os.pathsep!r}",
    )
    args = parser.parse_args()

    java_home = args.java_home.expanduser().resolve()
    javac = java_home / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    java = java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
    for executable in (javac, java):
        if not executable.is_file() or not os.access(executable, os.X_OK):
            parser.error(f"executable is missing or not executable: {executable}")

    artifacts = [args.api_jar.expanduser().resolve(), args.plugin_jar.expanduser().resolve()]
    for jar in artifacts:
        if not jar.is_file() or not zipfile.is_zipfile(jar):
            parser.error(f"not an existing JAR: {jar}")
    if artifacts[0].samefile(artifacts[1]):
        parser.error("--api-jar and --plugin-jar must be different artifacts")

    try:
        dependencies = dependency_classpath(args.classpath)
        compiler_paths = split_classpath(args.kotlin_compiler_classpath, "Kotlin compiler classpath")
    except ValueError as failure:
        parser.error(str(failure))
    java_sources = sorted((ROOT / "examples/api-consumer/src/main/java").rglob("*.java"))
    kotlin_sources = sorted((ROOT / "examples/api-consumer/src/main/kotlin").rglob("*.kt"))
    if not java_sources:
        parser.error("Java consumer fixture is missing")
    if not kotlin_sources:
        parser.error("Kotlin consumer fixture is missing")

    env = clean_java_environment()
    try:
        api_classes = load_api_classes(artifacts[0])
        plugin_classes = load_api_classes(artifacts[1])
        for jar, label, classes in (
            (artifacts[0], "API JAR", api_classes),
            (artifacts[1], "deployment JAR", plugin_classes),
        ):
            if "com/hiusers/rafter/api/UiOpenOptions" not in classes:
                raise ValueError(f"com/hiusers/rafter/api/UiOpenOptions.class is missing from {jar}")
            assert_no_kotlin_leaks(classes, label)
        assert_abi_equal(api_classes, plugin_classes)
        print("PASS: public non-synthetic API members and inheritance match", flush=True)
        print("PASS: public API signatures do not expose kotlin/ or kotlinN/ types", flush=True)

        consumer_classpath = os.pathsep.join([str(artifacts[0]), *(str(path) for path in dependencies)])
        deployment_classpath = os.pathsep.join([str(artifacts[1]), *(str(path) for path in dependencies)])
        compiler_classpath = os.pathsep.join(str(path) for path in compiler_paths)
        deployment_repository = ClassRepository(
            plugin_classes, [artifacts[1], *dependencies], java_home
        )
        with tempfile.TemporaryDirectory(prefix="rafter-api-consumer-") as temporary:
            temporary = Path(temporary)
            sourcepath = temporary / "empty-sourcepath"
            sourcepath.mkdir()
            for label, jar, classpath in (
                ("api", artifacts[0], consumer_classpath),
                ("plugin", artifacts[1], deployment_classpath),
            ):
                java_output = temporary / f"{label}-java"
                kotlin_output = temporary / f"{label}-kotlin"
                java_output.mkdir()
                kotlin_output.mkdir()
                javac_command = [
                    str(javac), "-J-Xms16m", "-J-Xmx128m", "-J-XX:ActiveProcessorCount=1", "-J-XX:+UseSerialGC",
                    "--release", "17", "-encoding", "UTF-8", "-proc:none", "-implicit:none",
                    "--source-path", str(sourcepath), "--class-path", classpath,
                    "-d", str(java_output), *(str(source) for source in java_sources),
                ]
                print(f"Compiling Java consumer against {jar}", flush=True)
                run_compiler(javac_command, 60, env)
                print(f"PASS: Java consumer compiled against {label}", flush=True)

                kotlin_command = [
                    str(java), "-Xms16m", "-Xmx256m", "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC",
                    "-cp", compiler_classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-jvm-target", "17",
                    "-classpath", classpath, "-d", str(kotlin_output),
                    *(str(source) for source in kotlin_sources),
                ]
                print(f"Compiling Kotlin consumer against {jar}", flush=True)
                run_compiler(kotlin_command, 60, env)
                print(f"PASS: Kotlin consumer compiled against {label}", flush=True)

                assert_consumer_links(java_output, deployment_repository, f"{label} Java consumer")
                assert_consumer_links(kotlin_output, deployment_repository, f"{label} Kotlin consumer")
                print(f"PASS: {label} consumer constant-pool API references exist in deployment JAR", flush=True)
    except (OSError, ValueError, subprocess.SubprocessError, zipfile.BadZipFile) as failure:
        parser.exit(1, f"FAIL: {failure}\n")
    print("PASS: API and deployment JARs passed the consumer ABI gate; no runtime/client verification performed")


if __name__ == "__main__":
    main()
