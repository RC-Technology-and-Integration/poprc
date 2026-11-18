import { expect, test } from '@playwright/test';
import { createHash } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const A = [24, 28, 94, 28];
const B = [120, 76, 212, 76];
const C = [36, 106, 106, 106];
const sha = data => createHash('sha256').update(data).digest('hex');
test.use({ viewport: { width: 1280, height: 900 } });

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    const BrowserImage = window.Image;
    const fila = [];
    window.replay = fila;
    window.Image = class extends BrowserImage {
      set onload(callback) {
        this.addEventListener('load', event => {
          fila.push(() => callback.call(this, event));
        }, { once: true });
      }
    };
  });
  await page.goto('http://127.0.0.1:5177/tests/fixtures/signature-box/');
  await expect(campo(page)).toBeVisible();
});

function campo(page, papel = 'Conferente') {
  return page.getByRole('region', { name: papel }).locator('canvas');
}

async function aguardarFila(page, quantidade) {
  await expect.poll(() => page.evaluate(() => window.replay.length)).toBe(quantidade);
}

async function liberar(page, indice) {
  await page.evaluate(i => window.replay[i](), indice);
}

async function receber(page, campo, png) {
  await page.evaluate(({ campo, png }) => window.ensaio.receber(campo, png), { campo, png });
}

async function mouse(page, campo, linha) {
  const r = await campo.boundingBox();
  const [x1, y1, x2, y2] = linha;
  await page.mouse.move(r.x + x1, r.y + y1);
  await page.mouse.down();
  // Uma única entrega de mousemove por linha torna o raster comparável
  // pixel a pixel à referência. A jornada completa continua usando cinco.
  await page.mouse.move(r.x + x2, r.y + y2);
  await page.mouse.up();
}

async function observar(campo, tipo = 'mouseup') {
  await campo.evaluate((el, event) => {
    el.capturas = [];
    el.addEventListener(event, e => {
      if (e.isTrusted) el.capturas.push(el.toDataURL());
    }, true);
  }, tipo);
}

async function ler(campo) {
  return campo.evaluate(el => el.toDataURL());
}

async function captura(campo, indice = -1) {
  return campo.evaluate((el, i) => el.capturas.at(i), indice);
}

async function referencia(campo, { linhas = [], imagem } = {}) {
  return campo.evaluate(async (el, { linhas, imagem }) => {
    const outro = document.createElement('canvas');
    outro.width = el.width;
    outro.height = el.height;
    const ctx = outro.getContext('2d');
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, outro.width, outro.height);
    if (imagem) {
      const png = document.createElement('img');
      png.src = imagem;
      await png.decode();
      ctx.drawImage(png, 0, 0, outro.width, outro.height);
    }
    ctx.scale(devicePixelRatio, devicePixelRatio);
    ctx.strokeStyle = '#0f172a';
    ctx.lineCap = 'round';
    ctx.lineWidth = 2;
    for (const [x1, y1, x2, y2] of linhas) {
      ctx.beginPath();
      ctx.moveTo(x1, y1);
      ctx.lineTo(x2, y2);
      ctx.stroke();
    }
    return outro.toDataURL();
  }, { linhas, imagem });
}

async function registrar(page, info, imagens) {
  const provas = {};
  for (const [nome, url] of Object.entries(imagens)) {
    const png = Buffer.from(url.split(',')[1], 'base64');
    writeFileSync(info.outputPath(`${nome}.png`), png);
    const { tamanho, rgba, pixels } = await page.evaluate(async url => {
      const img = document.createElement('img');
      img.src = url;
      await img.decode();
      const canvas = document.createElement('canvas');
      canvas.width = img.width;
      canvas.height = img.height;
      const ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0);
      const { data } = ctx.getImageData(0, 0, img.width, img.height);
      let pixels = 0;
      for (let i = 0; i < data.length; i += 4) {
        if (data[i] !== 255 || data[i+1] !== 255 || data[i+2] !== 255) pixels++;
      }
      // Hash RGBA é calculado no Node para funcionar também em contextos de teste.
      return { tamanho: [img.width, img.height], rgba: Array.from(data), pixels };
    }, url);
    provas[nome] = { tamanho, pixels, png: sha(png), rgba: sha(Buffer.from(rgba)) };
  }
  writeFileSync(info.outputPath('comparacao.json'), JSON.stringify(provas, null, 2));
  return provas;
}

