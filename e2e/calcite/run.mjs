import { execFile } from 'node:child_process';
import { constants as fsConstants } from 'node:fs';
import { access, appendFile, mkdir, realpath, rm, stat, writeFile } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { basename, dirname, isAbsolute, join, relative, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);
const DEFAULT_JAVA_PATH = '/Users/iiabc/.jenv/versions/25/bin/java';
const JAVA_PATH = process.env.RAFTER_JAVA_PATH || DEFAULT_JAVA_PATH;
const ENVIRONMENT_ROOT = '/Volumes/iiDisk/environment';
const DEFAULT_CALCITE_PACKAGE = '/Volumes/iiDisk/environment/test/spool-rafter-e2e/tools/node_modules/@bkm016/calcite';
const START_TIMEOUT_MS = 180_000;
const OP_TIMEOUT_MS = 30_000;
const STOP_TIMEOUT_MS = 30_000;

const clean = value =>
  String(value ?? '')
    .replace(/(Bearer\s+)[^\s]+/gi, '$1[REDACTED]')
    .replace(/[A-Za-z0-9_.-]{100,}/g, '[LONG VALUE OMITTED]');

const fail = (code, message) => Object.assign(new Error(message), { code });

const pathFromEnv = (name, fallback) => {
  const value = process.env[name] || fallback;
  if (!value || !isAbsolute(value)) {
    throw fail('configuration', `${name} must be an absolute directory`);
  }
  return value;
};

const resolveActualPath = async value => {
  let current = value;
  const suffix = [];
  while (true) {
    try {
      return join(await realpath(current), ...suffix.reverse());
    } catch (error) {
      if (error?.code !== 'ENOENT' || dirname(current) === current) throw error;
      suffix.push(basename(current));
      current = dirname(current);
    }
  }
};

const writePathFromEnv = async name => {
  const requested = pathFromEnv(name);
  try {
    const [environmentRoot, actual] = await Promise.all([
      realpath(ENVIRONMENT_ROOT),
      resolveActualPath(requested),
    ]);
    const relativePath = relative(environmentRoot, actual);
    if (relativePath === '..' || relativePath.startsWith(`..${sep}`) || isAbsolute(relativePath)) {
      throw fail('configuration', `${name} must stay under ${ENVIRONMENT_ROOT}`);
    }
    return actual;
  } catch (error) {
    if (error?.code === 'configuration') throw error;
    throw fail('configuration', `${name} cannot be resolved: ${error?.message || error}`);
  }
};

const displayJson = value =>
  JSON.stringify(value, (key, item) => {
    if (typeof item === 'string' && item.length > 250) return `${item.slice(0, 250)} [display truncated]`;
    return item;
  });

const redact = value => {
  if (typeof value === 'string') return clean(value);
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, redact(item)]));
  }
  return value;
};

const withTimeout = async (promise, label, timeoutMs) => {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(fail('timeout', `${label} timed out after ${timeoutMs}ms`)), timeoutMs);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
};

const runTool = async (file, args, options) => {
  try {
    return await execFileAsync(file, args, {
      ...options,
      maxBuffer: 2 * 1024 * 1024,
    });
  } catch (error) {
    const detail = clean(error.stderr || error.stdout || error.message);
    throw fail('probe_build', `${file} failed: ${detail}`);
  }
};

