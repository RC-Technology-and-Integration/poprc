import { test, expect } from '@playwright/test';
import { writeFileSync } from 'node:fs';

const BASE = 'http://127.0.0.1:5177';
const MARCA = 'TESTE — SEM VALIDADE OPERACIONAL';
test.use({ baseURL: BASE, viewport: { width: 1280, height: 900 } });
test.setTimeout(180_000);

// Eventos reais de mouse: escreve TESTE por traços no canvas do fluxo normal.
// Não chama fillText, não preenche base64 e não injeta assinaturas no banco.
async function desenharTeste(page, canvas, deslocamento = 0) {
  await canvas.scrollIntoViewIfNeeded();
  const box = await canvas.boundingBox();
  const strokes = [
    [[0,0],[26,0]], [[13,0],[13,42]],
    [[40,0],[40,42],[66,42]], [[40,0],[66,0]], [[40,21],[62,21]],
    [[108,0],[82,0],[82,21],[108,21],[108,42],[82,42]],
    [[122,0],[148,0]], [[135,0],[135,42]],
    [[162,0],[162,42],[188,42]], [[162,0],[188,0]], [[162,21],[185,21]],
  ];
  for (const stroke of strokes) {
    await page.mouse.move(box.x + 12 + deslocamento + stroke[0][0], box.y + 28 + stroke[0][1]);
    await page.mouse.down();
    for (const [x,y] of stroke.slice(1)) await page.mouse.move(box.x + 12 + deslocamento + x, box.y + 28 + y, { steps: 5 });
    await page.mouse.up();
  }
  return canvas.evaluate(el => el.toDataURL('image/png'));
}

test('nova OS fictícia captura TESTE nos documentos, vistoria, retirada e devolução', async ({ page, context }, testInfo) => {
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

  const assinarDocumento = async (tipo) => {
    for (let i=0; i<3; i++) {
      await documento.getByRole('button', {name:'Assinar TESTE',exact:true}).first().click();
      const modal = page.getByRole('dialog').last();
      await expect(modal.getByRole('button',{name:'Confirmar',exact:true})).toBeDisabled();
      await expect(modal.getByText(/Desenhe a palavra TESTE/)).toBeVisible();
      const captured = await desenharTeste(page, modal.locator('canvas'), i*3);
      await page.screenshot({path:testInfo.outputPath(`${tipo}-papel-${i+1}.png`)});
      const pending = page.waitForResponse(r => r.url().includes('/assinaturas/') && r.request().method() === 'PATCH');
      await modal.getByRole('button',{name:'Confirmar',exact:true}).click();
      const response = await pending;
      expect(response.status()).toBe(200);
      expect(response.request().postDataJSON().assinaturaBase64).toBe(captured);
      const saved = await response.json();
      const field = ['assinaturaTecnicoBase64','assinaturaGestorBase64','assinaturaGerenteBase64'][i];
      expect(saved[field]).toBe(captured); // Backend não pode substituir a captura.
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
  await assinarDocumento('encerramento');

  await page.getByText('Coletar Assinatura',{exact:true}).click();
  const modalVistoria = page.getByRole('dialog',{name:/Coletar Assinatura/});
  await expect(modalVistoria.getByRole('button',{name:'Confirmar',exact:true})).toBeDisabled();
  const capturaVistoria = await desenharTeste(page, modalVistoria.locator('canvas'));
  const aguardandoVistoria = page.waitForResponse(r => r.url().includes('/vistoria/assinatura') && r.request().method()==='PATCH');
  await modalVistoria.getByRole('button',{name:'Confirmar',exact:true}).click();
  expect((await (await aguardandoVistoria).json()).assinaturaBase64).toBe(capturaVistoria);
  await expect(modalVistoria).not.toBeVisible();
  const fotoTeste = Buffer.from(capturaVistoria.split(',')[1], 'base64');
  await api(`comarcas/${comarca.id}/vistoria/foto`, undefined, 'POST', {foto:{name:'TESTE.png',mimeType:'image/png',buffer:fotoTeste}});
  await api(`comarcas/${comarca.id}/avancar-etapa`, {}, 'PATCH');

  await page.goto('/estoque');
  await page.getByRole('button',{name:'Executar Retirada',exact:true}).click();
  const retirada = page.getByRole('dialog',{name:/Executar Retirada/});
  await expect(retirada.locator('canvas')).toHaveCount(2);
  const capturas = [await desenharTeste(page,retirada.locator('canvas').nth(0)), await desenharTeste(page,retirada.locator('canvas').nth(1),3)];
  await page.screenshot({path:testInfo.outputPath('or-retirada-capturada.png')});
  let pending = page.waitForResponse(r => r.url().endsWith('/executar') && r.request().method()==='PATCH');
  await retirada.getByRole('button',{name:'Confirmar Retirada',exact:true}).click();
  let response = await pending;
  expect(response.status()).toBe(200);
  let ordem = await response.json();
  expect(ordem.assinaturaConferenteBase64).toBe(capturas[0]);
  expect(ordem.assinaturaRetiranteBase64).toBe(capturas[1]);
  await expect(retirada).not.toBeVisible();
  await page.getByRole('button',{name:'Registrar Devolução',exact:true}).click();
  const devolucao = page.getByRole('dialog',{name:/Devolu/});
  const recebido = await desenharTeste(page,devolucao.locator('canvas'));
  pending = page.waitForResponse(r => r.url().endsWith('/devolver') && r.request().method()==='PATCH');
  await devolucao.getByRole('button',{name:'Confirmar Devolução',exact:true}).click();
  response = await pending;
  expect(response.status()).toBe(200);
  ordem = await response.json();
  expect(ordem.assinaturaRecebimentoBase64).toBe(recebido);
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