test('captura nativa preserva o traço com hidratação pendente', async ({page}, info) => {
  const c = campo(page);
  await receber(page, 0, await referencia(c, { linhas: [C] }));
  await aguardarFila(page, 1);
  await observar(c);
  await mouse(page, c, A);
  const nativa = await captura(c);
  const vivo = await ler(c);
  const estado = await page.evaluate(() => window.ensaio.valores[0]);
  await liberar(page, 0);
  const aposCallback = await ler(c);
  const esperado = await referencia(c, { linhas: [A] });
  const prova = await registrar(page, info, { nativa, vivo, estado, aposCallback, esperado });
  expect(prova.nativa.pixels).toBeGreaterThan(0);
  expect(prova.nativa.rgba).toBe(prova.esperado.rgba);
  expect(nativa).toBe(estado);
  expect(vivo).toBe(nativa);
  expect(aposCallback).toBe(nativa);
  await aguardarFila(page, 1);
});

test('todos os traços são preservados nos dois papéis após o retorno da prop', async ({page}, info) => {
  const c = campo(page);
  const r = campo(page, 'Retirante');
  await observar(c);
  await observar(r);
  for (const [canvas, linha] of [[c, A], [c, B], [r, B]]) {
    await mouse(page, canvas, linha);
  }
  const conferente = await captura(c);
  const retirante = await captura(r);
  const prova = await registrar(page, info, { conferente, retirante,
    esperadoConferente: await referencia(c, { linhas: [A, B] }),
    esperadoRetirante: await referencia(r, { linhas: [B] }),
  });
  expect(prova.conferente.rgba).toBe(prova.esperadoConferente.rgba);
  expect(prova.retirante.rgba).toBe(prova.esperadoRetirante.rgba);
  expect(conferente).toBe(await ler(c));
  expect(retirante).toBe(await ler(r));
  expect(await page.evaluate(() => window.ensaio.valores)).toEqual([conferente, retirante]);
  await aguardarFila(page, 0);
  expect(conferente).not.toBe(retirante);
});

test('novo traço acumula o anterior mesmo com callback pendente', async ({page}, info) => {
  const c = campo(page);
  await receber(page, 0, await referencia(c, { linhas: [C] }));
  await aguardarFila(page, 1);
  await observar(c);
  await mouse(page, c, A);
  await mouse(page, c, B);
  const primeiro = await captura(c, 0);
  const segundo = await captura(c, 1);
  const publicado = await page.evaluate(() => window.ensaio.valores[0]);
  await liberar(page, 0);
  const aposAntigo = await ler(c);
  const esperado = await referencia(c, { linhas: [A, B] });
  const prova = await registrar(page, info, { primeiro, segundo, publicado, aposAntigo, esperado });
  expect(publicado).toBe(segundo);
  expect(prova.primeiro.pixels).toBeGreaterThan(0);
  expect(prova.segundo.rgba, 'O segundo término deve conservar A e B').toBe(prova.esperado.rgba);
  expect(aposAntigo).toBe(segundo);
});

test('callback antigo entregue por último não deve apagar traço novo', async ({page}, info) => {
  const c = campo(page);
  const antigo = await referencia(c, { linhas: [C] });
  const externo = await referencia(c, { linhas: [A] });
  await receber(page, 0, antigo);
  await aguardarFila(page, 1);
  await receber(page, 0, externo);
  await aguardarFila(page, 2);
  await liberar(page, 1);
  expect(await ler(c)).toBe(externo);
  await observar(c);
  await mouse(page, c, B);
  const novo = await ler(c);
  await liberar(page, 0);
  const aposAntigo = await ler(c);
  const esperado = await referencia(c, { linhas: [A, B] });
  const prova = await registrar(page, info, {
    novo, aposAntigo, externo, antigo, esperado, segundaCaptura: await captura(c),
  });
  expect(prova.novo.rgba).toBe(prova.esperado.rgba);
  expect(prova.aposAntigo.rgba, 'O callback antigo deve preservar o resultado mais recente').toBe(prova.novo.rgba);
  expect(aposAntigo).toBe(await captura(c));
  expect(await page.evaluate(() => window.ensaio.valores[0])).toBe(aposAntigo);
});

test('Limpar deve invalidar callback que ainda está pendente', async ({page}, info) => {
  const c = campo(page);
  const r = campo(page, 'Retirante');
  const imagemAntiga = await referencia(c, { linhas: [A] });
  const imagemRetirante = await referencia(r, { linhas: [B] });
  await receber(page, 0, imagemAntiga);
  await aguardarFila(page, 1);
  await receber(page, 1, imagemRetirante);
  await aguardarFila(page, 2);
  await page.getByRole('region', { name: 'Conferente' }).getByRole('button', { name: 'Limpar' }).click();
  const limpo = await ler(c);
  expect(await page.evaluate(() => window.ensaio.valores[0])).toBe('');
  await liberar(page, 0);
  await liberar(page, 1);
  const aposAntigo = await ler(c);
  const retirante = await ler(r);
  const prova = await registrar(page, info, { limpo, aposAntigo, imagemAntiga, retirante, imagemRetirante });
  expect(prova.limpo.pixels).toBe(0);
  expect(aposAntigo, 'O callback antigo não deve restaurar imagem apagada').toBe(limpo);
  expect(retirante).toBe(imagemRetirante);
  expect(await page.evaluate(() => window.ensaio.valores)).toEqual(['', imagemRetirante]);
});

