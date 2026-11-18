import { expect } from '@playwright/test';

const tracados = [
  [[0, 0], [26, 0]], [[13, 0], [13, 42]],
  [[40, 0], [40, 42], [66, 42]], [[40, 0], [66, 0]], [[40, 21], [62, 21]],
  [[108, 0], [82, 0], [82, 21], [108, 21], [108, 42], [82, 42]],
  [[122, 0], [148, 0]], [[135, 0], [135, 42]],
  [[162, 0], [162, 42], [188, 42]], [[162, 0], [188, 0]], [[162, 21], [185, 21]],
];

export function campoAssinatura(dialogo, rotulo) {
  return dialogo.locator('label').filter({ hasText: rotulo }).locator('../..');
}

export async function desenharTeste(page, canvas, papel, deslocamento = 0) {
  // O listener roda no canvas, antes de o mouseup chegar ao handler delegado
  // de React. A leitura posterior recupera a captura desse evento, não o bitmap vivo.
  const elemento = await canvas.elementHandle();
  try {
    await elemento.evaluate(el => {
      const capturas = [];
      const aoSoltar = event => {
        if (event.isTrusted && event.button === 0) capturas.push(el.toDataURL('image/png'));
      };
      el.addEventListener('mouseup', aoSoltar, true);
      el.capturaTeste = { capturas, aoSoltar };
    });
    for (const traço of tracados) {
      await canvas.scrollIntoViewIfNeeded();
      const box = await elemento.boundingBox();
      if (!box || box.width < 204 + deslocamento || box.height < 72) {
        throw new Error(`Campo de assinatura sem área para TESTE: ${papel}`);
      }
      const [[x, y], ...pontos] = traço;
      await page.mouse.move(box.x + 12 + deslocamento + x, box.y + 28 + y);
      await page.mouse.down();
      for (const [px, py] of pontos) {
        await page.mouse.move(box.x + 12 + deslocamento + px, box.y + 28 + py, { steps: 5 });
      }
      await page.mouse.up();
    }
    const capturas = await elemento.evaluate(el => el.capturaTeste.capturas);
    expect(capturas, `Um mouseup nativo por traço de ${papel}`).toHaveLength(tracados.length);
    const lacunas = await page.evaluate(async ({ capturas, tracados, deslocamento }) => {
      const faltas = [];
      const bitmap = document.createElement('canvas');
      const ctx = bitmap.getContext('2d', { willReadFrequently: true });
      const dpr = window.devicePixelRatio;
      for (const [indice, captura] of capturas.entries()) {
        const imagem = new Image();
        imagem.src = captura;
        await imagem.decode();
        bitmap.width = imagem.width;
        bitmap.height = imagem.height;
        ctx.drawImage(imagem, 0, 0);
        const { data } = ctx.getImageData(0, 0, bitmap.width, bitmap.height);
        // Checa cada pixel do eixo interno de cada segmento já desenhado.
        // As pontas são excluídas para não depender do antialiasing das junções.
        for (const [traço, pontos] of tracados.slice(0, indice + 1).entries()) {
          for (const [segmento, [x, y]] of pontos.slice(0, -1).entries()) {
            const [fimX, fimY] = pontos[segmento + 1];
            const tamanho = Math.abs(fimX - x) + Math.abs(fimY - y);
            for (let passo = 2; passo <= tamanho - 2; passo++) {
              const px = Math.floor((12 + deslocamento + x + Math.sign(fimX - x) * passo) * dpr);
              const py = Math.floor((28 + y + Math.sign(fimY - y) * passo) * dpr);
              const i = (py * bitmap.width + px) * 4;
              const semTinta = data[i] === 255 && data[i + 1] === 255 && data[i + 2] === 255;
              if (px >= bitmap.width || py >= bitmap.height || data[i + 3] !== 255 || semTinta) {
                faltas.push({ captura: indice + 1, traço: traço + 1, segmento: segmento + 1, px, py });
                break;
              }
            }
          }
        }
      }
      return faltas;
    }, { capturas, tracados, deslocamento });
    expect(lacunas, `Segmentos ausentes no desenho de ${papel}`).toEqual([]);
    return capturas.at(-1);
  } finally {
    await elemento.evaluate(el => {
      el.removeEventListener('mouseup', el.capturaTeste.aoSoltar, true);
      delete el.capturaTeste;
    });
    await elemento.dispose();
  }
}
