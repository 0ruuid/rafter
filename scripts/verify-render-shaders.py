#!/usr/bin/env python3
"""用对应原版 include 离线编译生成 Shader，不创建窗口或 GPU 上下文。"""
import argparse
import ctypes as C
import json
import re
import resource
import signal
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('pack', type=Path)
parser.add_argument('--shaderc', type=Path, required=True)
parser.add_argument('--profile')
parser.add_argument('--baselines', type=Path, default=Path(__file__).resolve().parents[1] / 'project/pack/src/main/resources/shaders/client')
a = parser.parse_args()
signal.alarm(60)
resource.setrlimit(resource.RLIMIT_CPU, (45, 45))
lib = C.CDLL(str(a.shaderc))
P, I, U, S, B = C.c_void_p, C.c_int, C.c_uint, C.c_size_t, C.c_char_p


def api(name, result, *args):
    fn = getattr(lib, 'shaderc_' + name)
    fn.restype, fn.argtypes = result, list(args)
    return fn


init = api('compiler_initialize', P)
release = api('compiler_release', None, P)
options = api('compile_options_initialize', P)
release_options = api('compile_options_release', None, P)
target = api('compile_options_set_target_env', None, P, I, U)
locations = api('compile_options_set_auto_map_locations', None, P, C.c_bool)
bindings = api('compile_options_set_auto_bind_uniforms', None, P, C.c_bool)
compile_spv = api('compile_into_spv', P, P, B, S, I, B, B, P)
status = api('result_get_compilation_status', I, P)
message = api('result_get_error_message', B, P)
release_result = api('result_release', None, P)
compiler = init()
assert compiler, 'Shaderc initialization failed'
profiles = json.loads((a.pack / 'manifest.json').read_text())['client-profiles']
results = []
try:
    for profile in profiles:
        if a.profile and profile['directory'] != a.profile:
            continue
        folder = a.pack / profile['directory'] / 'assets/minecraft/shaders/core'
        files = sorted(folder.glob('*.vsh')) + sorted(folder.glob('*.fsh'))
        for file in files:
            variants = ('world', 'gui', 'grayscale') if file.stem == 'text' else ('ordinary',)
            for variant in variants:
                visited = set()

                def expand(source):
                    def include(match):
                        identifier = match.group(1)
                        ns, path = identifier.split(':', 1) if ':' in identifier else ('minecraft', identifier)
                        key = f'{ns}:{path}'
                        if key in visited:
                            return ''
                        visited.add(key)
                        local = a.pack / f'assets/{ns}/shaders/include/{path}'
                        original = a.baselines / profile['baseline'] / 'include' / path
                        source_file = local if local.is_file() else original
                        content = source_file.read_text()
                        return expand(re.sub(r'^#version[^\n]*', '', content, flags=re.M))
                    return re.sub(r'^\s*#(?:moj_import|include)\s+[<"]([^>"\n]+)[>"]', include, source, flags=re.M)

                original = file.read_text()
                assert original.startswith(f"#version {profile['glsl']}\n"), file
                source = expand(original)
                first, rest = source.split('\n', 1)
                normalized = profile['glsl'] < 330
                # OpenGL SPIR-V 最低要求 GLSL 330；150 原文件保留，前端检查单独标记。
                if normalized:
                    first = '#version 330'
                    assert 'layout(location' not in original, file
                defines = '#define IS_GUI\n' if variant in ('gui', 'grayscale') else ''
                if variant == 'grayscale':
                    defines += '#define IS_GRAYSCALE\n'
                source = (first + '\n' + defines + rest).encode()
                opts = options()
                target(opts, 1, 450)
                locations(opts, True)
                bindings(opts, True)
                result = compile_spv(compiler, source, len(source), 0 if file.suffix == '.vsh' else 1, file.name.encode(), b'main', opts)
                assert result, file
                ok = status(result) == 0
                result_type = 'normalized-330' if normalized else 'original-version'
                print(f"{'PASS' if ok else 'FAIL'} {profile['directory']}/{file.name} {variant} {result_type}")
                if not ok:
                    print((message(result) or b'').decode())
                results.append(ok)
                release_result(result)
                release_options(opts)
finally:
    release(compiler)
assert results, 'No generated shaders found'
print(json.dumps({'checks': len(results), 'passed': sum(results), 'gpu-or-client-validation': False}))
raise SystemExit(0 if all(results) else 1)
