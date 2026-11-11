const rotas = {
  "/estoque/entrada": "Entrada avulsa",
  "/estoque/ajustes": "Ajuste",
  "/estoque/transferencias": "Transferência parcial",
  "/estoque/unidades-rastreaveis": "Cadastro de bobina ou rolo",
};
const rotaUnidade = /^\/estoque\/unidades-rastreaveis\/([1-9]\d*)\/transferir$/;
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;

export const prefixoIntencoes = (ambiente, funcionario) =>
  `poprc:estoque:intencao:${encodeURIComponent(ambiente)}:${funcionario}:`;

const id = valor => Number.isSafeInteger(valor) && valor > 0;
function cabeDecimal(literal, escala, digitosInteiros) {
  const partes = /^-?(\d+)(?:\.(\d+))?(?:e([+-]?\d+))?$/i.exec(literal);
  if (!partes) return false;
  const fracao = partes[2] ?? "";
  const digitos = (partes[1] + fracao).replace(/^0+/, "");
  if (!digitos) return true;
  const significativos = digitos.replace(/0+$/, "");
  const casas = fracao.length - Number(partes[3] ?? 0) - (digitos.length - significativos.length);
  return Number.isFinite(casas) && casas <= escala && significativos.length - casas <= digitosInteiros;
}
const medida = (valor, literal) => Number.isFinite(valor) && valor > 0 && cabeDecimal(literal, 3, 11);
const texto = valor => typeof valor === "string" && valor.trim().length > 0 && valor.trim().length <= 255;
const opcional = validar => (valor, literal) => valor == null || validar(valor, literal);
const auditoria = { motivo: texto, lancadoPor: texto, autorizadoPor: texto };
const schemas = {
  "/estoque/entrada": {
    campos: {
      materialId: id, funcionarioId: id, localEstoqueId: id,
      quantidade: opcional((valor, literal) => id(valor) && valor <= 2147483647 && cabeDecimal(literal, 0, 10)),
      metragem: opcional(medida),
      custoUnitarioEntrada: opcional((valor, literal) => Number.isFinite(valor) && valor >= 0 && cabeDecimal(literal, 4, 11)),
    },
    validar: p => (p.quantidade != null) !== (p.metragem != null),
  },
  "/estoque/ajustes": {
    campos: {
      materialId: id, localEstoqueId: id, valor: medida,
      tipo: valor => valor === "AJUSTE_POSITIVO" || valor === "AJUSTE_NEGATIVO",
      ...auditoria,
    },
  },
  "/estoque/transferencias": {
    campos: { materialId: id, origemId: id, destinoId: id, valor: medida, ...auditoria },
    validar: p => p.origemId !== p.destinoId,
  },
  "/estoque/unidades-rastreaveis": {
    campos: {
      materialId: id, localEstoqueId: id, codigo: texto, metragemInicial: medida,
      observacao: opcional(valor => typeof valor === "string"),
    },
  },
};
const transferenciaUnidade = { campos: { destinoId: id, ...auditoria } };

function esquemaDaRota(method, url) {
  if (typeof url !== "string") return null;
  if (method === "post" && Object.hasOwn(schemas, url)) return schemas[url];
  const unidade = method === "patch" && rotaUnidade.exec(url);
  return unidade && id(Number(unidade[1])) ? transferenciaUnidade : null;
}

// O servidor recupera uma intenção concluída antes de aplicar as regras atuais do material.
function lerIntencao(raw, ambiente, funcionario, chave) {
  const item = JSON.parse(raw);
  const schema = esquemaDaRota(item?.method, item?.url);
  if (!schema || item.versao !== 1 || typeof ambiente !== "string" || !ambiente ||
      item.ambiente !== ambiente || !id(funcionario) || item.funcionario !== funcionario ||
      typeof chave !== "string" || item.chave !== chave || !uuid.test(chave) ||
      typeof item.corpo !== "string") throw new Error("Intenção inválida");
  const numeros = new Map();
  const corpo = JSON.parse(item.corpo, (campo, valor, contexto) => {
    if (typeof valor === "number" && contexto?.source) numeros.set(campo, contexto.source);
    return valor;
  });
  if (!corpo || typeof corpo !== "object" || Array.isArray(corpo) ||
      Object.keys(corpo).some(campo => !Object.hasOwn(schema.campos, campo)) ||
      Object.entries(schema.campos).some(([campo, validar]) => !validar(corpo[campo], numeros.get(campo))) ||
      schema.validar?.(corpo) === false) throw new Error("Pedido inválido");
  return item;
}

export function listarIntencoes(storage, ambiente, funcionario) {
  const prefixo = prefixoIntencoes(ambiente, funcionario);
  const itens = [];
  let corrompida = false;
  for (const nome of Object.keys(storage).filter(key => key.startsWith(prefixo))) {
    const raw = storage.getItem(nome);
    if (raw === null) continue;
    try {
      itens.push(lerIntencao(raw, ambiente, funcionario, nome.slice(prefixo.length)));
    } catch {
      corrompida = true;
    }
  }
  return { itens, corrompida };
}

export function salvarIntencao(storage, ambiente, funcionario, method, url, data) {
  const chave = crypto.randomUUID();
  const item = { versao: 1, ambiente, funcionario, chave, method, url, corpo: JSON.stringify(data) };
  const raw = JSON.stringify(item);
  lerIntencao(raw, ambiente, funcionario, chave);
  const nome = prefixoIntencoes(ambiente, funcionario) + chave;
  storage.setItem(nome, raw);
  if (storage.getItem(nome) !== raw) throw new Error("Intenção não persistida");
  return item;
}

export function recuperarIntencao(storage, ambiente, funcionario, chave) {
  return lerIntencao(storage.getItem(prefixoIntencoes(ambiente, funcionario) + chave),
    ambiente, funcionario, chave);
}

export function resumirIntencao(item, materiais, locais) {
  const p = JSON.parse(item.corpo);
  const unidade = rotaUnidade.exec(item.url)?.[1];
  const local = id => locais.find(l => l.id === id)?.nome ?? `#${id}`;
  const material = materiais.find(m => m.id === p.materialId)?.nome ?? `material #${p.materialId}`;
  const operacao = p.tipo === "AJUSTE_NEGATIVO" ? "Ajuste negativo"
    : p.tipo === "AJUSTE_POSITIVO" ? "Ajuste positivo" : rotas[item.url] ?? "Transferência integral";
  return [operacao, unidade ? `unidade #${unidade}` : material, p.codigo,
    p.metragem ?? p.quantidade ?? p.valor ?? p.metragemInicial,
    p.custoUnitarioEntrada != null && `custo unitário R$ ${p.custoUnitarioEntrada}`,
    p.origemId && `origem ${local(p.origemId)}`,
    (p.destinoId || p.localEstoqueId) && `depósito ${local(p.destinoId || p.localEstoqueId)}`,
    p.motivo].filter(x => typeof x === "string" || typeof x === "number").join(" · ");
}
