#!/usr/bin/env python3
"""通过小型 C++ 适配执行生成的 GLSL 判定源码；显式提供导数与采样，不测试驱动或 GPU。"""
import argparse,json,re,shutil,subprocess,tempfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--pack',required=True,type=Path);p.add_argument('--shader',type=Path)
a=p.parse_args();root=Path(__file__).resolve().parent
compiler=shutil.which('clang++') or shutil.which('g++')
if not compiler:p.error('An offline C++17 compiler (clang++ or g++) is required; no automatic installation')
source=(a.shader or a.pack/'assets/rafter/shaders/include/dialog_focus.glsl').read_text()
# 仅转换数组构造语法，不重新实现判定条件。
source,n=re.subn(r'(const\s+vec4\s+\w+\[\d+\]\s*=\s*)vec4\[\d+\]\((.*?)\);',r'\1{\2};',source,flags=re.S)
if n not in (1,2):p.error('Expected generated Canvas/Native focus-layout arrays')
if 'RAFTER_NATIVE_FOCUS_LAYOUT_COUNT' not in source:
    source+='\nconst int RAFTER_NATIVE_FOCUS_LAYOUT_COUNT=0;\nconst vec4 RAFTER_NATIVE_FOCUS_LAYOUTS[1]={vec4(0)};\n'
focus=json.loads((a.pack/'manifest.json').read_text())['dialog-focus-outline']
expected=sorted({(int(row['width']),int(row['height']) if row['kind']=='item' else 0,int(row['center-offset-twice']),1 if row['kind']=='item' else 0)
    for row in focus.get('native-layouts',[])}) if focus.get('native-enabled',False) else []
fixture='const int EXPECTED_NATIVE_LAYOUT_COUNT='+str(len(expected))+';\nconst vec4 EXPECTED_NATIVE_LAYOUTS['+str(max(1,len(expected)))+']={'+','.join('vec4('+','.join(str(v)+'.0' for v in row)+')' for row in expected or [(0,0,0,0)])+'};\n'
with tempfile.TemporaryDirectory(prefix='rafter-shader-source-') as tmp:
    directory=Path(tmp);program=directory/'predicate.cpp';binary=directory/'predicate'
    program.write_text((root/'shader-probe/vectors.hpp').read_text()+'\n'+source+'\n'+fixture+'\n'+(root/'shader-probe/cases.cpp').read_text())
    result=subprocess.run([compiler,'-std=c++17','-O1',str(program),'-o',str(binary)],timeout=30)
    if result.returncode:raise SystemExit(result.returncode)
    raise SystemExit(subprocess.run([str(binary)],timeout=15).returncode)
