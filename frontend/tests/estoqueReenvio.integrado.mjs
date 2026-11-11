import { chromium } from 'playwright';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const base = process.env.POP_RC_E2E_BASE_URL;
assert.match(base || '', /^http:\/\/127\.0\.0\.1:\d+$/);
const fixture = JSON.parse(await readFile(process.env.POP_RC_E2E_FIXTURE, 'utf8'));
const password = process.env.POP_RC_E2E_PASSWORD;
const browser = await chromium.launch({ executablePath: process.env.CHROME_BIN || undefined });
const errors = [];

async function openForm(page, item, second = false) {
  const dialog = page.getByRole('dialog').filter({ has: page.getByRole('heading', { name: item.title, exact: true }) });
  const select = (locator, value) => locator.selectOption(String(value));
  if (item.kind === 'entrada' || item.kind === 'entrada-rastreavel') {
    const tools = page.locator('.estoque-ferramentas');
    if (!(await tools.getAttribute('open') === '')) await tools.locator('summary').click();
    await tools.getByRole('button', { name: item.kind === 'entrada' ? item.title : 'Bobina / Rolo', exact: true }).click();
  } else if (item.kind === 'rastreavel') {
    const row = page.getByRole('row').filter({ hasText: item.code });
    await row.getByTitle('Transferir bobina/rolo completo').click();
  } else {
    await page.getByPlaceholder('Nome, part number, fabricante...').fill(item.name);
    const card = page.locator('article').filter({ hasText: item.name });
    const name = item.kind.startsWith('ajuste') ? `Ajustar saldo de ${item.name}` : `Transferir ${item.name}`;
    await card.getByRole('button', { name, exact: true }).click();
  }
  await dialog.waitFor();
  if (item.kind === 'entrada') {
    await select(dialog.locator('[name=materialId]'), item.mid);
    await select(dialog.locator('[name=localEstoqueId]'), item.origin);
    await select(dialog.locator('[name=funcionarioId]'), fixture.funcionario);
    await dialog.locator('[name=quantidade]').fill('4');
    await dialog.locator('[name=custoUnitarioEntrada]').fill('4');
  } else if (item.kind === 'entrada-rastreavel') {
    await select(dialog.locator('select').nth(0), item.mid);
    await select(dialog.locator('select').nth(1), item.origin);
    await dialog.getByPlaceholder('Ex: BOB-CAT6-001').fill(second ? item.body.codigo + '-2' : item.body.codigo);
    await dialog.getByPlaceholder('305').fill('40');
  } else {
    if (item.kind === 'rastreavel') {
      await select(dialog.locator('select'), second ? item.origin : item.dest);
    } else {
      await select(dialog.locator('select').nth(0), item.origin);
      if (item.kind === 'transferencia') await select(dialog.locator('select').nth(1), item.dest);
      if (item.kind === 'ajuste-') await dialog.getByRole('button', { name: 'Ajuste negativo' }).click();
      await dialog.locator('input[type=number]').fill('4');
    }
    await dialog.locator('textarea').fill('R02 motivo');
    await dialog.getByPlaceholder('Lançado por').fill('R02 lancador');
    await dialog.getByPlaceholder('Autorizado por').fill('R02 autorizador');
  }
  return dialog;
}

async function confirmarRetomada(page, botao) {
  await botao.click();
  const dialog = page.getByRole('dialog').filter({ hasText: 'Confirmar retomada de estoque' });
  await dialog.getByText(/retomar pode efetivar o lançamento/).waitFor();
  await dialog.getByRole('button', { name: 'Confirmar retomada', exact: true }).click();
}

