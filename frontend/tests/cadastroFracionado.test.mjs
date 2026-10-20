import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import React from 'react';
import { transformWithOxc } from 'vite';

// Actual submit handler and JSX. This is not a complete browser journey.
const source = readFileSync(process.env.PAINEL_ESTOQUE_FONTE_TESTE
  || new URL('../src/pages/PainelEstoque.jsx', import.meta.url), 'utf8');
const start = source.indexOf('  const handleSubmitNovoMaterial = async (e) => {');
const end = source.indexOf('\n  const handleSubmitEntrada', start);
assert.ok(start >= 0 && end > start);
const handler = source.slice(start, end);
const changeStart = source.indexOf('  const handleNovoMaterialChange = (e) => {');
const changeEnd = source.indexOf('\n  const handleNovoMaterialFotoChange', changeStart);
assert.ok(changeStart >= 0 && changeEnd > changeStart);
const changeHandler = source.slice(changeStart, changeEnd);
const input = [...source.matchAll(/<input\b[\s\S]*?\/>/g)].map(([jsx]) => jsx)
  .find(jsx => jsx.includes('"quantidadeDisponivel"') && jsx.includes('name='));
assert.ok(input);
const labelStart = source.lastIndexOf('<label', source.indexOf(input));
const label = source.slice(labelStart, source.indexOf('</label>', labelStart) + 8);

async function submit(tipoControle, unidadeMedida, valor, materialEmEdicao = null) {
  const calls = [], errors = [];
  const context = {
    React, materialEmEdicao,
    novoMaterialData: { nome: 'TESTE SINTETICO', partNumber: 'TESTE-CAD', tipoControle, unidadeMedida,
      quantidadeDisponivel: '0', metragemDisponivel: '0', comprimentoPorPeca: '3', custoMedio: '8', estoqueMinimo: '0' },
    setNovoMaterialData: update => { context.novoMaterialData = update(context.novoMaterialData); },
    api: { post: async (...args) => calls.push(['POST', ...args]), put: async (...args) => calls.push(['PUT', ...args]) },
    setError: error => errors.push(error), getApiErrorMessage: error => error.message,
    setSuccessMessage() {}, setTimeout() {}, handleCloseModal() {}, fetchData() {}, console: { error() {} },
  };
  vm.createContext(context);
  vm.runInContext(changeHandler, context);
  if (!materialEmEdicao) {
    const field = vm.runInContext((await transformWithOxc(`(${input})`, 'cadastro.jsx', { jsx: { runtime: 'classic' } })).code, context);
    field.props.onChange({target: {name: field.props.name, value: valor}});
  }
  await vm.runInContext(`${handler}\nhandleSubmitNovoMaterial({preventDefault(){}})`, context);
  return { calls, errors, context };
}

for (const [tipo, unidade, valor, decimal] of [
  ['FRACIONADO', 'UNIDADE', '0.125', true], ['FRACIONADO', 'PACOTE', '0.125', true],
  ['FRACIONADO', 'UNIDADE', '0', true], ['METRAGEM', 'METRO', '0.125', true],
  ['UNIDADE', 'UNIDADE', '2', false], ['PECA_COM_COMPRIMENTO', 'PECA', '2', false],
]) {
  test(`cadastro ${tipo}/${unidade}/${valor}: eixo, unidade e campo inicial`, async () => {
    const { calls, errors, context } = await submit(tipo, unidade, valor);
    assert.deepEqual(errors, []);
    assert.equal(calls.length, 1);
    const [method, url, payload] = calls[0];
    assert.equal(method, 'POST'); assert.equal(url, '/estoque/materiais');
    assert.equal(payload.quantidadeDisponivel, decimal ? 0 : Number(valor));
    assert.equal(payload.metragemDisponivel, decimal ? Number(valor) : 0);
    assert.equal(payload.unidadeMedida, unidade); assert.equal(payload.custoMedio, 8);
    const field = vm.runInContext((await transformWithOxc(`(${input})`, 'cadastro.jsx', { jsx: { runtime: 'classic' } })).code, context);
    assert.equal(field.props.name, decimal ? 'metragemDisponivel' : 'quantidadeDisponivel');
    assert.equal(Number(field.props.min), 0); assert.equal(Number(field.props.step), decimal ? 0.001 : 1);
    assert.equal(field.props.value, valor);
    const caption = vm.runInContext((await transformWithOxc(`(${label})`, 'label.jsx', { jsx: { runtime: 'classic' } })).code, context).props.children;
    assert.equal(caption, tipo === 'METRAGEM' ? 'Metragem inicial' : 'Quantidade inicial');
  });
}

for (const tipo of ['UNIDADE', 'PECA_COM_COMPRIMENTO']) {
  test(`cadastro ${tipo}: fração não é truncada pelo submit`, async () => {
    const { calls, errors } = await submit(tipo, tipo === 'UNIDADE' ? 'UNIDADE' : 'PECA', '1.125');
    assert.equal(calls.length, 0); assert.equal(errors.length, 1);
  });
}

test('edição usa PUT, não cadastra ou cria entrada adicional', async () => {
  const { calls, errors } = await submit('FRACIONADO', 'PACOTE', '0.125', { id: 27 });
  assert.deepEqual(errors, []); assert.equal(calls.length, 1);
  assert.equal(calls[0][0], 'PUT'); assert.equal(calls[0][1], '/estoque/materiais/27');
  assert.ok(source.includes('!materialEmEdicao && !["BOBINA", "ROLO"].includes(novoMaterialData.tipoControle)'));
});
