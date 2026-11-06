#!/usr/bin/env python3
"""使用指定客户端字体重放布局，不登录、启动界面或读取账号文件。"""
import argparse,json,os,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--client-json',required=True,type=Path);p.add_argument('--java',required=True,type=Path)
p.add_argument('--pack',required=True,type=Path);p.add_argument('--wire',type=Path);p.add_argument('--view',type=Path)
p.add_argument('--width',type=int);p.add_argument('--height',type=int);p.add_argument('--body-width',type=int)
p.add_argument('--viewport-height',type=int)
p.add_argument('--font-raster-probe',action='store_true');p.add_argument('--expect-crisp',action='store_true')
p.add_argument('--first-line',action='store_true');p.add_argument('--minimal-image',action='store_true');p.add_argument('--native-font-probe',action='store_true')
a=p.parse_args();a.pack=a.pack.resolve()
if a.wire:a.wire=a.wire.resolve()
if a.view:a.view=a.view.resolve()
data=json.loads(a.client_json.read_text());meta=a.client_json.parent.parent.parent;client=a.client_json.with_suffix('.jar');jars=[client]
for lib in data['libraries']:
    relative=lib.get('downloads',{}).get('artifact',{}).get('path')
    if relative and (meta/'libraries'/relative).exists():jars.append(meta/'libraries'/relative)
classpath=os.pathsep.join(map(str,jars));source=Path(__file__).parent/'client-probe';classes=Path(__file__).resolve().parent.parent/'project/bukkit/build/client-probe/classes';classes.mkdir(parents=True,exist_ok=True)
result=subprocess.run([str(a.java.with_name('javac')),'--class-path',classpath,'-d',str(classes),*[str(s) for s in source.glob('*.java')]])
if result.returncode:raise SystemExit(result.returncode)
classpath=str(classes)+os.pathsep+classpath;natives=meta/'natives'/a.client_json.stem
command=[str(a.java),'--enable-native-access=ALL-UNNAMED','-Djava.awt.headless=true','-Dorg.lwjgl.librarypath='+str(natives),'--class-path',classpath]
print('Client:',data['id'],'libraries:',len(jars)-1,flush=True)
if a.font_raster_probe:raise SystemExit(subprocess.run(command+['FontRasterProbe',str(a.pack)]+(['crisp'] if a.expect_crisp else []),cwd=classes.parent).returncode)
if a.native_font_probe:raise SystemExit(subprocess.run(command+['NativeFontProbe',str(a.pack)],cwd=classes.parent).returncode)
view=json.loads(a.view.read_text()) if a.view else {}
if view.get('asset-fingerprint') and view['asset-fingerprint']!=json.loads((a.pack/'manifest.json').read_text())['fingerprint']:p.error('Wire/view and resource-pack fingerprints differ; export a single successful candidate before replay')
width=a.width or view.get('canvas-width');height=a.height or view.get('canvas-height');body=a.body_width or view.get('body-width')
if not a.wire or not all((width,height,body)):p.error('Provide --wire and actual --view metadata, or explicit --width/--height/--body-width; drawing width is not body width')
mode='minimal-image' if a.minimal_image else 'first-line' if a.first_line else 'full'
raise SystemExit(subprocess.run(command+['ClientLayoutProbe',str(a.pack),str(a.wire),str(width),str(height),mode,str(body)]+([str(a.view)] if a.view else [])+([str(a.viewport_height)] if a.viewport_height else []),cwd=classes.parent).returncode)
