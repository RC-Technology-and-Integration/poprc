import { test, expect } from '@playwright/test';

// Usa a aplicação local, mas nunca o backend: todas as APIs são interceptadas.
const baseURL = process.env.CATALOGO_BASE_URL || 'http://127.0.0.1:5177';
if (!['127.0.0.1', 'localhost'].includes(new URL(baseURL).hostname)) {
  throw new Error('O ensaio de catálogo exige servidor local.');
}
test.use({
  viewport: { width: 1440, height: 1000 },
  ...(process.env.CATALOGO_CHROME_PATH ? { launchOptions: { executablePath: process.env.CATALOGO_CHROME_PATH } } : {}),
});
const materiais = Array.from({ length: 125 }, (_, i) => ({
  id: i + 1, nome: `TESTE Material ${String(i + 1).padStart(4, '0')}`, partNumber: `FIC-${i + 1}`,
  categoria: 'MATERIAL_CONSUMO', tipoControle: i === 0 ? 'FRACIONADO' : 'UNIDADE', unidadeMedida: 'UNIDADE',
  quantidadeDisponivel: i === 0 ? 0 : 10, quantidadeReservada: i === 0 ? 0 : 2,
  metragemDisponivel: i === 0 ? .125 : 0, metragemReservada: 0, custoMedio: 8, estoqueMinimo: 0, ativo: true,
}));
test.beforeEach(async ({ page }) => {
  await page.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url());
    if (url.origin !== baseURL) return route.abort();
    if (!url.pathname.startsWith('/api/')) return route.continue();
    if (request.method() !== 'GET') throw new Error(`Mutação inesperada: ${request.method()} ${url.pathname}`);
    const respostas = {
      '/api/auth/config': { securityEnabled: true, devLoginEnabled: false },
      '/api/auth/csrf': { token: 'FICTICIO' },
      '/api/auth/me': { funcionarioId: 999, nome: 'ADMIN FICTICIO', perfil: 'ADMIN', trocaSenhaObrigatoria: false },
      '/api/estoque/materiais': materiais,
      '/api/estoque/locais': [{ id: 1, nome: 'Depósito fictício' }],
      '/api/estoque/saldos-locais': materiais.map(material => ({
        id: material.id, material, localEstoque: { id: 1, nome: 'Depósito fictício' },
        quantidadeDisponivel: material.quantidadeDisponivel, quantidadeReservada: material.quantidadeReservada,
        metragemDisponivel: material.metragemDisponivel, metragemReservada: 0,
      })),
    };
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(respostas[url.pathname] ?? []) });
  });
  await page.goto(`${baseURL}/estoque`);
  await expect(page.getByRole('button', { name: 'Planilha compacta', exact: true })).toBeVisible();
});

test('catálogo prioriza disponível e valor sem alterar os saldos exibidos', async ({ page }) => {
  await expect(page.locator('[data-testid=catalogo-cards] article')).toHaveCount(50);
  const resumo = page.locator('.catalogo-resumo-rapido').first();
  await expect(resumo).toContainText('0,125 un');
  await expect(resumo).toContainText('R$ 1,00');
  await page.getByRole('button', { name: 'Planilha compacta', exact: true }).click();
  await expect(page.locator('[data-testid=catalogo-cards]')).toHaveCount(0);
  const table = page.locator('[data-testid=catalogo-tabela]');
  expect((await table.locator('thead th').allTextContents()).slice(0, 3).map(t => t.trim()))
    .toEqual(['Produto', 'Disponível', 'Valor em estoque']);
  const cells = table.locator('tbody tr').nth(1).locator('td');
  await expect(cells.nth(1)).toHaveText('0,125 un');
  await expect(cells.nth(2)).toHaveText('R$ 1,00');
});

test('busca reinicia paginação e limpar não restaura página antiga', async ({ page }) => {
  await page.getByRole('button', { name: 'Próxima', exact: true }).click();
  await expect(page.locator('.catalogo-paginacao')).toContainText('51–100');
  const search = page.getByPlaceholder('Nome, part number, fabricante...');
  await search.fill('FIC-125');
  await expect(page.locator('.catalogo-paginacao')).toContainText('1–1 de 1');
  await search.fill('');
  await expect(page.locator('.catalogo-paginacao')).toContainText('1–50 de 125');
});

