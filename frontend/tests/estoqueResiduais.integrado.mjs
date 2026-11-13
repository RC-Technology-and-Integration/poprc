import { chromium } from 'playwright';
import { createServer, request } from 'node:http';
import { readFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import { diagnosticoSessao } from './estoqueDiagnostico.mjs';

const baseURL = process.env.POP_RC_E2E_BASE_URL;
assert.match(baseURL, /^http:\/\/127\.0\.0\.1:\d+$/);
const fixture = JSON.parse(await readFile(process.env.POP_RC_E2E_FIXTURE, 'utf8'));
const senha = process.env.POP_RC_E2E_PASSWORD;
const browser = await chromium.launch({ executablePath: process.env.CHROME_BIN });
const entrada = fixture.items.find(item => item.kind === 'entrada');
const rotaLogin = '/api/auth/login';
const outroMenu = 'Abrir menu de R02 OUTRO OPERADOR';
const menuA = 'Abrir menu de R02 ADMIN';
const caminho = url => new URL(url).pathname;

const comando = (...args) => execFileSync(process.env.POP_RC_E2E_PYTHON,
  [process.env.POP_RC_E2E_HTTP_SCRIPT, ...args], { encoding: 'utf8' });
const estado = id => JSON.parse(comando('--snapshot', String(id)));
const intencao = chave => Number(comando('--intencao', chave));
const registro = (caso, dados) => console.log(JSON.stringify({ caso, ...dados }));

async function abrir(context, path = '/login') {
  const page = await context.newPage();
  page.setDefaultTimeout(20000);
  await page.goto(path);
  return page;
}

async function preencher(page, cpf) {
  await page.getByLabel('CPF', { exact: true }).fill(cpf);
  await page.getByLabel('Senha', { exact: true }).fill(senha);
}

function entrar(page) {
  return page.getByRole('button', { name: 'Entrar com CPF', exact: true }).click();
}

async function atual(context) {
  const response = await context.request.get('/api/auth/me');
  return response.ok() ? (await response.json()).funcionarioId : null;
}

async function sair(page) {
  await page.getByRole('button', { name: /Abrir menu de/ }).click();
  await page.getByRole('button', { name: 'Sair', exact: true }).last().click();
  await page.waitForURL('**/login');
}

async function concorrentes(fecharTitular) {
  const capturado = Promise.withResolvers();
  const liberar = Promise.withResolvers();
  const entregue = Promise.withResolvers();
  let loginInterceptado = false;
  const proxy = createServer((cliente, navegador) => {
    const login = cliente.method === 'POST' && cliente.url === rotaLogin && !loginInterceptado;
    if (login) loginInterceptado = true;
    const destino = new URL(cliente.url, baseURL);
    const upstream = request(destino, { method: cliente.method, headers: { ...cliente.headers, host: destino.host } },
      async resposta => {
        if (login) {
          try {
            const dados = [];
            for await (const parte of resposta) dados.push(parte);
            const body = Buffer.concat(dados);
            assert.equal(resposta.statusCode, 200);
            assert.ok(resposta.headers['set-cookie'].some(cookie => /^SESSION=/.test(cookie)));
            capturado.resolve(JSON.parse(body).funcionarioId);
            await liberar.promise;
            const conexaoFechada = navegador.destroyed || navegador.socket?.destroyed === true;
            if (!conexaoFechada) {
              navegador.writeHead(resposta.statusCode, resposta.headers);
              navegador.end(body);
            }
            entregue.resolve({ conexaoFechada });
          } catch (error) {
            capturado.reject(error);
            entregue.reject(error);
            navegador.destroy();
          }
        } else {
          navegador.writeHead(resposta.statusCode, resposta.headers);
          resposta.pipe(navegador);
        }
      });
    upstream.on('error', () => navegador.destroy());
    cliente.pipe(upstream);
  });
  await new Promise(resolve => proxy.listen(0, '127.0.0.1', resolve));
  const context = await browser.newContext({
    baseURL: 'http://127.0.0.1:' + proxy.address().port, viewport: { width: 1440, height: 1000 },
  });
  const diagnostico = await diagnosticoSessao(context, fecharTitular ? 'titular-fechada' : 'login-concorrente');
  try {
    const a = await abrir(context);
    const b = await abrir(context);
    let postsB = 0;
    b.on('request', req => { if (caminho(req.url()) === rotaLogin) postsB++; });
    await preencher(a, fixture.cpf);
    await preencher(b, fixture.outroCpf);
    await entrar(a);
    assert.equal(await capturado.promise, fixture.funcionario);
    await entrar(b);
    await b.getByText(/Outra aba está autenticando/).waitFor();
    assert.equal(postsB, 0);
    assert.equal(await atual(context), null);
    if (fecharTitular) {
      await a.close();
    } else {
      const recebida = a.waitForResponse(r => caminho(r.url()) === rotaLogin);
      liberar.resolve();
      await recebida;
      await b.getByRole('button', { name: menuA, exact: true }).waitFor();
      assert.equal(await atual(context), fixture.funcionario);
      await sair(b);
    }
    await b.waitForFunction(async () => {
      const { held, pending } = await navigator.locks.query();
      return [...held, ...pending].every(lock => lock.name !== 'poprc:auth:transicao');
    });
    await preencher(b, fixture.outroCpf);
    await entrar(b);
    await b.getByRole('button', { name: outroMenu, exact: true }).waitFor();
    const identidade = await atual(context);
    assert.notEqual(identidade, fixture.funcionario);
    assert.equal(postsB, 1);
    liberar.resolve();
    const entrega = await entregue.promise;
    if (fecharTitular) assert.equal(entrega.conexaoFechada, true);
    assert.equal(await atual(context), identidade);
    registro(fecharTitular ? 'titular-fechada' : 'login-concorrente', {
      respostaDeAComSetCookie: true, postConcorrenteDeB: 0, postValidoDeB: postsB, identidade, ...entrega,
    });
  } catch (error) {
    await diagnostico.falha(error);
    throw error;
  } finally {
    liberar.resolve();
    await diagnostico.encerrar();
    await context.close();
    proxy.closeAllConnections();
    await new Promise(resolve => proxy.close(resolve));
  }
}

async function falhasDeCoordenacao() {
  for (const semLocks of [false, true]) {
    const context = await browser.newContext({ baseURL });
    try {
      if (semLocks) await context.addInitScript(() => Object.defineProperty(navigator, 'locks', { value: undefined }));
      const page = await abrir(context);
      let posts = 0;
      page.on('request', req => { if (caminho(req.url()) === rotaLogin) posts++; });
      if (!semLocks) await page.route('**/api/auth/login', route => route.abort('connectionreset'), { times: 1 });
      await preencher(page, fixture.cpf);
      await entrar(page);
      await page.getByText(semLocks ? /não permite coordenar a sessão/ : /Não foi possível conectar/).waitFor();
      assert.equal(await atual(context), null);
      assert.equal(posts, semLocks ? 0 : 1);
      if (!semLocks) {
        await preencher(page, fixture.outroCpf);
        await entrar(page);
        await page.getByRole('button', { name: outroMenu, exact: true }).waitFor();
        assert.equal(posts, 2);
      }
      registro(semLocks ? 'coordenacao-indisponivel' : 'falha-de-rede', { posts });
    } finally {
      await context.close();
    }
  }
}

async function precisao(anterior) {
  const context = await browser.newContext({ baseURL });
  try {
    const login = await context.request.post(rotaLogin, { data: { cpf: fixture.cpf, senha } });
    assert.equal(login.status(), 200);
    const csrf = (await (await context.request.get('/api/auth/csrf')).json()).token;
    const headers = { 'X-XSRF-TOKEN': csrf, 'Content-Type': 'application/json' };
    function enviar(item, dados, chave, status) {
      const porIntencao = chave ? { 'Idempotency-Key': chave, 'X-Estoque-Funcionario': String(fixture.funcionario) } : {};
      return context.request.fetch(item.path, {
        method: item.method, data: typeof dados === 'string' ? dados : JSON.stringify(dados),
        headers: { ...headers, ...porIntencao },
      }).then(async response => {
        assert.equal(response.status(), status, await response.text());
        return response.json();
      });
    }

    const corpoInvalido = { ...entrada.body, metragem: 0.0001 };
    if (anterior) {
      const antes = estado(entrada.mid);
      const chave = randomUUID();
      const response = await enviar(entrada, corpoInvalido, chave, 201);
      const depois = estado(entrada.mid);
      assert.equal(response.metragem, 0.0001);
      assert.equal(depois.global.saldo, antes.global.saldo);
      assert.equal(depois.historico.at(-1).metragem, 0);
      assert.equal(depois.historico.length, antes.historico.length + 1);
      assert.equal(intencao(chave), 1);
      registro('http-direto-antes', { status: 201, antes: antes.global, depois: depois.global,
        ultimo: depois.historico.at(-1), intencoes: 1 });
      return;
    }

    const page = await abrir(context, '/estoque/materiais');
    const chave = randomUUID();
    const antes = estado(entrada.mid);
    const local = await page.evaluate(({ item, funcionario, chave, body }) => {
      const ambiente = JSON.stringify([location.origin, '']);
      const nome = 'poprc:estoque:intencao:' + encodeURIComponent(ambiente) + ':' + funcionario + ':' + chave;
      const raw = JSON.stringify({ versao: 1, ambiente, funcionario, chave, method: 'post',
        url: item.path.slice(4), corpo: JSON.stringify(body) });
      localStorage.setItem(nome, raw);
      return { nome, raw };
    }, { item: entrada, funcionario: fixture.funcionario, chave, body: corpoInvalido });
    let mutacoes = 0;
    page.on('request', req => { if (req.method() === 'POST' && caminho(req.url()) === entrada.path) mutacoes++; });
    await page.reload();
    await page.getByText(/Há envios salvos inválidos/).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Retomar envio anterior' }).count(), 0);
    assert.equal(mutacoes, 0);
    assert.equal(await page.evaluate(nome => localStorage.getItem(nome), local.nome), local.raw);
    await enviar(entrada, corpoInvalido, chave, 400);
    await enviar(entrada, corpoInvalido, null, 400);
    assert.equal(intencao(chave), 0);
    assert.deepEqual(estado(entrada.mid), antes);
    registro('precisao-pendencia-http', { mutacoesDaInterface: mutacoes, pendenciaPreservada: true,
      httpComESemChave: 400, antes, depois: estado(entrada.mid), intencoes: 0 });

    for (const item of fixture.items.filter(i => !['entrada', 'rastreavel'].includes(i.kind))) {
      const campo = item.kind === 'entrada-rastreavel' ? 'metragemInicial' : 'valor';
      const snapshot = estado(item.mid);
      const id = randomUUID();
      await enviar(item, { ...item.body, [campo]: 0.0001 }, id, 400);
      assert.equal(intencao(id), 0);
      assert.deepEqual(estado(item.mid), snapshot);
    }
    for (const [metragem, custo] of [['0.001', '0.0001'], ['0.1250000', '4.1234000']]) {
      const raw = JSON.stringify(entrada.body).replace('"metragem":4', '"metragem":' + metragem)
        .replace('"custoUnitarioEntrada":4', '"custoUnitarioEntrada":' + custo);
      const id = randomUUID();
      await enviar(entrada, raw, id, 201);
      assert.equal(intencao(id), 1);
    }
    const custoExcessivo = randomUUID();
    const depoisValidos = estado(entrada.mid);
    await enviar(entrada, { ...entrada.body, custoUnitarioEntrada: 0.00001 }, custoExcessivo, 400);
    assert.equal(intencao(custoExcessivo), 0);
    assert.deepEqual(estado(entrada.mid), depoisValidos);
    assert.equal(depoisValidos.global.saldo, 40.126);
    assert.deepEqual(depoisValidos.historico.slice(-2).map(m => m.metragem), [0.001, 0.125]);
    assert.deepEqual(depoisValidos.historico.slice(-2).map(m => m.custo_unitario), [0.0001, 4.1234]);

    const criar = async tipo => {
      const suffix = randomUUID();
      return (await enviar({ path: '/api/estoque/materiais', method: 'POST' }, {
        nome: 'R02 limite ' + suffix, partNumber: suffix, tipoControle: tipo,
        unidadeMedida: tipo === 'METRAGEM' ? 'METRO' : 'UNIDADE',
        quantidadeDisponivel: 0, metragemDisponivel: 0, custoMedio: 0,
      }, null, 201)).id;
    };
    const midDecimal = await criar('METRAGEM');
    const maxDecimal = { ...entrada.body, materialId: midDecimal, metragem: 99999999999.999, custoUnitarioEntrada: 0 };
    await enviar(entrada, maxDecimal, randomUUID(), 201);
    assert.equal(estado(midDecimal).global.saldo, 99999999999.999);
    const midInteiro = await criar('UNIDADE');
    const maxInteiro = { ...entrada.body, materialId: midInteiro, metragem: null, quantidade: 2147483647, custoUnitarioEntrada: 0 };
    await enviar(entrada, maxInteiro, randomUUID(), 201);
    assert.equal(estado(midInteiro).global.quantidade, 2147483647);
    for (const [mid, dados] of [[midDecimal, { ...maxDecimal, metragem: 0.001 }], [midInteiro, { ...maxInteiro, quantidade: 1 }]]) {
      const antesLimite = estado(mid);
      const id = randomUUID();
      await enviar(entrada, dados, id, 400);
      assert.equal(intencao(id), 0);
      assert.deepEqual(estado(mid), antesLimite);
    }
    const transferencia = fixture.items.find(item => item.kind === 'transferencia');
    const inteiroAntes = estado(midInteiro);
    await enviar(transferencia, { ...transferencia.body, materialId: midInteiro,
      origemId: entrada.origin, valor: 0.125 }, randomUUID(), 400);
    assert.deepEqual(estado(midInteiro), inteiroAntes);
    registro('fronteiras', { valido: [0.001, 0.125, 99999999999.999, 2147483647],
      custoComQuatroCasas: true, acimaDoLimiteRecusado: true, ajusteTransferenciaECadastroInvalidosRecusados: true });
  } finally {
    await context.close();
  }
}

async function replayAposTrocaDeTipo() {
  const context = await browser.newContext({ baseURL });
  try {
    const login = await context.request.post(rotaLogin, { data: { cpf: fixture.cpf, senha } });
    assert.equal(login.status(), 200);
    const csrf = (await (await context.request.get('/api/auth/csrf')).json()).token;
    const headers = { 'X-XSRF-TOKEN': csrf };
    const materiais = '/api/estoque/materiais';
    const material = { nome: 'R02 mudança de controle', partNumber: randomUUID(),
      tipoControle: 'METRAGEM', unidadeMedida: 'METRO', custoMedio: 0,
      quantidadeDisponivel: 0, metragemDisponivel: 0 };
    const criado = await context.request.post(materiais, { headers, data: material });
    assert.equal(criado.status(), 201, await criado.text());
    const { id } = await criado.json();

    const page = await abrir(context, '/estoque/materiais');
    const endpoint = url => url.pathname === entrada.path;
    const ehEntrada = req => req.method() === 'POST' && caminho(req.url()) === entrada.path;
    const pedido = req => ({ method: req.method(), rota: caminho(req.url()), corpo: req.postData(),
      chave: req.headers()['idempotency-key'] });
    const posts = [];
    page.on('request', req => { if (ehEntrada(req)) posts.push(pedido(req)); });
    const lerSalvo = chave => page.evaluate(chave => {
      const nome = Object.keys(localStorage).find(key => key.endsWith(':' + chave));
      return nome ? { nome, raw: localStorage.getItem(nome) } : null;
    }, chave);
    const abrirEntrada = async valor => {
      const tools = page.locator('.estoque-ferramentas');
      if (await tools.getAttribute('open') === null) await tools.locator('summary').click();
      await tools.getByRole('button', { name: 'Entrada de Material', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: 'Entrada de Material' });
      await dialog.locator('[name=materialId]').selectOption(String(id));
      await dialog.locator('[name=localEstoqueId]').selectOption(String(entrada.origin));
      await dialog.locator('[name=funcionarioId]').selectOption(String(fixture.funcionario));
      await dialog.locator('[name=quantidade]').fill(valor);
      return dialog;
    };
    const salvarSemResposta = async (valor, entregar) => {
      let resultado;
      await page.route(endpoint, async route => {
        if (entregar) {
          const response = await route.fetch();
          resultado = { status: response.status(), corpo: await response.text() };
        }
        await route.abort('connectionreset');
      }, { times: 1 });
      const dialog = await abrirEntrada(valor);
      await dialog.locator('button[type=submit]').click();
      await dialog.getByText(/A retomada pode efetivar o lançamento/).waitFor();
      await dialog.getByRole('button', { name: 'Fechar modal' }).click();
      const original = posts.at(-1);
      assert.ok(original.chave);
      assert.equal(JSON.parse(original.corpo).metragem, Number(valor));
      return { original, resultado };
    };

    const concluida = await salvarSemResposta('0.125', true);
    const naoRecebida = await salvarSemResposta('0.25', false);
    assert.equal(concluida.resultado.status, 201);
    assert.equal(intencao(concluida.original.chave), 1);
    assert.equal(intencao(naoRecebida.original.chave), 0);
    assert.equal(estado(id).global.saldo, 0.125);

    const ajuste = await context.request.post('/api/estoque/ajustes', { headers, data: {
      materialId: id, localEstoqueId: entrada.origin, valor: 0.125, tipo: 'AJUSTE_NEGATIVO',
      motivo: 'Encerrar saldo em metros', lancadoPor: 'Operador', autorizadoPor: 'Supervisor',
    } });
    assert.equal(ajuste.status(), 201, await ajuste.text());
    assert.equal(estado(id).global.saldo, 0);
    const alterado = await context.request.put(materiais + '/' + id, { headers,
      data: { ...material, tipoControle: 'UNIDADE', unidadeMedida: 'UNIDADE' } });
    assert.equal(alterado.status(), 200, await alterado.text());
    assert.equal((await alterado.json()).tipoControle, 'UNIDADE');
    const antes = estado(id);
    const salvo = await lerSalvo(naoRecebida.original.chave);
    assert.equal(JSON.parse(salvo.raw).corpo, naoRecebida.original.corpo);

    await page.reload();
    const pendencias = page.getByRole('region', { name: 'Envios de estoque pendentes' });
    const retomar = medida => pendencias.getByText(' · ' + medida + ' · ', { exact: false })
      .locator('..').getByRole('button', { name: 'Retomar envio anterior' });
    const confirmar = page.getByRole('button', { name: 'Confirmar retomada', exact: true });
    const reenviar = async medida => {
      await retomar(medida).click();
      const resposta = page.waitForResponse(r => ehEntrada(r.request()));
      await confirmar.click();
      return resposta;
    };
    await pendencias.waitFor();
    registro('tipo-alterado-antes-de-retomar', { saldoAntesDaTroca: 0, tipoAtual: 'UNIDADE',
      opcoes: await pendencias.getByRole('button', { name: 'Retomar envio anterior' }).count() });
    const replay = await reenviar('0.125');
    assert.equal(replay.status(), concluida.resultado.status);
    assert.equal(await replay.text(), concluida.resultado.corpo);
    await page.getByText('Envio anterior confirmado.', { exact: true }).waitFor();
    assert.deepEqual(posts.at(-1), concluida.original);
    assert.equal(await lerSalvo(concluida.original.chave), null);
    assert.deepEqual(estado(id), antes);
    assert.equal(intencao(concluida.original.chave), 1);

    const recusada = await reenviar('0.25');
    assert.equal(recusada.status(), 400, await recusada.text());
    assert.deepEqual(posts.at(-1), naoRecebida.original);
    await page.getByText(/O envio continua salvo/).waitFor();
    assert.deepEqual(await lerSalvo(naoRecebida.original.chave), salvo);
    assert.equal(intencao(naoRecebida.original.chave), 0);
    assert.deepEqual(estado(id), antes);

    const dialog = await abrirEntrada('2');
    const respostaNova = page.waitForResponse(r => ehEntrada(r.request()));
    await dialog.getByRole('button', { name: 'Nova operação', exact: true }).click();
    assert.equal((await respostaNova).status(), 201);
    await page.getByText('Entrada de material registrada com sucesso!', { exact: true }).waitFor();
    const nova = posts.at(-1);
    assert.ok(nova.chave && ![concluida.original.chave, naoRecebida.original.chave].includes(nova.chave));
    assert.equal(JSON.parse(nova.corpo).quantidade, 2);
    assert.equal(JSON.parse(nova.corpo).metragem, null);
    assert.equal(intencao(nova.chave), 1);
    const depoisNova = estado(id);
    assert.equal(depoisNova.global.quantidade, 2);
    assert.equal(depoisNova.historico.length, antes.historico.length + 1);
    assert.deepEqual(await lerSalvo(naoRecebida.original.chave), salvo);

    await retomar('0.25').click();
    const outraAba = await abrir(context, '/estoque/materiais');
    await outraAba.evaluate(({ nome, raw }) => {
      const item = JSON.parse(raw);
      item.corpo = JSON.stringify({ ...JSON.parse(item.corpo), metragem: 0.375 });
      localStorage.setItem(nome, JSON.stringify(item));
    }, salvo);
    const totalPosts = posts.length;
    await confirmar.click();
    await page.getByText(/O envio salvo mudou ou ficou indisponível/).waitFor();
    assert.equal(posts.length, totalPosts);
    assert.equal(intencao(naoRecebida.original.chave), 0);
    assert.deepEqual(estado(id), depoisNova);
    registro('replay-apos-troca-de-tipo', { http: [replay.status(), recusada.status(), 201],
      mesmaChaveRotaMetodoECorpo: true, resultadoOriginal: true,
      sqlIgualAposReplayERecusa: true, saldosAntesDaNova: antes.global,
      saldoDepoisDaNova: depoisNova.global, novaChave: true,
      intencoesPorChave: [1, 0, 1], edicaoConcorrenteBloqueada: true });
  } finally {
    await context.close();
  }
}


try {
  if (process.env.POP_RC_E2E_PRECISAO_BASE === '1') {
    await precisao(true);
  } else {
    const falhas = [];
    for (const teste of [() => concorrentes(false), () => concorrentes(true),
      falhasDeCoordenacao, () => precisao(false), replayAposTrocaDeTipo]) {
      try {
        await teste();
      } catch (error) {
        console.error(error);
        falhas.push(error);
      }
    }
    if (falhas.length) throw new AggregateError(falhas, 'Regressões residuais');
  }
} finally {
  await browser.close();
}
