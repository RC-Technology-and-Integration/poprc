import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, copyFileSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';

test('preparo vincula contexto sem .git ao commit selecionado e preserva arquivos locais', () => {
  const root = mkdtempSync(join(tmpdir(), 'poprc-build-test-'));
  const repo = join(root, 'repo');
  mkdirSync(join(repo, 'scripts'), { recursive: true });
  copyFileSync(resolve('../scripts/prepare-build-revision.ps1'), join(repo, 'scripts/prepare-build-revision.ps1'));
  const git = (...args) => execFileSync('git', ['-C', repo, ...args], { encoding: 'utf8' }).trim();
  const run = (...args) => spawnSync('pwsh', ['-NoProfile', '-File', join(repo, 'scripts/prepare-build-revision.ps1'), ...args], { encoding: 'utf8' });
  try {
    git('init', '-q'); git('config', 'core.autocrlf', 'false'); git('config', 'user.name', 'TESTE'); git('config', 'user.email', 'teste@example.invalid');
    writeFileSync(join(repo, '.gitignore'), 'application-local.properties\n');
    writeFileSync(join(repo, 'fonte.txt'), 'primeira revisao\n');
    git('add', '.'); git('commit', '-qm', 'fixture 1');
    const selected = git('rev-parse', 'HEAD');
    writeFileSync(join(repo, 'fonte.txt'), 'segunda revisao\n');
    git('commit', '-qam', 'fixture 2');
    writeFileSync(join(repo, 'documento-preexistente.md'), 'preservar\n');
    writeFileSync(join(repo, 'application-local.properties'), 'configuracao ficticia\n');
    assert.notEqual(run('-Revision', 'HEAD').status, 0, 'checkout com arquivo não rastreado deve ser recusado');
    const context = join(root, 'contexto');
    const result = run('-Revision', selected, '-ContextDirectory', context);
    assert.equal(result.status, 0, result.stderr || result.stdout);
    assert.ok(result.stdout.includes(`APP_REVISION=${selected}`));
    assert.equal(readFileSync(join(context, 'fonte.txt'), 'utf8'), 'primeira revisao\n');
    assert.equal(existsSync(join(context, '.git')), false);
    assert.equal(existsSync(join(context, 'application-local.properties')), false);
    assert.equal(existsSync(join(context, 'documento-preexistente.md')), false);
    assert.equal(readFileSync(join(repo, 'documento-preexistente.md'), 'utf8'), 'preservar\n');
    assert.equal(readFileSync(join(repo, 'application-local.properties'), 'utf8'), 'configuracao ficticia\n');
    assert.notEqual(run('-Revision', 'HEAD', '-ContextDirectory', context).status, 0, 'destino existente não pode ser sobrescrito');
    assert.equal(readFileSync(join(context, 'fonte.txt'), 'utf8'), 'primeira revisao\n');
    assert.notEqual(run('-Revision', 'a'.repeat(40), '-ContextDirectory', join(root, 'invalido')).status, 0);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
