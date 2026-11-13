import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';

// Somente a fixture sintética do executor E2E; não modifica a sessão nem repete ações.
export async function diagnosticoSessao(context, nome) {
  const out = join(dirname(process.env.POP_RC_E2E_FIXTURE), 'diagnosticos', nome);
  const rede = [], consoleLogs = [], erros = [];
  let falhou = false;
  const instante = () => new Date().toISOString();
  context.on('request', r => rede.push({ instante: instante(), evento: 'request', metodo: r.method(), url: r.url() }));
  context.on('response', r => rede.push({ instante: instante(), evento: 'response', status: r.status(), url: r.url() }));
  context.on('requestfailed', r => rede.push({ instante: instante(), evento: 'requestfailed', url: r.url(), falha: r.failure() }));
  context.on('page', page => {
    page.on('console', m => consoleLogs.push({ instante: instante(), url: page.url(), tipo: m.type(), texto: m.text() }));
    page.on('pageerror', e => consoleLogs.push({ instante: instante(), url: page.url(), erro: String(e.stack || e) }));
  });
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
  const gravar = (nomeArquivo, dados) => writeFile(join(out, nomeArquivo), JSON.stringify(dados, null, 2));
  return {
    async falha(error) {
      falhou = true;
      try {
        await mkdir(out, { recursive: true });
        await gravar('erro.json', { instante: instante(), nome, erro: String(error.stack || error) });
        const capturas = [context.storageState().then(s => gravar('storage-state.json', s))];
        for (const [i, page] of context.pages().entries()) {
          capturas.push(page.screenshot({ path: join(out, `pagina-${i}.png`), fullPage: true, timeout: 5000 }));
          capturas.push(page.evaluate(async () => ({
            url: location.href, titulo: document.title, texto: document.body.innerText,
            localStorage: Object.fromEntries(Object.entries(localStorage)),
            sessionStorage: Object.fromEntries(Object.entries(sessionStorage)),
            locks: navigator.locks ? await navigator.locks.query() : null,
          })).then(s => gravar(`pagina-${i}-estado.json`, s)));
        }
        capturas.push(context.request.get('/api/auth/me', { timeout: 5000 })
          .then(async r => gravar('auth-me.json', { status: r.status(), corpo: await r.text() })));
        for (const r of await Promise.allSettled(capturas)) if (r.status === 'rejected') erros.push(String(r.reason));
        await gravar('rede.json', rede);
        await gravar('console.json', consoleLogs);
        await gravar('captura-erros.json', erros);
      } catch (e) {
        console.error('Falha ao preservar diagnóstico E2E:', e);
      }
    },
    async encerrar() {
      try {
        await context.tracing.stop(falhou ? { path: join(out, 'trace.zip') } : undefined);
      } catch (e) {
        console.error('Falha ao encerrar trace E2E:', e);
      }
    },
  };
}
