import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, parse } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const runner = fileURLToPath(new URL('./run.mjs', import.meta.url));

function withConfiguration(check) {
    const storage = mkdtempSync(join(tmpdir(), 'rafter-run-config-'));
    const env = {
        ...process.env,
        RAFTER_JAVA_PATH: join(storage, 'jdk', 'bin', 'java'),
        RAFTER_E2E_ROOT: join(storage, 'run'),
        CALCITE_HOME: join(storage, 'calcite'),
        RAFTER_CALCITE_PACKAGE: join(storage, 'missing-calcite'),
    };
    try {
        check(env, storage);
    } finally {
        rmSync(storage, { recursive: true, force: true });
    }
}

function run(env) {
    const result = spawnSync(process.execPath, [runner], {
        env,
        encoding: 'utf8',
        timeout: 5000,
    });
    assert.ifError(result.error);
    assert.equal(result.status, 1);
    return JSON.parse(result.stderr.trim());
}

test('Java must be explicitly configured', () => {
    withConfiguration(env => {
        delete env.RAFTER_JAVA_PATH;
        const result = run(env);
        assert.equal(result.code, 'configuration');
        assert.match(result.error, /RAFTER_JAVA_PATH/);
    });
});

test('filesystem roots cannot be used for test output', () => {
    withConfiguration((env, storage) => {
        env.RAFTER_E2E_ROOT = parse(storage).root;
        const result = run(env);
        assert.equal(result.code, 'configuration');
        assert.match(result.error, /filesystem root/);
    });
});

test('explicit portable directories reach dependency loading without starting a client', () => {
    withConfiguration(env => {
        const result = run(env);
        // 缺少本地 Calcite 包时必须停止，不进行登录或启动游戏。
        assert.equal(result.code, 'ERR_MODULE_NOT_FOUND');
    });
});