test('fechar e reabrir isola callbacks e carrega o valor inicial no papel correto', async ({page}, info) => {
  const c = campo(page);
  const inicial = await referencia(c, { linhas: [A] });
  await receber(page, 0, inicial);
  await aguardarFila(page, 1);
  const desmontado = await c.elementHandle();
  const antesDeFechar = await ler(c);
  await page.evaluate(() => window.ensaio.fechar());
  await expect(c).toHaveCount(0);
  await page.evaluate(png => window.ensaio.reabrir(['', png]), inicial);
  await aguardarFila(page, 2);
  await liberar(page, 1);
  await liberar(page, 0);
  expect(await desmontado.evaluate(el => el.toDataURL())).toBe(antesDeFechar);
  await desmontado.dispose();
  const conferente = await ler(c);
  const retirante = await ler(campo(page, 'Retirante'));
  const prova = await registrar(page, info, { inicial, conferente, retirante });
  expect(prova.conferente.pixels).toBe(0);
  expect(retirante).toBe(inicial);
});

test('valor externo vazio limpa um bitmap hidratado sem publicar outro valor', async ({page}, info) => {
  const c = campo(page);
  const imagem = await referencia(c, { linhas: [A] });
  const branco = await referencia(c);
  await receber(page, 0, imagem);
  await aguardarFila(page, 1);
  await liberar(page, 0);
  expect(await ler(c)).toBe(imagem);
  await receber(page, 0, null);
  await expect.poll(() => ler(c)).toBe(branco);
  const atual = await ler(c);
  await registrar(page, info, { imagem, branco, atual });
  expect(atual).toBe(branco);
  expect(await page.evaluate(() => window.ensaio.valores[0])).toBeNull();
});

test.describe('DPR 2', () => {
  test.use({ deviceScaleFactor: 2, hasTouch: true });

  test('resize preserva pixels e prop publicada em DPR 2 e DPR 1', async ({page}, info) => {
    const c = campo(page);
    await observar(c);
    await mouse(page, c, A);
    const original = await captura(c);
    await page.evaluate(() => window.ensaio.largura(384));
    await expect.poll(() => c.evaluate(el => el.width)).toBe(768);
    const aposResize = await ler(c);
    const refResize = await referencia(c, { imagem: original });
    const cd = await page.context().newCDPSession(page);
    // Neste Chromium, o override só de DPR altera o getter sem notificar o
    // layout. A pequena mudança de viewport dispara a reavaliação da mídia.
    await cd.send('Emulation.setDeviceMetricsOverride', {
      width: 1279, height: 900, deviceScaleFactor: 1, mobile: false,
    });
    await expect.poll(() => c.evaluate(el => el.width)).toBe(384);
    const aposDpr = await ler(c);
    const refDpr = await referencia(c, { imagem: aposResize });
    const prova = await registrar(page, info, { original, aposResize, refResize, aposDpr, refDpr });
    expect(prova.original.tamanho).toEqual([608, 256]);
    expect(prova.aposResize.tamanho).toEqual([768, 256]);
    expect(prova.aposDpr.tamanho).toEqual([384, 128]);
    expect(prova.aposResize.rgba).toBe(prova.refResize.rgba);
    expect(prova.aposDpr.rgba).toBe(prova.refDpr.rgba);
    expect(prova.aposDpr.pixels).toBeGreaterThan(0);
    expect(await page.evaluate(() => window.ensaio.valores[0])).toBe(original);
  });

  test('toque preserva os dois traços em pixels e na imagem publicada', async ({page}, info) => {
    const c = campo(page);
    await receber(page, 0, await referencia(c, { linhas: [C] }));
    await aguardarFila(page, 1);
    await observar(c, 'touchend');
    const cd = await page.context().newCDPSession(page);
    for (const [x1, y1, x2, y2] of [A, B]) {
      const r = await c.boundingBox();
      await cd.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: r.x+x1, y: r.y+y1 }] });
      await cd.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: r.x+x2, y: r.y+y2 }] });
      await cd.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    }
    await liberar(page, 0);
    const nativa = await captura(c);
    const vivo = await ler(c);
    const estado = await page.evaluate(() => window.ensaio.valores[0]);
    const prova = await registrar(page, info, { nativa, vivo, estado,
      esperado: await referencia(c, { linhas: [A, B] }),
    });
    expect(nativa).toBe(estado);
    expect(vivo).toBe(estado);
    expect(prova.nativa.rgba).toBe(prova.esperado.rgba);
  });
});
