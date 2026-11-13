// Executar somente com a fixture de RetiradaV2IntegrationTest em runtime descartável.
// Não intercepta APIs: navegador -> Vite -> backend real -> PostgreSQL/arquivos isolados.
import { chromium } from 'playwright';
import { readFile, mkdir, writeFile, readdir, rename } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
const base = process.env.POP_RC_E2E_BASE_URL;
assert.match(base || '', /^http:\/\/127\.0\.0\.1:\d+$/);
assert.ok(process.env.POP_RC_E2E_FIXTURE && process.env.POP_RC_E2E_OUT);
const fixture = JSON.parse(await readFile(process.env.POP_RC_E2E_FIXTURE, 'utf8'));
assert.ok(fixture.osId && fixture.osMaterialId, 'Fixture precisa incluir OS criada pelo serviço normal.');
const out = process.env.POP_RC_E2E_OUT;
await mkdir(out, { recursive: true });
const browser = await chromium.launch({ executablePath: process.env.CHROME_BIN || undefined, headless: true });
const relatorio = { base, backendReal: true, mocks: false, tamanhos: [], requisicoes: {}, errosPagina: [] };
let ultimaPagina;
const rede = [];
relatorio.rede = rede;
async function sessao(perfil) {
  const context = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } });
  const conta = fixture.contas.find(c => c.perfil === perfil);
  const login = await context.request.post('/api/auth/login', { data: { cpf: conta.cpf, senha: 'RetiradasTeste2026!' } });
  assert.equal(login.status(), 200, await login.text());
  const page = await context.newPage();
  ultimaPagina = page;
  const requisicoes = [];
  page.on('response', r => {
    const url = new URL(r.url());
    if (r.status() >= 400 && url.pathname.startsWith('/api/')) {
      (relatorio.falhasHttp ||= []).push({ perfil, metodo: r.request().method(),
        url: url.origin + url.pathname, status: r.status() });
    }
  });
  page.on('request', r => {
    const url = new URL(r.url());
    if (!url.pathname.startsWith('/api/')) return;
    const evento = { perfil, metodo: r.method(), url: url.origin + url.pathname,
      momento: new Date().toISOString(), secao: new URL(page.url()).pathname };
    rede.push(evento);
    requisicoes.push(evento);
  });
  page.on('pageerror', e => relatorio.errosPagina.push(e.message));
  return { context, page, requisicoes };
}
async function carregar(page, path) { await page.goto(path);await page.locator('.retiradas-v2').waitFor();await page.getByText('Carregando retiradas…', { exact: true }).waitFor({ state: 'hidden' }); }
async function assertSemOverflow(page, largura) {assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `overflow ${largura}px`);}
async function topo(page){await page.evaluate(()=>{document.scrollingElement.scrollTop=0;document.querySelector('main')?.scrollTo(0,0);});}
try {
  const { context, page } = await sessao('ADMIN');
  const materiais = await (await context.request.get('/api/estoque/materiais')).json();
  const material = materiais.find(m => m.id === fixture.materialId);
  assert.ok(material);
  await carregar(page, '/estoque/retiradas?nova=1');
  await page.getByRole('heading', { name: 'Preparar retirada', exact: true }).waitFor();
  await page.getByLabel('Depósito único').selectOption(String(fixture.localId));
  for (const [label, valor] of [['Identificador do destino', `UI-${Date.now()}`], ['Destino / obra','Obra fictícia - teste de navegador'], ['Motivo da retirada','Ensaio local sem valor operacional'], ['Quem entrega / confere','Entregador fictício'], ['Quem retira','Retirante fictício']]) await page.getByLabel(label, { exact: true }).fill(valor);
  await page.getByLabel('Buscar nome / código').fill(material.partNumber);
  await page.getByRole('button', { name: `Selecionar ${material.nome}`, exact: true }).click();
  await page.getByLabel(`${material.nome} · UNIDADE`, { exact: true }).fill('2');
  for (const largura of [1440, 390, 320]) {
    await page.setViewportSize({ width: largura, height: 1000 });await topo(page);await assertSemOverflow(page, largura);
    await page.screenshot({ path: `${out}/rascunho-${largura}.png`, fullPage: true });relatorio.tamanhos.push(largura);
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByRole('button', { name: 'Salvar rascunho e revisar' }).click();
  await page.getByRole('button', { name: 'Autorizar esta emissão avulsa' }).waitFor();
  const orId = new URL(page.url()).searchParams.get('or');assert.ok(orId);relatorio.orId = orId;
  await page.reload();await page.getByRole('button', { name: 'Autorizar esta emissão avulsa' }).waitFor();
  await page.getByLabel('Justificativa / motivo').fill('Autorizar esta emissão fictícia específica');
  await page.getByRole('button', { name: 'Autorizar esta emissão avulsa' }).click();
  await page.getByRole('button', { name: 'Confirmar resumo e emitir com reserva' }).click();
  await page.getByText('Conferir entrega física', { exact: true }).waitFor();
  if (process.env.POP_RC_E2E_UPLOADS) {
    const raiz = process.env.POP_RC_E2E_UPLOADS.replaceAll('\\','/');assert.ok(raiz.includes('/outputs/implementacao-retiradas-v2-20261030/') || raiz.endsWith('/target/test-uploads'));
    const dto = await (await context.request.get(`/api/ordens-retirada/v2/${orId}`)).json();const f = dto.arquivos.find(a=>a.tipo==='EMISSAO');let caminho;
    for(const nome of await readdir(`${raiz}/.retiradas-v2`)){if(!/^[0-9a-f-]{36}$/.test(nome))continue;const bytes=await readFile(`${raiz}/.retiradas-v2/${nome}`);if(createHash('sha256').update(bytes).digest('hex')===f.hash){caminho=`${raiz}/.retiradas-v2/${nome}`;break;}}
    assert.ok(caminho);const preservado=`${caminho}.falha-sintetica-${orId}`;await rename(caminho,preservado);
    try{await page.getByRole('button',{name:/Abrir \/ imprimir documento emitido/}).click();await page.getByRole('alert').filter({hasText:'Não foi possível abrir'}).waitFor();}
    finally{await rename(preservado,caminho);}
    const popup=context.waitForEvent('page');await page.getByRole('button',{name:/Abrir \/ imprimir documento emitido/}).click();const aberta=await popup;await aberta.close();relatorio.falhaServidorStorage503ERecuperacao=true;
  }
  await page.screenshot({ path: `${out}/emitida-1440.png`, fullPage: true });
  const before = (await (await context.request.get('/api/estoque/materiais')).json()).find(m => m.id === fixture.materialId);
  await page.getByText('Conferir entrega física', { exact: true }).click();
  await page.getByLabel(`${material.nome} · UNIDADE`, { exact: true }).fill('1');
  await page.getByLabel('Justificativa / motivo').fill('Entrega parcial de ensaio: um item não entregue');
  await context.setOffline(true);await page.getByRole('button', { name: 'Confirmar quantidades efetivas e registrar baixa única' }).click();
  await page.getByText(/Intenção preservada/).waitFor();await context.setOffline(false);
  const entregasAntesRetry = rede.filter(r => r.metodo === 'POST' && r.url.endsWith(`/v2/${orId}/entrega`)).length;
  await page.waitForFunction(() => [...document.querySelectorAll('button')].some(b => b.textContent === 'Consultar/repetir a mesma intenção' && !b.disabled));
  // A primeira ação pode desabilitar/remover o botão; não esperar uma segunda ação Playwright nele.
  await page.getByRole('button', { name: 'Consultar/repetir a mesma intenção' }).evaluate(b => {
    if (b.disabled) throw new Error('Retry precisa estar habilitado antes das duas tentativas.');
    b.click();b.click();
  });relatorio.transporteInterrompidoSomenteNoCliente=true;
  await page.getByRole('button', { name: 'Anexar comprovante assinado', exact: true }).waitFor();
  const postsRetry = rede.filter(r => r.metodo === 'POST' && r.url.endsWith(`/v2/${orId}/entrega`)).length - entregasAntesRetry;
  assert.equal(postsRetry, 1, 'Duas tentativas rápidas devem enviar um único POST de entrega.');
  relatorio.retryDuplo = { tentativas: 2, posts: postsRetry };
  const after = (await (await context.request.get('/api/estoque/materiais')).json()).find(m => m.id === fixture.materialId);
  assert.equal(after.quantidadeDisponivel, before.quantidadeDisponivel - 1);
  relatorio.baixaUnica = { antes: before.quantidadeDisponivel, depois: after.quantidadeDisponivel };
  const comprovante = process.env.POP_RC_E2E_COMPROVANTE;assert.ok(comprovante);
  await page.getByLabel('Comprovante assinado — PDF, PNG ou JPEG (até 10 MiB)').setInputFiles(comprovante);
  await page.getByRole('button', { name: 'Anexar comprovante assinado', exact: true }).click();
  await page.getByRole('button', { name: 'Conferir comprovante', exact: true }).waitFor();
  await page.getByRole('button', { name: 'Conferir comprovante', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: 'explicitamente' }).waitFor();
  relatorio.autorrevisaoAutomaticaNegada = true;
  const sup = await sessao('SUPERVISOR_TECNICO');
  // Uma página ADMIN ainda pode atualizar Retiradas enquanto a supervisão abre Pendências.
  const pedidoAdmin = page.waitForRequest(r => new URL(r.url()).pathname === '/api/estoque/importacoes/planilha/retiradas');
  await Promise.all([
    page.reload(),
    pedidoAdmin,
    carregar(sup.page, `/estoque/pendencias?or=${orId}`),
  ]);
  await sup.page.getByRole('button', { name: 'Conferir comprovante', exact: true }).waitFor();
  relatorio.requisicoes.pendenciasSupervisor = [...sup.requisicoes];
  const inesperadas = sup.requisicoes.filter(r => /\/api\/estoque\/|\/api\/contratos|\/api\/funcionarios|\/api\/comarcas/.test(new URL(r.url).pathname));
  assert.deepEqual(inesperadas, [], 'seções inativas não devem carregar');
  await sup.page.getByLabel('Justificativa (obrigatória na rejeição e autorrevisão)').fill('Documento sintético rejeitado para exercitar reenvio');
  await sup.page.getByRole('button', { name: 'Rejeitar e manter pendência' }).click();
  await sup.page.getByText('REJEITADO', { exact: true }).waitFor();
  await page.reload();await page.getByRole('button', { name: 'Reenviar novo comprovante' }).waitFor();
  await page.getByLabel('Comprovante assinado — PDF, PNG ou JPEG (até 10 MiB)').setInputFiles(comprovante);
  await page.getByRole('button', { name: 'Reenviar novo comprovante' }).click();
  await page.getByRole('button', { name: 'Conferir comprovante', exact: true }).waitFor();
  await sup.page.reload();await sup.page.getByRole('button', { name: 'Conferir comprovante', exact: true }).click();
  await sup.page.getByText('CONFERIDO', { exact: true }).waitFor();
  for (const largura of [1440,390,320]) {await sup.page.setViewportSize({ width: largura, height: 1000 });await topo(sup.page);await assertSemOverflow(sup.page,largura);await sup.page.screenshot({ path: `${out}/conferida-${largura}.png`, fullPage: true });}
  await sup.page.getByRole('button',{name:'Abrir menu',exact:true}).click();await sup.page.getByRole('link',{name:'Pendências documentais',exact:true}).waitFor();assert.equal(await sup.page.locator('#estoque-submenu a').count(),1);await sup.page.screenshot({path:`${out}/menu-supervisor-320.png`});await sup.page.getByRole('button',{name:'Fechar menu',exact:true}).first().click();
  await sup.page.keyboard.press('Tab');assert.notEqual(await sup.page.evaluate(() => document.activeElement.tagName), 'BODY');
  await page.reload();await page.getByText('Registrar devolução e concluir retorno', { exact: true }).click();
  await page.getByLabel('Quem entrega / confere', { exact: true }).fill('Recebedor fictício');
  await page.getByLabel('Quem retira / devolve', { exact: true }).fill('Devolvedor fictício');
  await page.getByLabel(`${material.nome} · UNIDADE`, { exact: true }).fill('0');
  await page.getByLabel('Assinatura de recebimento da devolução (PNG)').setInputFiles(comprovante);
  await page.getByRole('button', { name: 'Confirmar devolução', exact: true }).click();
  await page.getByRole('button', { name: /Abrir DEVOLUCAO/ }).waitFor();
  const final = (await (await context.request.get('/api/estoque/materiais')).json()).find(m => m.id === fixture.materialId);
  assert.equal(final.quantidadeDisponivel, after.quantidadeDisponivel);
  relatorio.documentacaoSemBaixa = true;relatorio.devolucaoCompativel = true;
  const dto = await (await context.request.get(`/api/ordens-retirada/v2/${orId}`)).json();
  for (const f of dto.arquivos) {const resp = await context.request.get(`/api/ordens-retirada/v2/${orId}/arquivos/${f.id}`);assert.equal(resp.status(),200);await writeFile(`${out}/arquivo-${f.id}${f.mime === 'application/pdf' ? '.pdf' : '.png'}`,await resp.body());}
  // Back/forward e deep link real, sem nova entrega.
  await page.goto('/estoque/historico');await page.goBack();await page.locator('.retiradas-v2').waitFor();assert.equal(new URL(page.url()).searchParams.get('or'),orId);
  await page.goForward();assert.equal(new URL(page.url()).pathname,'/estoque/historico');relatorio.navegacao = true;
  // OS criada pelo serviço existente: reserva prévia, parcial e complemento em outra OR.
  const mOs = materiais.find(m=>m.id===fixture.osMaterialId);assert.ok(mOs);
  const orsOs=[];
  for(const [solicitado,efetivo] of [[4,3],[1,1]]){
    await carregar(page,'/estoque/retiradas?nova=1');await page.getByLabel('Depósito único').selectOption(String(fixture.localId));await page.getByLabel('OS ou saída avulsa').selectOption(String(fixture.osId));
    for(const [label,valor] of [['Identificador do destino',`OS-${fixture.osId}`],['Destino / obra','Obra fictícia vinculada à OS'],['Motivo da retirada','Teste integrado de OS e complemento'],['Quem entrega / confere','Entrega fictícia OS'],['Quem retira','Retirante fictício OS']])await page.getByLabel(label,{exact:true}).fill(valor);
    await page.getByLabel('Buscar nome / código').fill(mOs.partNumber);await page.getByRole('button',{name:`Selecionar ${mOs.nome}`,exact:true}).click();await page.getByLabel(`${mOs.nome} · UNIDADE`,{exact:true}).fill(String(solicitado));
    await page.getByRole('button',{name:'Salvar rascunho e revisar'}).click();await page.getByRole('button',{name:'Confirmar resumo e emitir com reserva'}).waitFor();
    const atualId=new URL(page.url()).searchParams.get('or');orsOs.push(atualId);
    const reservadoAntes=(await(await context.request.get('/api/estoque/materiais')).json()).find(m=>m.id===fixture.osMaterialId).quantidadeReservada;
    await page.getByRole('button',{name:'Confirmar resumo e emitir com reserva'}).click();await page.getByText('Conferir entrega física',{exact:true}).waitFor();
    assert.equal((await(await context.request.get('/api/estoque/materiais')).json()).find(m=>m.id===fixture.osMaterialId).quantidadeReservada,reservadoAntes,'emissão não duplica reserva OS');
    await page.getByText('Conferir entrega física',{exact:true}).click();await page.getByLabel(`${mOs.nome} · UNIDADE`,{exact:true}).fill(String(efetivo));await page.getByLabel('Justificativa / motivo').fill('Efetivos conferidos; complemento em OR distinta');await page.getByRole('button',{name:'Confirmar quantidades efetivas e registrar baixa única'}).click();
    const anexar=page.getByRole('button',{name:'Anexar comprovante assinado',exact:true});await anexar.waitFor();assert.equal(await anexar.isDisabled(),true,'arquivo de outra OR não pode ficar selecionado');
    await page.getByLabel('Comprovante assinado — PDF, PNG ou JPEG (até 10 MiB)').setInputFiles(comprovante);await anexar.click();await page.getByRole('button',{name:'Conferir comprovante',exact:true}).waitFor();
    await carregar(sup.page,`/estoque/pendencias?or=${atualId}`);await sup.page.getByRole('button',{name:'Conferir comprovante',exact:true}).click();await sup.page.getByText('CONFERIDO',{exact:true}).waitFor();
    await page.reload();await page.getByText('Registrar devolução e concluir retorno',{exact:true}).click();await page.getByLabel(`${mOs.nome} · UNIDADE`,{exact:true}).fill('0');await page.getByLabel('Assinatura de recebimento da devolução (PNG)').setInputFiles(comprovante);await page.getByRole('button',{name:'Confirmar devolução',exact:true}).click();await page.getByRole('button',{name:/Abrir DEVOLUCAO/}).waitFor();
  }
  assert.notEqual(orsOs[0],orsOs[1]);const osFinal=(await(await context.request.get('/api/estoque/materiais')).json()).find(m=>m.id===fixture.osMaterialId);assert.equal(osFinal.quantidadeDisponivel,6);assert.equal(osFinal.quantidadeReservada,0);relatorio.osParcialComplemento={osId:fixture.osId,ors:orsOs,fisico:6,reservado:0};
  assert.deepEqual(relatorio.errosPagina, []);
  await sup.context.close();await context.close();
  relatorio.resultado='APROVADO';
} catch (e) {
  relatorio.resultado = 'FALHOU';
  relatorio.erro = String(e.stack || e);
  if (ultimaPagina) {
    const [captura, tela] = await Promise.allSettled([
      ultimaPagina.screenshot({ path: out + '/falha.png', fullPage: true }),
      ultimaPagina.locator('body').innerText(),
    ]);
    if (captura.status === 'rejected') relatorio.erroCaptura = String(captura.reason);
    if (tela.status === 'fulfilled') relatorio.telaNaFalha = tela.value.slice(-7000);
  }
  throw e;
} finally {
  await writeFile(out + '/RESULTADO_E2E.json', JSON.stringify(relatorio, null, 2));
  await browser.close();
}
