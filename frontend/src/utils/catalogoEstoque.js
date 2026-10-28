export const TAMANHO_PAGINA_CATALOGO = 50;
export const CHAVE_VISUAL_CATALOGO = 'poprc.estoque.visual.v1';

export function lerPreferenciasCatalogo(storage) {
  try {
    const valor = JSON.parse(storage.getItem(CHAVE_VISUAL_CATALOGO));
    return {
      modo: valor?.modo === 'compacto' ? 'compacto' : 'confortavel',
      fotos: valor?.fotos === true,
    };
  } catch {
    return { modo: 'confortavel', fotos: false };
  }
}

export function indexarSaldosPorMaterial(saldos) {
  const indice = new Map();
  for (const saldo of saldos) {
    const chave = String(saldo.material?.id);
    const grupo = indice.get(chave);
    if (grupo) grupo.push(saldo);
    else indice.set(chave, [saldo]);
  }
  return indice;
}

export function paginarCatalogo(materiais, paginaSolicitada) {
  const totalPaginas = Math.max(1, Math.ceil(materiais.length / TAMANHO_PAGINA_CATALOGO));
  const pagina = Math.max(0, Math.min(totalPaginas - 1, Number.isInteger(paginaSolicitada) ? paginaSolicitada : 0));
  const inicio = pagina * TAMANHO_PAGINA_CATALOGO;
  return {
    pagina, totalPaginas,
    inicio: materiais.length ? inicio + 1 : 0,
    fim: Math.min(inicio + TAMANHO_PAGINA_CATALOGO, materiais.length),
    itens: materiais.slice(inicio, inicio + TAMANHO_PAGINA_CATALOGO),
  };
}