for (const width of [320, 390]) test(`quantidade e valor visíveis sem rolar horizontalmente em ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 844 });
  await page.getByRole('button', { name: 'Planilha compacta', exact: true }).click();
  const table = page.locator('[data-testid=catalogo-tabela]');
  const value = table.locator('tbody tr').nth(1).locator('td').nth(2);
  expect(await value.evaluate(el => el.getBoundingClientRect().right <= innerWidth)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('button', { name: 'Ativar tema escuro', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  expect(await table.locator('tbody tr').evaluateAll(rows => rows.slice(0, 4).every(row =>
    getComputedStyle(row).backgroundColor.match(/\d+/g).slice(0, 3).every(n => Number(n) < 90)))).toBe(true);
});

test('ferramentas continuam acessíveis por teclado e abrem os formulários existentes', async ({ page }) => {
  const details = page.locator('.estoque-ferramentas');
  const summary = details.locator('summary');
  await summary.focus();
  await expect(summary).toBeFocused();
  expect(await summary.evaluate(el => getComputedStyle(el).outlineStyle)).toBe('solid');
  await page.keyboard.press('Enter');
  await expect(details).toHaveAttribute('open', '');
  for (const name of ['Bobina / Rolo', 'Depósito', 'Entrada de Material']) {
    await expect(details.getByRole('button', { name, exact: true })).toBeVisible();
  }
  await details.getByRole('button', { name: 'Entrada de Material', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('button', { name: 'Fechar modal', exact: true }).click();
  await page.getByRole('button', { name: 'Novo material', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('button', { name: 'Fechar modal', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Nova retirada', exact: true })).toHaveCount(0);
});

test('filtros avançados e dados secundários preservam acesso equivalente', async ({ page }) => {
  await page.locator('.estoque-filtros-avancados summary').click();
  const min = page.getByLabel('Disponível mínimo', { exact: true });
  await min.fill('100');
  await expect(page.locator('[data-testid=catalogo-cards] article')).toHaveCount(0);
  await expect(page.locator('.estoque-filtro-ativo')).toHaveText('Há filtros aplicados');
  await page.getByRole('button', { name: 'Limpar filtros', exact: true }).click();
  await expect(min).toHaveValue('');
  const card = page.locator('[data-testid=catalogo-cards] article').first();
  await card.locator('summary').click();
  await expect(card).toContainText('FIC-1');
  await expect(card.getByText('Reservado', { exact: true })).toBeVisible();
  await expect(card.getByText('Custo médio', { exact: true })).toBeVisible();
  await expect(card.getByRole('button', { name: 'Editar TESTE Material 0001', exact: true })).toBeVisible();
  await page.locator('.estoque-indicadores-extra summary').click();
  await expect(page.getByText('Metragem disponível', { exact: true })).toBeVisible();
});

test('adaptação visual deixa a navegação original das outras páginas intacta', async ({ page }) => {
  await expect(page.locator('.layout-estoque')).toHaveCount(1);
  await page.getByRole('link', { name: 'Contratos', exact: true }).click();
  await expect(page).toHaveURL(/\/contratos$/);
  await expect(page.locator('.layout-estoque')).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'POP RC — Central operacional' })).toHaveCount(0);
  const originalBackground = await page.evaluate(() => {
    const probe = document.createElement('div');
    probe.style.backgroundColor = 'var(--color-slate-950)';
    document.body.append(probe);
    const color = getComputedStyle(probe).backgroundColor;
    probe.remove();
    return color;
  });
  expect(await page.getByRole('complementary', { name: 'Navegação principal' }).evaluate(el => getComputedStyle(el).backgroundColor)).toBe(originalBackground);
  await page.getByRole('button', { name: 'Estoque', exact: false }).click();
  await expect(page.locator('.layout-estoque')).toHaveCount(1);
});

test('estoque crítico mantém indicação visual nos dois modos', async ({ page }) => {
  await page.route('**/api/estoque/materiais', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify([{ ...materiais[1], quantidadeDisponivel: 0, quantidadeReservada: 0 }]),
  }));
  await page.reload();
  const card = page.locator('.estoque-material-critico');
  await expect(card).toBeVisible();
  await expect(card.locator('.catalogo-resumo-rapido').first()).toContainText('0 un');
  await page.getByRole('button', { name: 'Planilha compacta', exact: true }).click();
  await expect(page.locator('.estoque-linha-critica')).toBeVisible();
  await expect(page.locator('.estoque-linha-critica').locator('td').nth(1)).toContainText('0 un');
});


test('subpáginas têm rotas diretas, recarga, seleção e histórico de navegação', async ({ page }) => {
  const links = [['materiais','Materiais e saldos'],['retiradas','Retiradas'],['pendencias','Pendências documentais'],['historico','Histórico'],['integracao','Planilhas e integração']];
  for (const [route,label] of links) {
    await page.goto(`${baseURL}/estoque/${route}`);
    await expect(page.locator('h1')).toHaveText(label);
    await expect(page.getByRole('link',{name:label,exact:true})).toHaveAttribute('aria-current','page');
    await page.reload();await expect(page.locator('h1')).toHaveText(label);
  }
  await page.getByRole('link',{name:'Retiradas',exact:true}).click();
  await page.goBack();await expect(page.locator('h1')).toHaveText('Planilhas e integração');
  await page.goForward();await expect(page.locator('h1')).toHaveText('Retiradas');
  await page.getByRole('button',{name:'Estoque',exact:false}).click();
  await expect(page).toHaveURL(/\/estoque\/materiais$/);
});

test('catálogo não monta histórico, ORs ou integração; pendências não inventam operações',async ({page})=>{
  await expect(page.getByRole('heading',{name:'Histórico de Movimentações',exact:true})).toHaveCount(0);
  await expect(page.getByRole('heading',{name:'Ordens de Retirada',exact:true})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'Importar .xlsx',exact:true})).toHaveCount(0);
  const calls=[];page.on('request',req=>{if(new URL(req.url()).pathname.startsWith('/api/estoque/'))calls.push(req.url())});
  await page.goto(`${baseURL}/estoque/pendencias`);await expect(page.locator('h1')).toHaveText('Pendências documentais');
  await expect(page.getByText('INDISPONÍVEL NO FLUXO ATUAL',{exact:true})).toBeVisible();
  expect(calls).toEqual([]);
  await expect(page.locator('[data-testid=catalogo-cards]')).toHaveCount(0);
  await page.goto(`${baseURL}/estoque/integracao`);
  await page.locator('.estoque-ferramentas summary').click();
  await expect(page.getByRole('button',{name:'Importar .xlsx',exact:true})).toBeVisible();
  await expect(page.getByText('Nenhuma integração automática com a V2 está disponível.',{exact:false})).toBeVisible();
});

test('erro de leitura é explícito; conteúdo vazio mantém navegação',async ({page})=>{
 await page.route('**/api/estoque/materiais',r=>r.fulfill({status:503,contentType:'application/json',body:JSON.stringify({erro:'Falha fictícia de leitura'})}));
 await page.reload();await expect(page.getByText('Falha fictícia de leitura',{exact:false})).toBeVisible();
 await expect(page.getByRole('link',{name:'Histórico',exact:true})).toBeVisible();
});

test('5.000 materiais mantêm paginação; carregamento e vazio são explícitos',async ({page})=>{
 let release;const gate=new Promise(r=>release=r);
 const large=Array.from({length:5000},(_,i)=>({...materiais[1],id:i+1,nome:`TESTE Material ${String(i+1).padStart(4,'0')}`,partNumber:`FIC-${i+1}`}));
 await page.route('**/api/estoque/materiais',async r=>{await gate;await r.fulfill({contentType:'application/json',body:JSON.stringify(large)})});
 await page.reload({waitUntil:'domcontentloaded'});
 await expect(page.getByText('Carregando seção de estoque…',{exact:true})).toBeVisible();release();
 await expect(page.locator('[data-testid=catalogo-cards] article')).toHaveCount(50);
 await expect(page.locator('img[alt^="Foto de"]')).toHaveCount(0);
 await page.getByPlaceholder('Nome, part number, fabricante...').fill('FIC-5000');
 await expect(page.locator('[data-testid=catalogo-cards] article')).toHaveCount(1);
 await page.getByPlaceholder('Nome, part number, fabricante...').fill('INEXISTENTE');
 await expect(page.getByText('Nenhum material encontrado com os filtros aplicados.',{exact:true})).toBeVisible();
});

test('perfil ESTOQUE mantém acesso às subrotas e opções restritas de contrato',async({page})=>{
 await page.route('**/api/auth/me',r=>r.fulfill({contentType:'application/json',body:JSON.stringify({funcionarioId:999,nome:'ESTOQUE FICTÍCIO',perfil:'ESTOQUE',trocaSenhaObrigatoria:false})}));
 await page.reload();await expect(page.locator('h1')).toHaveText('Materiais e saldos');
 await page.getByRole('link',{name:'Retiradas',exact:true}).click();await expect(page.locator('h1')).toHaveText('Retiradas');
 await expect(page.getByRole('button',{name:'Executar OR',exact:true})).toBeVisible();
});

for(const width of [390,320]) test(`menu de estoque funciona por teclado no celular ${width}`,async({page})=>{
 await page.setViewportSize({width,height:844});
 await page.getByRole('button',{name:'Abrir menu',exact:true}).click();
 const link=page.getByRole('link',{name:'Histórico',exact:true});await link.focus();await page.keyboard.press('Enter');
 await expect(page.locator('h1')).toHaveText('Histórico');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 for(const r of ['retiradas','pendencias','integracao']){await page.goto(`${baseURL}/estoque/${r}`);await page.locator('h1').waitFor();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)}
});

test('falha tardia não apresenta saldos parciais e permite repetir a leitura',async({page})=>{
 await page.route('**/api/estoque/saldos-locais',r=>r.fulfill({status:503,contentType:'application/json',body:JSON.stringify({erro:'Falha fictícia de depósitos'})}));
 await page.reload();await expect(page.getByText('Falha fictícia de depósitos',{exact:false})).toBeVisible();
 await expect(page.locator('[data-testid=catalogo-cards]')).toHaveCount(0);
 await expect(page.getByRole('button',{name:'Tentar novamente',exact:true})).toBeVisible();
 await page.unroute('**/api/estoque/saldos-locais');await page.getByRole('button',{name:'Tentar novamente',exact:true}).click();
 await expect(page.locator('[data-testid=catalogo-cards] article')).toHaveCount(50);
});
test('perfil sem permissão não acessa subrota nem consulta estoque',async({page})=>{
 await page.route('**/api/auth/me',r=>r.fulfill({contentType:'application/json',body:JSON.stringify({funcionarioId:999,nome:'TESTE AUDITOR',perfil:'AUDITOR',trocaSenhaObrigatoria:false})}));
 const consultas=[];page.on('request',r=>{if(new URL(r.url()).pathname.startsWith('/api/estoque/'))consultas.push(r.url())});
 await page.reload();await expect(page).not.toHaveURL(/\/estoque/);expect(consultas).toEqual([]);
 await expect(page.getByRole('alert')).toContainText('Acesso negado. Seu perfil não tem permissão');
});


test('submenu recolhido mantém foco, nomes acessíveis e seção ativa', async ({page}) => {
 await page.getByRole('button',{name:'Recolher menu lateral',exact:true}).click();
 // Ativa a modalidade de teclado antes de verificar :focus-visible.
 await page.keyboard.press('Tab');
 const nav=page.locator('aside nav');
 expect(await nav.evaluate(el=>el.scrollWidth<=el.clientWidth)).toBe(true);
 for(const label of ['Materiais e saldos','Retiradas','Pendências documentais','Histórico','Planilhas e integração']) {
  const link=page.getByRole('link',{name:label,exact:true});await link.focus();
  await expect(link).toBeFocused();expect(await link.evaluate(el=>getComputedStyle(el).outlineStyle)).toBe('solid');
  await page.keyboard.press('Enter');await expect(link).toHaveAttribute('aria-current','page');
  expect(await nav.evaluate(el=>el.scrollWidth<=el.clientWidth)).toBe(true);
 }
 await page.getByRole('button',{name:'Expandir menu lateral',exact:true}).click();
 await expect(page.locator('.estoque-menu-label').filter({hasText:'Planilhas e integração'})).toBeVisible();
});

test('custo fracionado usa unidade do material sem mudar o valor',async({page})=>{
 const first=page.locator('[data-testid=catalogo-cards] article').first();
 await first.getByRole('button',{name:'Editar TESTE Material 0001',exact:true}).click();
 const dialog=page.getByRole('dialog');
 await expect(dialog.getByText('Informe o custo médio por un.',{exact:true})).toBeVisible();
 await expect(dialog.locator('input[name="custoMedio"]')).toHaveValue('8');
 await page.keyboard.press('Escape');
 await expect(page.locator('.catalogo-resumo-rapido').first()).toContainText('R$ 1,00');
});
