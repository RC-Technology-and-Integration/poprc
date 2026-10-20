import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../src/pages/PainelEstoque.jsx', import.meta.url), 'utf8');
const start = source.indexOf('  const valorMovimentacao = (mov) =>');
const end = source.indexOf('\n  const historicoFiltrado', start);
assert.ok(start >= 0 && end > start);
const units = source.slice(source.indexOf('const UNIDADES_MEDIDA = ['), source.indexOf('\nconst unidadePadraoPorControle'));
const context = vm.createContext({});
vm.runInContext(`${units}\nconst formatarNumero = valor => Number(valor || 0).toLocaleString('pt-BR', {maximumFractionDigits: 3});
${source.slice(start, end)}\nthis.formatar = valorMovimentacao;`, context);

for (const [nome, mov, expected] of [
  ['FRACIONADO/UNIDADE', { metragem: 0.125, unidadeMedida: 'UNIDADE' }, '0,125 un'],
  ['FRACIONADO/PACOTE', { metragem: 0.125, unidadeMedida: 'PACOTE' }, '0,125 pacote'],
  ['METRAGEM', { metragem: 0.125, unidadeMedida: 'METRO' }, '0,125 m'],
  ['inteiro', { quantidade: 2, unidadeMedida: 'UNIDADE' }, '2 un'],
  ['peça com comprimento derivado', { quantidade: 2, metragem: 6, unidadeMedida: 'PECA' }, '2 peça'],
  ['bobina rastreável: comprimento e snapshot', { metragem: 100, unidadeMedida: 'BOBINA', unidadeRastreavel: { id: 1 } }, '100 m (bobina)'],
  ['rolo rastreável: comprimento e snapshot', { metragem: 100, unidadeMedida: 'ROLO', unidadeRastreavel: { id: 2 } }, '100 m (rolo)'],
  ['snapshot diferente do cadastro atual', { metragem: 0.125, unidadeMedida: 'PACOTE', material: { unidadeMedida: 'METRO', tipoControle: 'METRAGEM' } }, '0,125 pacote'],
  ['legado decimal sem snapshot', { metragem: 0.125, material: { unidadeMedida: 'METRO' } }, '0,125 (unidade não informada)'],
  ['legado inteiro sem snapshot', { quantidade: 2 }, '2 (unidade não informada)'],
]) {
  test(`histórico: ${nome}`, () => {
    const original = JSON.stringify(mov);
    assert.equal(context.formatar(mov), expected);
    assert.equal(JSON.stringify(mov), original);
  });
}

test('tabela usa a função revista; exportação conserva o snapshot bruto', () => {
  assert.ok(source.includes('{valorMovimentacao(mov)}'));
  assert.ok(source.includes('unidade: mov.unidadeMedida,'));
});
