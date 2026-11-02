#!/usr/bin/env python3
"""只读检查源码与产物，不启动游戏或修改作者文件。"""
from pathlib import Path
import argparse, json, re, zipfile

root = Path(__file__).resolve().parents[1]
sources = {name: root / f'project/{name}/src/main/kotlin/com/hiusers/rafter'
           for name in ('api', 'core', 'pack', 'bukkit')}
retired_prefix = r'(?:PlayerPreview|Equipment|Held|NativeEquipmentReader|Preview(?:Equipment|HeldItems|ItemProviders?|Payload|Outfit|Slot)|PackSources|AssetPng)'
retired_type = re.compile('^' + retired_prefix)
for path in (p for source in sources.values() for p in source.rglob('*.kt')):
    text = path.read_text()
    assert not retired_type.match(path.stem), f'Retired preview source: {path}'
    assert not re.search(r'rafter:demo|返回游戏|"fonts/rafter-mono\.ttf"|imageNames\s*=', text), f'Demo defaults must be authored resources: {path}'
    assert not re.search(r'com\.hiusers\.rafter\.(?:asset|render)\.' + retired_prefix, text), path
    assert not re.search(r'\bPlayerPreview\w*\b|player-preview|player_preview', text), path
    assert not re.search(r'import\s+net\.kyori', text) or '/transport/' in str(path), path
    assert not re.search(r'net\.kyori\.adventure\.text\.minimessage|MiniMessage\.miniMessage|\.toLegacyText\(', text), path
    assert not re.search(r'WrapperPlayServerCamera|TextDisplay|ItemDisplay|Interaction::|ScheduledExecutorService|PLAYER_ROTATION', text), path
    if '/model/' in str(path):
        assert not re.search(r'import\s+(org\.bukkit|com\.github\.retrooper)|CompletableFuture', text), path

# 人物绘制已退役，原生物品控件仍保留。
api = (sources['api'] / 'api/RafterService.kt').read_text()
renderer = (sources['core'] / 'render/Renderers.kt').read_text()
transport = (sources['bukkit'] / 'transport/DialogTransport.kt').read_text()
assert not re.search(r'session\.(?:handle|ready|consume|invalidate|beginRender|finishAction|navigate|back|close|setState|patchState)\s*\(', renderer), 'Renderer must not mutate session or issue capabilities'
assert re.search(r'fun\s+interface\s+ItemProvider\b', api) and 'registerItems' in api
assert re.search(r'is\s+NodeContent\.Item\s*->\s*bodies\s*\+=\s*ItemBody\(', renderer)
assert 'val bodies: List<RenderBody>' in renderer
assert 'view.bodies.map' in transport and re.search(r'is\s+ItemBody\s*->\s*ItemDialogBody\(', transport)

for module in ('core', 'pack', 'bukkit'):
    resources = root / f'project/{module}/src/main/resources'
    assert not (resources / 'shaders/player_preview').exists(), 'Retired person shader resources'
    assert not (resources / 'menus/player-preview.yml').exists(), 'Retired person example'
    for path in resources.rglob('*'):
        if path.is_file() and path.suffix in ('.vsh', '.fsh', '.glsl', '.yml', '.yaml'):
            assert not re.search(r'PlayerPreview|player-preview|player_preview|rafterAvatar|RAFTER_(?:ARMOR|EQUIPMENT|HELD)', path.read_text()), path

parser = argparse.ArgumentParser()
parser.add_argument('--jar', type=Path)
args = parser.parse_args()
if args.jar:
    with zipfile.ZipFile(args.jar) as jar:
        names = set(jar.namelist())
        for suffix in ('api/RafterService.class', 'api/ItemProvider.class', 'render/ItemBody.class',
                       'render/NativeRenderer.class', 'render/CanvasRenderer.class', 'transport/DialogTransport.class',
                       'asset/AutoAssets.class', 'api/UiFailureCode.class', 'api/UiOperationException.class', 'api/UiActionResult.class',
                       'runtime/SessionPhase.class', 'runtime/RuntimePolicy.class',
                       'asset/DialogFocusShaders.class', 'model/CanvasGeometry.class', 'model/NativeGeometry.class'):
            assert 'com/hiusers/rafter/' + suffix in names, suffix
        for name in names:
            if name.startswith('com/hiusers/rafter/') and name.endswith('.class'):
                assert not retired_type.match(Path(name).stem), f'Retired preview class: {name}'
        for name in ('core/menus/friends.yml', 'core/ui/fonts/rafter-mono.ttf',
                     'core/ui/fonts/rafter-mono.license',
                     'shaders/gui.vsh', 'shaders/gui.fsh', 'shaders/dialog_focus.glsl',
                     'defaults/bundle.json', 'core/ui/theme.yml'):
            assert name in names, name
        assert not any('PlayerPreview' in name or name.startswith('shaders/player_preview/')
                       or name == 'menus/player-preview.yml' for name in names), 'Retired person artifacts'
        for name in names:
            if name.endswith(('.yml', '.yaml')):
                assert b'player-preview' not in jar.read(name), name
        assert not any('/canvas/CanvasUi' in name or 'NativeInventoryUi' in name for name in names)
        assert not any('minimessage' in name.lower() for name in names)
        assert not {'menus/demo.yml', 'menus/controls.yml', 'menus/workbench.yml', 'templates/action-button.yml'} & names, 'Retired default interfaces'
        assert 'ui/assets.yml' not in names
        bundle = json.loads(jar.read('defaults/bundle.json'))
        assert set(bundle['files']) <= names, 'Missing resources listed by the default bundle'
        assert not any(name.startswith('com/hiusers/rafter/cli/') for name in names), 'CLI belongs to its independent distribution'
        descriptor = jar.read('plugin.yml').decode()
        assert '0.0.2' in descriptor and 'packetevents' in descriptor, descriptor
        dependencies = {}
        section = None
        for line in descriptor.splitlines():
            header = re.match(r'^([a-zA-Z_-]+):', line)
            if header:
                section = header.group(1)
            entry = re.match(r"^\s*-\s*['\"]?([A-Za-z_]+)['\"]?\s*$", line)
            if entry:
                dependencies.setdefault(section, set()).add(entry.group(1))
        assert 'Xerr' in dependencies.get('softdepend', set()), descriptor
        assert 'Xerr' not in dependencies.get('depend', set()), descriptor
        version = jar.read('META-INF/taboolib/version.properties').decode()
        env = jar.read('META-INF/taboolib/env.properties').decode()
        assert 'taboolib=6.3.0-0e3a911' in version, version
        assert 'minecraft-kether' in env and 'minecraft-chat' in env, env
    print('PASS: fixed JAR/resources, Native/Canvas renderers, native item widgets and runtime metadata')
print('PASS: pure models; TabooLib text; transport-only wire Components; no person/equipment/held/entity rendering path')