async function exercise(item) {
  const context = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } });
  try {
    const login = await context.request.post('/api/auth/login', { data: { cpf: fixture.cpf, senha: password } });
    assert.equal(login.status(), 200, await login.text());
    const session = (await context.cookies()).find(cookie => cookie.name === 'SESSION');
    assert.ok(session);
    execFileSync(process.env.POP_RC_E2E_PYTHON, [process.env.POP_RC_E2E_HTTP_SCRIPT, '--expire-reauth', session.value]);
    let page;
    const calls = [];
    let lost = false;
    let divergir = false;
    let concorrer = false;
    let participantes = 0;
    let liberar;
    const barreira = new Promise(resolve => { liberar = resolve; });
    await context.route(url => url.pathname === item.path, async route => {
      if (route.request().method() !== item.method) return route.continue();
      if (concorrer) {
        if (++participantes === 2) liberar();
        await barreira;
      }
      const response = await route.fetch(divergir
        ? { postData: { ...route.request().postDataJSON(), metragem: 17 } }
        : {});
      const result = await response.text();
      calls.push({ key: route.request().headers()['idempotency-key'], status: response.status(), result,
        body: route.request().postData() });
      if (!lost && response.status() === item.status) {
        lost = true;
        return route.abort('connectionreset');
      }
      return route.fulfill({ response, body: result });
    });
    async function abrirPagina() {
      const tab = await context.newPage();
      tab.on('pageerror', error => errors.push(error.message));
      tab.setDefaultTimeout(15000);
      await tab.goto('/estoque/materiais');
      return tab;
    }
    const estado = () => JSON.parse(execFileSync(process.env.POP_RC_E2E_PYTHON,
      [process.env.POP_RC_E2E_HTTP_SCRIPT, '--snapshot', String(item.mid)], { encoding: 'utf8' }));
    page = await abrirPagina();
    const dialog = await openForm(page, item);
    const submit = dialog.locator('button[type=submit]');
    if (item.kind === 'entrada') {
      await page.evaluate(() => {
        window.escritaOriginal = Storage.prototype.setItem;
        Storage.prototype.setItem = function (key, value) {
          if (key.startsWith('poprc:estoque:intencao:')) throw new DOMException('Quota', 'QuotaExceededError');
          return window.escritaOriginal.call(this, key, value);
        };
      });
      await submit.click();
      await dialog.getByText(/O envio não foi iniciado/).waitFor();
      assert.equal(calls.length, 0);
      await page.evaluate(() => { Storage.prototype.setItem = window.escritaOriginal; });
    }
    await submit.dblclick();
    const reauth = page.getByRole('dialog').filter({ hasText: 'Confirme sua identidade' });
    await reauth.getByPlaceholder('Senha', { exact: true }).fill(password);
    await reauth.getByRole('button', { name: 'Confirmar', exact: true }).click();
    await dialog.getByText(/O envio continua salvo/).waitFor();
    assert.deepEqual(calls.map(call => call.status), [428, item.status]);
    const primeiro = calls[1];
    assert.equal(calls[0].key, primeiro.key);
    const depoisCommit = estado();

    if (['ajuste+', 'transferencia', 'rastreavel'].includes(item.kind)) {
      await page.close();
      page = await abrirPagina();
    } else {
      await page.reload();
    }
    const retomar = () => page.getByRole('button', { name: 'Retomar envio anterior', exact: true });
    await retomar().waitFor();
    assert.equal(calls.length, 2, 'A abertura não deve reenviar');
    if (item.kind === 'entrada') {
      const edit = await openForm(page, item);
      await edit.locator('[name=quantidade]').fill('17');
      await edit.getByRole('button', { name: 'Nova operação', exact: true }).waitFor();
      await edit.getByRole('button', { name: 'Fechar modal' }).click();
      const outra = await context.request.post('/api/auth/login', { data: { cpf: fixture.outroCpf, senha: password } });
      assert.equal(outra.status(), 200);
      await page.reload();
      await page.getByRole('heading', { name: 'Materiais e saldos' }).waitFor();
      assert.equal(await retomar().count(), 0);
      const csrf = await (await context.request.get('/api/auth/csrf')).json();
      const errada = await context.request.fetch(item.path, {
        method: item.method, data: primeiro.body,
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf.token,
          'Idempotency-Key': primeiro.key, 'X-Estoque-Funcionario': String(fixture.funcionario) },
      });
      assert.equal(errada.status(), 409, await errada.text());
      assert.deepEqual(estado(), depoisCommit);
      const volta = await context.request.post('/api/auth/login', { data: { cpf: fixture.cpf, senha: password } });
      assert.equal(volta.status(), 200);
      await page.reload();
      await retomar().waitFor();
    }

    async function nova() {
      const form = await openForm(page, item, true);
      if (item.kind === 'rastreavel') {
        await form.getByRole('button', { name: 'Nova operação', exact: true }).waitFor();
      }
      await form.locator('button[type=submit]').click();
      await form.waitFor({ state: 'hidden' });
      assert.notEqual(calls.at(-1).key, primeiro.key);
      if (!item.kind.includes('rastreavel')) assert.equal(calls.at(-1).body, primeiro.body);
    }

    if (item.kind === 'entrada') {
      divergir = true;
      await confirmarRetomada(page, retomar());
      await page.getByText(/Conflito no envio/).waitFor();
      assert.equal(calls.at(-1).status, 409);
      assert.equal(calls.at(-1).key, primeiro.key);
      assert.deepEqual(estado(), depoisCommit);
      divergir = false;
    }
    if (item.kind === 'rastreavel') await nova();
    const antesRetomar = estado();
    if (item.kind === 'rastreavel') {
      assert.equal(depoisCommit.unidades[0].local_estoque_id, item.dest);
      assert.equal(antesRetomar.unidades[0].local_estoque_id, item.origin);
    } else {
      assert.deepEqual(antesRetomar, depoisCommit);
    }
    const outraAba = item.kind === 'entrada' ? await abrirPagina() : null;
    if (outraAba) {
      const botaoOutraAba = outraAba.getByRole('button', { name: 'Retomar envio anterior' });
      await botaoOutraAba.waitFor();
      concorrer = true;
      await Promise.all([confirmarRetomada(page, retomar()), confirmarRetomada(outraAba, botaoOutraAba)]);
      await botaoOutraAba.waitFor({ state: 'hidden' });
      await outraAba.close();
      concorrer = false;
      assert.deepEqual(calls.slice(-2), [primeiro, primeiro]);
    } else {
      await confirmarRetomada(page, retomar());
    }
    await retomar().waitFor({ state: 'hidden' });
    assert.deepEqual(calls.at(-1), primeiro);
    const depoisRetomar = estado();
    assert.deepEqual(depoisRetomar, antesRetomar);
    console.log(JSON.stringify({ operacao: item.kind, sqlAntesRetomar: antesRetomar,
      sqlDepoisRetomar: depoisRetomar }));
    if (item.kind !== 'rastreavel') await nova();
    const history = await context.request.get('/api/estoque/historico');
    assert.equal(history.status(), 200);
    const movements = (await history.json()).filter(m => m.material.id === item.mid);
    assert.equal(movements.length, item.kind === 'entrada-rastreavel' ? 2 : 3);
    console.log(JSON.stringify({ operacao: item.kind, http: calls.map(call => call.status),
      movimentos: movements.map(m => m.id), mesmaChave: true, resultadoIgual: true, novaChave: true }));
  } finally {
    await context.close();
  }
}

try {
  for (const item of fixture.items) await exercise(item);
  assert.deepEqual(errors, []);
} finally {
  await browser.close();
}