const ensureExtension = async ({ root, packageRoot, source }) => {
  const javaBin = dirname(JAVA_PATH);
  const javac = join(javaBin, 'javac');
  const jar = join(javaBin, 'jar');
  const apiJar = join(packageRoot, 'vendor', 'calcite-probe.jar');
  const buildRoot = join(root, 'build', 'rafterui');
  const classes = join(buildRoot, 'classes');
  const serviceDir = join(classes, 'META-INF', 'services');
  const serviceFile = join(serviceDir, 'calcite.probe.api.CalciteExtension');
  const extensionJar = join(buildRoot, 'rafter-dialog-probe.jar');

  for (const file of [JAVA_PATH, javac, jar, source, apiJar]) {
    try {
      await access(file, fsConstants.X_OK);
    } catch {
      try {
        await access(file, fsConstants.F_OK);
      } catch {
        throw fail('configuration', `Required local file is missing: ${file}`);
      }
    }
  }

  try {
    const [built, sourceInfo, apiInfo] = await Promise.all([stat(extensionJar), stat(source), stat(apiJar)]);
    if (built.mtimeMs >= sourceInfo.mtimeMs && built.mtimeMs >= apiInfo.mtimeMs) return extensionJar;
  } catch {
    // 未命中缓存时构建本地扩展。
  }

  await rm(classes, { recursive: true, force: true });
  await mkdir(serviceDir, { recursive: true });
  await runTool(
    javac,
    ['--release', '8', '-Xlint:-options', '-encoding', 'UTF-8', '-cp', apiJar, '-d', classes, source],
    {
      cwd: root,
      timeout: 30_000,
      env: { ...process.env, TMPDIR: root, TMP: root, TEMP: root },
    },
  );
  await writeFile(serviceFile, 'RafterDialogProbe\n', 'utf8');
  await runTool(jar, ['cf', extensionJar, '-C', classes, '.'], {
    cwd: root,
    timeout: 30_000,
    env: { ...process.env, TMPDIR: root, TMP: root, TEMP: root },
  });
  return extensionJar;
};

