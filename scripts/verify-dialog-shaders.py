#!/usr/bin/env python3
"""离线检查字体范围并编译 GUI Shader，不调用 GPU 或游戏。"""
import argparse
import ctypes as C
import json
import re
import resource
import signal
import sys
import zipfile
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('pack', type=Path)
p.add_argument('--client-jar', required=True, type=Path)
p.add_argument('--profile', default='rafter_v260200')
p.add_argument('--shaderc', required=True, type=Path)
a = p.parse_args()
signal.alarm(20)
resource.setrlimit(resource.RLIMIT_CPU, (15, 15))
failed = False
for font in sorted((a.pack / 'assets/rafter/font/modal_backdrop').rglob('*.json')):
    for provider in json.loads(font.read_text())['providers']:
        if provider['type'] == 'ttf':
            shift = provider.get('shift', [0, 0])
            if len(shift) != 2 or any(not -512 <= float(n) <= 512 for n in shift):
                print(f'FAIL font {font.relative_to(a.pack)}: TTF shift {shift} outside [-512, 512]')
                failed = True
lib = C.CDLL(str(a.shaderc))
P, I, U, S, B = C.c_void_p, C.c_int, C.c_uint, C.c_size_t, C.c_char_p
def api(name, result, *args):
    fn = getattr(lib, 'shaderc_' + name)
    fn.restype, fn.argtypes = result, list(args)
    return fn
init = api('compiler_initialize', P)
end = api('compiler_release', None, P)
opts = api('compile_options_initialize', P)
opts_end = api('compile_options_release', None, P)
target = api('compile_options_set_target_env', None, P, I, U)
locations = api('compile_options_set_auto_map_locations', None, P, C.c_bool)
bindings = api('compile_options_set_auto_bind_uniforms', None, P, C.c_bool)
optimize = api('compile_options_set_optimization_level', None, P, I)
compile_spv = api('compile_into_spv', P, P, B, S, I, B, B, P)
status = api('result_get_compilation_status', I, P)
message = api('result_get_error_message', B, P)
result_end = api('result_release', None, P)
compiler = init()
assert compiler, 'Shaderc initialization failed'
with zipfile.ZipFile(a.client_jar) as jar:
    for grayscale in (False, True):
        for stage, name in enumerate(('text.vsh', 'text.fsh')):
            visited = set()
            def expand(source):
                def include(match):
                    name = match.group(1)
                    ns, path = name.split(':', 1) if ':' in name else ('minecraft', name)
                    path = f'assets/{ns}/shaders/include/{path}'
                    if path in visited:
                        return ''
                    visited.add(path)
                    local = a.pack / path
                    data = local.read_text() if local.is_file() else jar.read(path).decode()
                    return expand(re.sub(r'^#version[^\n]*', '', data, flags=re.M))
                return re.sub(r'^\s*#(?:moj_import|include)\s+[<"]([^>"\n]+)[>"]', include, source, flags=re.M)
            file = a.pack / a.profile / 'assets/minecraft/shaders/core' / name
            source = expand(file.read_text())
            first, rest = source.split('\n', 1)
            defines = '#define IS_GUI\n' + ('#define IS_GRAYSCALE\n' if grayscale else '')
            source = (first + '\n' + defines + rest).encode()
            options = opts()
            target(options, 1, 450)  # 仅使用 OpenGL 编译前端，不创建窗口或 GPU 上下文。
            locations(options, True)
            bindings(options, True)
            optimize(options, 0)
            result = compile_spv(compiler, source, len(source), stage, name.encode(), b'main', options)
            assert result, 'Shaderc returned no result'
            ok = status(result) == 0
            print(f'{"PASS" if ok else "FAIL"} {a.profile}/{name} {"grayscale" if grayscale else "color"}')
            if not ok:
                print((message(result) or b'').decode())
                failed = True
            result_end(result)
            opts_end(options)
end(compiler)
sys.exit(1 if failed else 0)
