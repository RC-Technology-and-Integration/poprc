import { chromium } from 'playwright';
import { readFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';

const baseURL = process.env.POP_RC_E2E_BASE_URL;
assert.match(baseURL || '', /^http:\/\/127\.0\.0\.1:\d+$/);
const fixture = JSON.parse(await readFile(process.env.POP_RC_E2E_FIXTURE, 'utf8'));
const senha = process.env.POP_RC_E2E_PASSWORD;
const browser = await chromium.launch({ executablePath: process.env.CHROME_BIN });
const context = await browser.newContext({ baseURL, viewport: { width: 1440, height: 1000 } });
const a = fixture.funcionario;
const entrada = fixture.items.find(item => item.kind === 'entrada');
const ajuste = fixture.items.find(item => item.kind === 'ajuste+');
const pendente = page => page.getByRole('button', { name: 'Retomar envio anterior', exact: true });
const confirmar = page => page.getByRole('button', { name: 'Confirmar retomada', exact: true });
const caminho = url => new URL(url).pathname;
const erros = [];
const abrir = async () => {
  const page = await context.newPage();
  page.on('pageerror', error => erros.push(error.message));
  page.setDefaultTimeout(16000);
  return page;
};

function bloqueio() {
  let sinalizar, liberar;
  const pronto = new Promise(resolve => { sinalizar = resolve; });
  const livre = new Promise(resolve => { liberar = resolve; });
  return { pronto, livre, sinalizar, liberar };
}

function sql(item = entrada) {
  return JSON.parse(execFileSync(process.env.POP_RC_E2E_PYTHON,
    [process.env.POP_RC_E2E_HTTP_SCRIPT, '--snapshot', String(item.mid)], { encoding: 'utf8' }));
}

async function login(cpf) {
  const response = await context.request.post('/api/auth/login', { data: { cpf, senha } });
  assert.equal(response.status(), 200, await response.text());
  return (await response.json()).funcionarioId;
}

async function avisar(page, id, fase) {
  await page.evaluate(({ id, fase }) => {
    const enviar = () => {
      const canal = new BroadcastChannel('poprc:auth:session-change');
      canal.postMessage({ id, fase });
      canal.close();
    };
    if (fase === 'inicio') {
      return new Promise(resolve => {
        navigator.locks.request('poprc:auth:transicao', () => new Promise(liberar => {
          window.liberarTransicaoDeTeste = liberar;
          enviar();
          resolve();
        }));
      });
    }
    enviar();
    if (fase === 'fim') window.liberarTransicaoDeTeste();
  }, { id, fase });
}

async function prepararRegistro(page, funcionario, item, alteracoes = {}) {
  return page.evaluate(({ funcionario, item, alteracoes }) => {
    const ambiente = JSON.stringify([location.origin, '']);
    const chave = crypto.randomUUID();
    const nome = 'poprc:estoque:intencao:' + encodeURIComponent(ambiente) + ':' + funcionario + ':' + chave;
    const registro = { versao: 1, ambiente, funcionario, chave, method: item.method.toLowerCase(),
      url: item.path.slice(4), corpo: JSON.stringify(item.body), ...alteracoes };
    localStorage.setItem(nome, JSON.stringify(registro));
    return { nome, registro };
  }, { funcionario, item, alteracoes });
}

async function aguardarEstoque(page) {
  await page.getByRole('heading', { name: 'Materiais e saldos', exact: true }).waitFor();
}

async function abrirEntrada(page, quantidade = "4") {
  const tools = page.locator('.estoque-ferramentas');
  if (await tools.getAttribute('open') === null) await tools.locator('summary').click();
  await tools.getByRole('button', { name: 'Entrada de Material', exact: true }).click();
  const dialog = page.getByRole('dialog').filter({ hasText: 'Entrada de Material' });
  await dialog.locator('[name=materialId]').selectOption(String(entrada.mid));
  await dialog.locator('[name=localEstoqueId]').selectOption(String(entrada.origin));
  await dialog.locator('[name=funcionarioId]').selectOption(String(a));
  await dialog.locator('[name=quantidade]').fill(quantidade);
  await dialog.locator('[name=custoUnitarioEntrada]').fill('4');
  return dialog.locator('button[type=submit]');
}

try {
  const controle = await abrir();
  assert.equal(await login(fixture.cpf), a);
  await controle.goto('/estoque/materiais');
  await aguardarEstoque(controle);

  // A primeira resposta se perde antes de alcançar o backend.
  let original;
  const antes = sql();
  await controle.route(url => caminho(url.href) === entrada.path, async route => {
    original = { chave: route.request().headers()['idempotency-key'], corpo: route.request().postData() };
    await route.abort('connectionreset');
  }, { times: 1 });
  await (await abrirEntrada(controle)).click();
  await controle.getByRole('dialog').getByText(/A retomada pode efetivar o lançamento/).waitFor();
  await controle.getByRole('button', { name: 'Fechar modal' }).click();
  await pendente(controle).waitFor();
  assert.deepEqual(sql(), antes);

  const lenta = await abrir();
  const authA = bloqueio();
  const authB = bloqueio();
  await lenta.route('**/api/auth/me', async route => {
    const response = await route.fetch();
    const id = (await response.json()).funcionarioId;
    const gate = id === a ? authA : authB;
    gate.sinalizar(id);
    await gate.livre;
    await route.fulfill({ response });
  });
  await lenta.goto('/estoque/materiais');
  assert.equal(await authA.pronto, a);
  await avisar(controle, 'troca-1', 'inicio');
  const b = await login(fixture.outroCpf);
  assert.notEqual(b, a);
  const outro = await prepararRegistro(controle, b, ajuste);
  await avisar(controle, 'troca-1', 'fim');
  assert.equal(await authB.pronto, b);
  await lenta.getByText('Validando sessão...').waitFor();
  assert.equal(await pendente(lenta).count(), 0);
  authB.liberar();
  await aguardarEstoque(lenta);
  await lenta.getByText(/Ajuste positivo ·/).waitFor();
  const liberada = lenta.waitForResponse(async response => caminho(response.url()) === '/api/auth/me'
    && (await response.json()).funcionarioId === a);
  authA.liberar();
  await liberada;
  await lenta.getByText(/Ajuste positivo ·/).waitFor();
  assert.equal(await lenta.getByRole('region', { name: 'Envios de estoque pendentes' }).getByText(new RegExp(entrada.name)).count(), 0);
  assert.equal(await pendente(lenta).count(), 1);
  assert.equal((await (await context.request.get('/api/auth/me')).json()).funcionarioId, b);
  assert.deepEqual(sql(), antes);
  await lenta.close();
  console.log(JSON.stringify({ caso: 'auth-me-antigo', respostaDeAEntreguePorUltimo: true, sessaoReal: b,
    pendenciaExibida: 'somente B', efeitos: 0 }));

  // O servidor valida o ator em todos os caminhos, mesmo sem aviso entre abas.
  const csrf = (await (await context.request.get('/api/auth/csrf')).json()).token;
  for (const item of fixture.items) {
    const estado = sql(item);
    const response = await context.request.fetch(item.path, {
      method: item.method, data: item.body,
      headers: { 'X-XSRF-TOKEN': csrf, 'Idempotency-Key': original.chave, 'X-Estoque-Funcionario': String(a) },
    });
    assert.equal(response.status(), 409);
    assert.deepEqual(sql(item), estado);
  }
  await controle.evaluate(nome => localStorage.removeItem(nome), outro.nome);
  await login(fixture.cpf);
  await controle.goto('/estoque/materiais');
  await pendente(controle).waitFor();
  await pendente(controle).click();
  await confirmar(controle).waitFor();
  let mutacoes = 0;
  controle.on('request', request => {
    if (fixture.items.some(item => item.path === caminho(request.url()) &&
      item.method === request.method())) mutacoes++;
  });
  await login(fixture.outroCpf);
  await confirmar(controle).click();
  await controle.getByRole('button', { name: 'Abrir menu de R02 OUTRO OPERADOR' }).waitFor();
  await aguardarEstoque(controle);
  assert.equal(mutacoes, 0);
  assert.equal(await pendente(controle).count(), 0);
  assert.deepEqual(sql(), antes);
  console.log(JSON.stringify({ caso: 'troca-sem-notificacao', mutacoesDaInterface: mutacoes, rotasTestadasSobB: fixture.items.length }));

  await login(fixture.cpf);
  await controle.goto('/estoque/materiais');
  await pendente(controle).waitFor();
  await pendente(controle).click();
  const preflight = bloqueio();
  await controle.route('**/api/auth/me', async route => {
    const response = await route.fetch();
    preflight.sinalizar((await response.json()).funcionarioId);
    await preflight.livre;
    await route.fulfill({ response });
  }, { times: 1 });
  const tentativaNaJanela = controle.waitForResponse(response => caminho(response.url()) === entrada.path);
  await confirmar(controle).click();
  assert.equal(await preflight.pronto, a);
  await login(fixture.outroCpf);
  await context.request.get('/api/auth/csrf');
  preflight.liberar();
  const recusada = await tentativaNaJanela;
  assert.equal(recusada.request().headers()['x-estoque-funcionario'], String(a));
  assert.equal(recusada.status(), 409, await recusada.text());
  assert.deepEqual(sql(), antes);
  console.log(JSON.stringify({ caso: 'janela-apos-preflight', status: 409, atorDaIntencao: a, efeitos: 0 }));

  // Uma resposta de leitura antiga e um 201 já confirmado chegam durante a nova sessão.
  await login(fixture.cpf);
  await controle.goto('/estoque/materiais');
  await aguardarEstoque(controle);
  const dados = bloqueio();
  const operacao = bloqueio();
  const dadosA = await abrir();
  await dadosA.route('**/api/estoque/materiais', async route => {
    const res = await route.fetch();
    const body = await res.json();
    body[0].nome = 'MARCADOR SINTETICO EXCLUSIVO DE A';
    dados.sinalizar();
    await dados.livre;
    await route.fulfill({ response: res, json: body });
  }, { times: 1 });
  await dadosA.goto('/estoque/materiais');
  await dados.pronto;
  await controle.route(url => caminho(url.href) === entrada.path, async route => {
    const res = await route.fetch();
    operacao.sinalizar({ status: res.status(), chave: route.request().headers()['idempotency-key'] });
    await operacao.livre;
    await route.fulfill({ response: res });
  }, { times: 1 });
  await (await abrirEntrada(controle, "5")).click();
  const commit = await operacao.pronto;
  assert.equal(commit.status, 201);
  const depoisCommit = sql();
  assert.equal(depoisCommit.global.saldo, antes.global.saldo + 5);
  await avisar(controle, 'troca-2', 'inicio');
  await login(fixture.outroCpf);
  await avisar(controle, 'troca-2', 'fim');
  // A aba que simula a mudança da sessão também recebe a nova identidade ao recarregar.
  await dadosA.getByRole('button', { name: 'Abrir menu de R02 OUTRO OPERADOR' }).waitFor();
  const dadosEntregues = dadosA.waitForResponse(async response => caminho(response.url()) === '/api/estoque/materiais'
    && (await response.text()).includes('MARCADOR SINTETICO EXCLUSIVO DE A'));
  dados.liberar();
  await dadosEntregues;
  assert.equal(await dadosA.getByText('MARCADOR SINTETICO EXCLUSIVO DE A').count(), 0);
  const respostaAntiga = controle.waitForResponse(response => caminho(response.url()) === entrada.path);
  // Envia a notificação também à aba que disparou a mutação de estoque.
  await avisar(dadosA, 'troca-2b', 'atualizar');
  await controle.getByRole('button', { name: 'Abrir menu de R02 OUTRO OPERADOR' }).waitFor();
  operacao.liberar();
  await respostaAntiga;
  assert.equal(await controle.getByText('Entrada de material registrada com sucesso!', { exact: true }).count(), 0);
  assert.equal(await pendente(controle).count(), 0);
  assert.ok(await controle.evaluate(chave => Object.keys(localStorage).some(k => k.endsWith(':' + chave)), commit.chave));
  assert.deepEqual(sql(), depoisCommit);
  await dadosA.close();
  console.log(JSON.stringify({ caso: 'respostas-antigas', leituraDescartada: true, confirmacaoDeAIsolada: true,
    saldo: depoisCommit.global.saldo }));

  await login(fixture.cpf);
  await controle.goto('/estoque/materiais');
  await pendente(controle).first().waitFor();
  const antesRetomada = sql();
  const mutacoesAntes = mutacoes;
  const transferencia = fixture.items.find(item => item.kind === "transferencia");
  const antesTransferencia = sql(transferencia);
  const invalidos = await Promise.all([
    prepararRegistro(controle, a, entrada, { corpo: '{}' }),
    prepararRegistro(controle, a, entrada, { url: ['/estoque/entrada'] }),
    prepararRegistro(controle, a, entrada, { corpo: '{"materialId":"7","metragem":["4"]}' }),
    prepararRegistro(controle, a, ajuste, { corpo: JSON.stringify({ ...ajuste.body, motivo: [] }) }),
    prepararRegistro(controle, a, entrada, { funcionario: b }),
    prepararRegistro(controle, a, transferencia, {
      corpo: JSON.stringify({ ...transferencia.body, destinoId: transferencia.origin }),
    }),
  ]);
  await controle.reload();
  await controle.getByText(/Há envios salvos inválidos/).waitFor();
  assert.equal(await pendente(controle).count(), 2);
  assert.equal(mutacoes, mutacoesAntes);
  assert.deepEqual(sql(), antesRetomada);
  assert.deepEqual(sql(transferencia), antesTransferencia);
  assert.ok(await controle.evaluate(nomes => nomes.every(nome => localStorage.getItem(nome)),
    invalidos.map(item => item.nome)));

  const linhaOriginal = controle.getByText(/ · 4 · custo unitário/).locator('..');
  const alvo = linhaOriginal.getByRole('button', { name: 'Retomar envio anterior' });
  await alvo.click();
  const originalSalvo = await controle.evaluate(chave => {
    const nome = Object.keys(localStorage).find(k => k.endsWith(':' + chave));
    return { nome, raw: localStorage.getItem(nome) };
  }, original.chave);
  await controle.evaluate(({ nome, raw }) => {
    localStorage.setItem(nome, JSON.stringify({ ...JSON.parse(raw), corpo: '{}' }));
  }, originalSalvo);
  await confirmar(controle).click();
  await controle.getByText(/O envio salvo mudou ou ficou indisponível/).waitFor();
  assert.equal(mutacoes, mutacoesAntes);
  await controle.evaluate(({ nome, raw }) => localStorage.setItem(nome, raw), originalSalvo);
  await controle.reload();
  await alvo.click();
  await controle.getByText(/você recuperará o resultado original/).waitFor();
  await controle.getByText(/retomar pode efetivar o lançamento/).waitFor();
  assert.equal(mutacoes, mutacoesAntes);
  const enviada = controle.waitForResponse(res => caminho(res.url()) === entrada.path);
  await confirmar(controle).click();
  const aplicada = await enviada;
  assert.equal(aplicada.status(), 201);
  assert.equal(aplicada.request().headers()['idempotency-key'], original.chave);
  assert.equal(aplicada.request().postData(), original.corpo);
  await controle.getByText('Envio anterior confirmado.', { exact: true }).waitFor();
  assert.equal(sql().historico.length, antesRetomada.historico.length + 1);
  const reenvio = controle.waitForResponse(res => caminho(res.url()) === entrada.path);
  await pendente(controle).click();
  await confirmar(controle).click();
  const recuperada = await reenvio;
  assert.equal(recuperada.status(), 201);
  assert.equal(recuperada.request().headers()['idempotency-key'], commit.chave);
  await pendente(controle).waitFor({ state: 'hidden' });
  assert.equal(sql().historico.length, antesRetomada.historico.length + 1);
  console.log(JSON.stringify({ caso: 'armazenamento-e-confirmacao', invalidosPreservados: invalidos.length,
    mutacoesDosInvalidos: 0, semCommit: 'um efeito', comCommit: 'replay sem efeito' }));

  // Logout iniciado em outra aba cancela uma confirmação de senha já respondida pelo servidor.
  const sessao = (await context.cookies()).find(cookie => cookie.name === 'SESSION');
  execFileSync(process.env.POP_RC_E2E_PYTHON, [process.env.POP_RC_E2E_HTTP_SCRIPT, '--expire-reauth', sessao.value]);
  const logoutTab = await abrir();
  await logoutTab.goto('/estoque/materiais');
  await aguardarEstoque(logoutTab);
  const senhaAtrasada = bloqueio();
  await controle.route('**/api/auth/reauth', async route => {
    const response = await route.fetch();
    senhaAtrasada.sinalizar(response.status());
    await senhaAtrasada.livre;
    await route.fulfill({ response });
  }, { times: 1 });
  const antesReauth = sql();
  const antesChamadas = mutacoes;
  await (await abrirEntrada(controle)).click();
  const dialogoSenha = controle.getByRole('dialog').filter({ hasText: 'Confirme sua identidade' });
  await dialogoSenha.getByPlaceholder('Senha', { exact: true }).fill(senha);
  await dialogoSenha.getByRole('button', { name: 'Confirmar', exact: true }).click();
  assert.equal(await senhaAtrasada.pronto, 204);
  const sair = async () => {
    await logoutTab.getByRole('button', { name: /Abrir menu de/ }).click();
    await logoutTab.getByRole('button', { name: 'Sair', exact: true }).last().click();
  };
  let logouts = 0;
  logoutTab.on('request', req => {
    if (caminho(req.url()) === '/api/auth/logout') logouts++;
  });
  await sair();
  await logoutTab.getByRole('alert').getByText(/Outra aba está autenticando/).waitFor();
  assert.equal(logouts, 0);
  assert.equal(mutacoes, antesChamadas + 1);
  assert.deepEqual(sql(), antesReauth);
  const reenvioAposSenha = controle.waitForResponse(res =>
    caminho(res.url()) === entrada.path && res.status() === 201);
  senhaAtrasada.liberar();
  await reenvioAposSenha;
  assert.equal(sql().global.saldo, antesReauth.global.saldo + 4);
  await logoutTab.getByRole('button', { name: 'Fechar aviso', exact: true }).click();
  await logoutTab.keyboard.press('Escape');
  // O menu permanece aberto depois da recusa.
  const menu = logoutTab.getByRole('button', { name: /Abrir menu de/ });
  if (await menu.getAttribute('aria-expanded') === 'true') await menu.click();
  await sair();
  await controle.waitForURL('**/login');
  assert.equal(logouts, 1);
  assert.equal(await dialogoSenha.count(), 0);
  const aposLogout = sql();
  console.log(JSON.stringify({ caso: 'logout-e-reauth-atrasada', logoutConcorrenteBloqueado: true,
    reauthConcluida: true, sessaoEncerrada: true }));
  await controle.getByLabel('CPF', { exact: true }).fill(fixture.outroCpf);
  await controle.getByLabel('Senha', { exact: true }).fill(senha);
  await controle.getByRole('button', { name: 'Entrar com CPF', exact: true }).click();
  await controle.getByRole('button', { name: 'Abrir menu de R02 OUTRO OPERADOR' }).waitFor();
  await controle.goto('/estoque/materiais');
  await aguardarEstoque(controle);
  assert.equal(await pendente(controle).count(), 0);
  assert.equal((await (await context.request.get('/api/auth/me')).json()).funcionarioId, b);
  assert.deepEqual(sql(), aposLogout);
  console.log(JSON.stringify({ caso: 'login-e-navegacao', contaConfirmada: b, pendenciasDeA: 0 }));
  assert.deepEqual(erros, []);
} finally {
  await browser.close();
}