async function main() {
  const root = await writePathFromEnv('RAFTER_E2E_ROOT');
  const calciteHome = await writePathFromEnv('CALCITE_HOME');
  const packageRoot = pathFromEnv('RAFTER_CALCITE_PACKAGE', DEFAULT_CALCITE_PACKAGE);
  const evidence = join(root, 'evidence');
  const actionFile = join(evidence, 'rafter-item-probe-actions.jsonl');
  const source = join(dirname(fileURLToPath(import.meta.url)), 'RafterDialogProbe.java');
  const accountMode = process.env.RAFTER_CALCITE_ACCOUNT || 'microsoft';
  if (accountMode !== 'microsoft' && accountMode !== 'offline') {
    throw fail('configuration', 'RAFTER_CALCITE_ACCOUNT must be microsoft or offline');
  }

  await mkdir(evidence, { recursive: true });
  process.env.CALCITE_HOME = calciteHome;
  process.env.TMPDIR = root;
  process.env.TMP = root;
  process.env.TEMP = root;

  const calcite = await import(pathToFileURL(join(packageRoot, 'dist', 'index.js')).href);
  const extensionJar = await ensureExtension({ root, packageRoot, source });
  const bot = new calcite.Client({
    name: process.env.RAFTER_CALCITE_NAME || 'RafterItemProbe',
    version: '26.2',
    server: '127.0.0.1:25577',
    account: accountMode === 'offline' ? { type: 'offline', username: 'RafterItemProbe' } : { type: 'microsoft' },
    render: 'on-demand',
    memory: '1536M',
    javaPath: JAVA_PATH,
    allowJavaDownload: false,
    renderDistance: 5,
    maxFps: 10,
    reconnect: false,
    jvmArgs: [`-XX:ActiveProcessorCount=2`, `-Duser.home=${root}`, `-Djava.io.tmpdir=${root}`],
    gameArgs: ['--width', '1280', '--height', '960'],
    extensions: [extensionJar],
  });

  let started = false;
  let stopPromise;

  const publicStatus = () => {
    const status = bot.status();
    const { account, ...safe } = status;
    return safe;
  };

  const stopClient = async () => {
    const phase = bot.status().phase;
    if (phase === 'idle' || phase === 'stopped') return;
    if (!stopPromise) {
      stopPromise = withTimeout(bot.stop(), 'client stop', STOP_TIMEOUT_MS);
    }
    return stopPromise;
  };

  const requireStarted = () => {
    if (!started) throw fail('not_started', 'Send a start operation before using the client');
  };

  const ui = () => withTimeout(bot.call('rafterui.state', {}, { timeoutMs: OP_TIMEOUT_MS }), 'ui', OP_TIMEOUT_MS);

  const operation = async request => {
    switch (request.op) {
      case 'start':
        if (started) return publicStatus();
        try {
          await withTimeout(bot.start(), 'client start', START_TIMEOUT_MS);
        } catch (error) {
          await stopClient();
          throw error;
        }
        started = true;
        return publicStatus();
      case 'state':
        requireStarted();
        return withTimeout(bot.state(), 'state', OP_TIMEOUT_MS);
      case 'chat':
        requireStarted();
        return bot.chatSince(0, 30);
      case 'ui':
        requireStarted();
        return ui();
      case 'resource':
        requireStarted();
        return withTimeout(bot.call('rafterui.resource', { path: request.path }), 'resource', OP_TIMEOUT_MS);
      case 'command':
        requireStarted();
        if (typeof request.value !== 'string' || request.value.length === 0) throw fail('bad_request', 'command.value is required');
        await withTimeout(bot.command(request.value), 'command', OP_TIMEOUT_MS);
        return true;
      case 'hover':
      case 'click':
      case 'scroll':
      case 'close':
      case 'reload':
      case 'input':
      case 'setValue': {
        requireStarted();
        const name = request.op === 'input' || request.op === 'setValue' ? 'setValue' : request.op;
        const args = request.op === 'input' || request.op === 'setValue'
          ? { path: request.path, value: request.value }
          : request;
        return withTimeout(bot.call(`rafterui.${name}`, args, { timeoutMs: OP_TIMEOUT_MS }), name, OP_TIMEOUT_MS);
      }
      case 'wait': {
        const ms = Math.max(0, Math.min(Number(request.ms ?? 500), 5000));
        await new Promise(resolve => setTimeout(resolve, Number.isFinite(ms) ? ms : 500));
        return true;
      }
      case 'snap': {
        requireStarted();
        if (accountMode === 'offline') throw fail('offline_no_screenshot', 'Offline mode is layout-only and cannot take screenshots');
        if (typeof request.name !== 'string' || !/^[A-Za-z0-9_-]+$/.test(request.name)) {
          throw fail('bad_request', 'snap.name must contain only letters, digits, _ or -');
        }
        return withTimeout(
          (async () => {
            const ready = await bot.state();
            if (ready.loading) throw fail('loading', 'Resource reload is not complete');
            const shot = await bot.screenshot({ keep: false });
            const pngPath = join(evidence, `${request.name}.png`);
            const uiState = await ui();
            if (uiState.overlay || (await bot.state()).loading) {
              throw fail('loading', 'An overlay is covering the screen');
            }
            const uiPath = join(evidence, `${request.name}.ui.json`);
            await writeFile(pngPath, shot.png);
            await writeFile(uiPath, `${JSON.stringify(redact(uiState), null, 2)}\n`, 'utf8');
            return { pngPath, uiPath, bytes: shot.png.length, ui: uiState };
          })(),
          'snap',
          OP_TIMEOUT_MS,
        );
      }
      case 'stop':
        await stopClient();
        started = false;
        return { stopped: true };
      default:
        throw fail('bad_request', `Unknown operation: ${String(request.op)}`);
    }
  };

  const record = async (request, result) => {
    const line = { time: new Date().toISOString(), request: redact(request), result: redact(result) };
    await appendFile(actionFile, `${JSON.stringify(line)}\n`, 'utf8');
    console.log(displayJson(line));
  };

  const recordError = async (request, error) => {
    const result = { error: clean(error?.message || error), code: error?.code };
    await record(request, result);
  };

  console.log(JSON.stringify({ runnerPid: process.pid, evidence }));
  bot.on('phase', phase => console.log(JSON.stringify({ phase })));

  const stopOnSignal = () => {
    void stopClient().finally(() => process.exit(130));
  };
  process.once('SIGINT', stopOnSignal);
  process.once('SIGTERM', stopOnSignal);

  let input;
  try {
    input = createInterface({ input: process.stdin, crlfDelay: Infinity });
    inputLoop: for await (const line of input) {
      if (!line.trim()) continue;
      let request;
      try {
        request = JSON.parse(line);
        if (!request || typeof request.op !== 'string') throw fail('bad_request', 'Each JSON line needs an op string');
        const result = await operation(request);
        await record(request, result);
        if (request.op === 'stop') {
          input.close();
          break inputLoop;
        }
      } catch (error) {
        process.exitCode = 1;
        await recordError(request || { raw: line }, error);
      }
    }
  } finally {
    input?.close();
    await stopClient();
  }
}

try {
  await main();
} catch (error) {
  console.error(JSON.stringify({ error: clean(error?.message || error), code: error?.code }));
  process.exitCode = 1;
}
