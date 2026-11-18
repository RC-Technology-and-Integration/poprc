import { test, expect } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { campoAssinatura, desenharTeste } from './capturaAssinatura.mjs';

const BASE = 'http://127.0.0.1:5177';
const MARCA = 'TESTE — SEM VALIDADE OPERACIONAL';
test.use({ baseURL: BASE, viewport: { width: 1280, height: 900 } });
test.setTimeout(180_000);

const papeisDocumento = [
  ['Técnico responsável', 'Técnico responsável', 'assinaturaTecnicoBase64'],
  ['Gestor do projeto RC', 'Gestor do projeto fictício', 'assinaturaGestorBase64'],
  ['Gerente do fórum', 'Gerente do fórum', 'assinaturaGerenteBase64'],
];

test('nova OS fictícia captura TESTE nos documentos, vistoria, retirada e devolução', async ({ page, context }, testInfo) => {
  const provas = {};
  const conferir = (papel, captura, valores) => {
    const png = Buffer.from(captura.split(',')[1], 'base64');
    writeFileSync(testInfo.outputPath(`assinatura-${papel}.png`), png);
    const hash = value => createHash('sha256').update(value).digest('hex');
    provas[papel] = {
      captura: hash(captura),
      png: hash(png),
      ...Object.fromEntries(Object.entries(valores).map(([etapa, valor]) => [
        etapa, typeof valor === 'string' ? hash(valor) : null,
      ])),
    };
    writeFileSync(testInfo.outputPath('comparacao-assinaturas.json'), JSON.stringify(provas, null, 2));
    for (const [etapa, valor] of Object.entries(valores)) {
      expect(valor, `${papel}: assinatura da captura independente na etapa ${etapa}`).toBe(captura);
    }
  };
  const api = async (path, data, method = 'POST', multipart) => {
    const csrf = await (await context.request.get(`${BASE}/api/auth/csrf`)).json();
    const response = await context.request.fetch(`${BASE}/api/${path}`, {
      method, headers: { [csrf.headerName]: csrf.token },
      ...(multipart ? { multipart } : data === undefined ? {} : { data }),
    });
    expect(response.ok(), `${method} ${path}: ${await response.text()}`).toBeTruthy();
    return response.status() === 204 ? null : response.json();
  };
  const config = await api('auth/config', undefined, 'GET');
  expect(config.securityEnabled).toBe(true);
  expect(config.bootstrapRequired).toBe(true); // Exige banco vazio próprio; recusa fixture habitual.
  const admin = await api('auth/bootstrap', { nome: 'TESTE ADMIN FICTICIO', cpf: '12345678909', senha: 'SenhaFicticiaTESTE123', cidade: 'CIDADE FICTICIA TESTE' });
  // CPF matemático de fixture de autenticação; documentos nunca usam esse CPF.
  expect((await api('documentos-internos/configuracao', undefined, 'GET')).simulacaoHabilitada).toBe(true);
  const contrato = (await api('contratos', { cliente:'CLIENTE FICTICIO TESTE', contrato:'TESTE-E2E', tipoContratante:'SETOR_PUBLICO', vigenciaInicio:'2026-01-01', vigenciaFim:'2030-01-01', valorGlobal:100 })).contrato;
  const projeto = (await api('projetos', { contrato:{id:contrato.id}, responsavel:{id:admin.funcionarioId}, nomeComarcaVinculada:'UNIDADE FICTICIA TESTE', dataInicio:'2026-01-01', dataFim:'2030-01-01' })).projeto;
  const material = await api('estoque/materiais', { nome:'MATERIAL FICTICIO TESTE E2E', partNumber:'TESTE-E2E', categoria:'MATERIAL_CONSUMO', tipoControle:'UNIDADE', unidadeMedida:'UNIDADE', quantidadeDisponivel:10, localizacao:'ESTOQUE FICTICIO TESTE' });
  const futuro = days => new Date(Date.now() + days * 86400000).toISOString().slice(0,19);
  const os = await api('ordens-servico', { simulacao:true, descricao:'CENARIO FICTICIO TESTE', projetoId:projeto.id, contratoId:contrato.id, dataHoraInicio:futuro(1), dataHoraFim:futuro(2), deadline:futuro(3), materiais:[{materialId:material.id,quantidadePrevista:1}] });
  expect(os.simulacao).toBe(true);
  const comarca = (await api('comarcas', undefined, 'GET')).find(c => c.projeto?.id === projeto.id);
  expect(comarca.ordemServico.simulacao).toBe(true);
  page.on('dialog', dialog => { throw new Error(`Aviso inesperado: ${dialog.message()}`); });
  await page.goto('/obras');
  await page.getByRole('button', {name:'Documento Inicial - Serviços Previstos',exact:true}).click();
  let documento = page.getByRole('dialog', {name:/Documento Inicial/});
  await expect(documento.getByText(MARCA, {exact:true})).toBeVisible();
  await documento.getByRole('button', {name:'Salvar documento',exact:true}).click();
  await expect(documento.getByText('Documento salvo no servidor.', {exact:true})).toBeVisible();

  const assinarDocumento = async (tipo, documentoId) => {
    for (const [i, [rotulo, rotuloCartao, campo]] of papeisDocumento.entries()) {
      await documento.getByText(`${rotuloCartao} — TESTE`, { exact: true }).locator('..')
        .getByRole('button', {name:'Assinar TESTE',exact:true}).click();
      const modal = page.getByRole('dialog', { name: new RegExp(`^${rotulo} -`) });
      await expect(modal.getByRole('button',{name:'Confirmar',exact:true})).toBeDisabled();
      await expect(modal.getByText(/Desenhe a palavra TESTE/)).toBeVisible();
      const papel = `${tipo}-papel-${i+1}`;
      const captured = await desenharTeste(page, modal.locator('canvas'), papel, i*3);
      await page.screenshot({path:testInfo.outputPath(`${tipo}-papel-${i+1}.png`)});
      const pending = page.waitForResponse(r => r.url().includes('/assinaturas/') && r.request().method() === 'PATCH');
      await modal.getByRole('button',{name:'Confirmar',exact:true}).click();
      const response = await pending;
      expect(response.status()).toBe(200);
      const saved = await response.json();
      const persistido = (await api(`documentos-internos/comarca/${comarca.id}`, undefined, 'GET'))
        .find(d => d.id === (documentoId ?? saved.id));
      conferir(papel, captured, {
        payload: response.request().postDataJSON().assinaturaBase64,
        resposta: saved[campo],
        persistido: persistido?.[campo],
      });
      expect(saved.simulacao).toBe(true);
      await expect(modal).not.toBeVisible();
    }
    await expect(documento.getByText(/Documento totalmente assinado e registrado/)).toBeVisible();
    await expect(documento.getByText(/Integridade: confirmada/)).toBeVisible();
    await page.screenshot({path:testInfo.outputPath(`${tipo}-registrado.png`)});
    await documento.getByRole('button',{name:'Fechar modal'}).click();
  };
  await assinarDocumento('vistoria');
  const final = await api('documentos-internos/vistoria', { comarcaId:comarca.id, tipo:'ENCERRAMENTO_OS', simulacao:true, recebidoPor:'TESTE', conteudoJson:'{"descricaoServicos":"CENARIO FICTICIO TESTE"}' });
  await page.getByRole('button',{name:'Documento Inicial - Serviços Previstos',exact:true}).click();
  documento = page.getByRole('dialog',{name:/Documento Inicial/});
  await documento.getByRole('button',{name:new RegExp(`Encerramento #${final.id}`)}).click();
  documento = page.getByRole('dialog',{name:/Documento Final/});
  await assinarDocumento('encerramento', final.id);

  await page.getByText('Coletar Assinatura',{exact:true}).click();
  const modalVistoria = page.getByRole('dialog',{name:/Coletar Assinatura/});
  await expect(modalVistoria.getByRole('button',{name:'Confirmar',exact:true})).toBeDisabled();
  const capturaVistoria = await desenharTeste(page, modalVistoria.locator('canvas'), 'vistoria-campo');
  const aguardandoVistoria = page.waitForResponse(r => r.url().includes('/vistoria/assinatura') && r.request().method()==='PATCH');
  await modalVistoria.getByRole('button',{name:'Confirmar',exact:true}).click();
  const respostaVistoria = await aguardandoVistoria;
  expect(respostaVistoria.status()).toBe(200);
  conferir('vistoria-campo', capturaVistoria, {
    payload: respostaVistoria.request().postDataJSON().assinaturaBase64,
    resposta: (await respostaVistoria.json()).assinaturaBase64,
    persistido: (await api(`comarcas/${comarca.id}`, undefined, 'GET')).assinaturaBase64,
  });
  await expect(modalVistoria).not.toBeVisible();
  const fotoTeste = Buffer.from(capturaVistoria.split(',')[1], 'base64');
  await api(`comarcas/${comarca.id}/vistoria/foto`, undefined, 'POST', {foto:{name:'TESTE.png',mimeType:'image/png',buffer:fotoTeste}});
  await api(`comarcas/${comarca.id}/avancar-etapa`, {}, 'PATCH');

  await page.goto('/estoque/retiradas');
  await page.getByRole('button',{name:'Executar Retirada',exact:true}).click();
  const retirada = page.getByRole('dialog',{name:/Executar Retirada/});
  await expect(retirada.locator('canvas')).toHaveCount(2);
  const conferente = await desenharTeste(page,
    campoAssinatura(retirada, /^Assinatura fictícia do conferente/).locator('canvas'), 'conferente');
  const retirante = await desenharTeste(page,
    campoAssinatura(retirada, /^Assinatura fictícia de quem levou/).locator('canvas'), 'retirante', 3);
  expect(conferente).not.toBe(retirante);
  await page.screenshot({path:testInfo.outputPath('or-retirada-capturada.png')});
  let pending = page.waitForResponse(r => r.url().endsWith('/executar') && r.request().method()==='PATCH');
  await retirada.getByRole('button',{name:'Confirmar Retirada',exact:true}).click();
  let response = await pending;
  expect(response.status()).toBe(200);
  let ordem = await response.json();
  const consultarOrdem = async id => (await api(`ordens-retirada/os/${os.id}`, undefined, 'GET'))
    .find(or => or.id === id);
  let persistida = await consultarOrdem(ordem.id);
  for (const [papel, captura, campo] of [
    ['conferente', conferente, 'assinaturaConferenteBase64'],
    ['retirante', retirante, 'assinaturaRetiranteBase64'],
  ]) {
    conferir(papel, captura, {
      payload: response.request().postDataJSON()[campo],
      resposta: ordem[campo],
      persistido: persistida?.[campo],
    });
  }
  await expect(retirada).not.toBeVisible();
  await page.getByRole('button',{name:'Registrar Devolução',exact:true}).click();
  const devolucao = page.getByRole('dialog',{name:/Devolu/});
  const recebido = await desenharTeste(page,
    campoAssinatura(devolucao, /^Assinatura fictícia de recebimento/).locator('canvas'), 'recebimento');
  pending = page.waitForResponse(r => r.url().endsWith('/devolver') && r.request().method()==='PATCH');
  await devolucao.getByRole('button',{name:'Confirmar Devolução',exact:true}).click();
  response = await pending;
  expect(response.status()).toBe(200);
  ordem = await response.json();
  persistida = await consultarOrdem(ordem.id);
  conferir('recebimento', recebido, {
    payload: response.request().postDataJSON().assinaturaRecebimentoBase64,
    resposta: ordem.assinaturaRecebimentoBase64,
    persistido: persistida?.assinaturaRecebimentoBase64,
  });
  expect(ordem.simulacao).toBe(true);
  const materiais = await api('materiais', undefined, 'GET');
  expect(materiais).toHaveLength(1);
  expect(materiais[0].nome).toBe('MATERIAL FICTICIO TESTE E2E');
  expect(materiais[0].quantidadeDisponivel).toBe(9); // Devolução zero, consumo fictício de uma unidade.
  await page.goto('/obras');
  const recarregada = (await api(`documentos-internos/comarca/${comarca.id}`,undefined,'GET'));
  expect(recarregada.every(d => d.simulacao && d.status==='REGISTRADO')).toBe(true);
  for (const d of recarregada) {
    const pdf = await context.request.get(`${BASE}/api/documentos-internos/${d.id}/pdf`);
    expect(pdf.status()).toBe(200);
    writeFileSync(testInfo.outputPath(`documento-${d.tipo}.pdf`),await pdf.body());
  }
  await page.screenshot({path:testInfo.outputPath('obra-ficticia-apos-retorno.png')});
});
