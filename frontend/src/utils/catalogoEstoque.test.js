import test from 'node:test';
import assert from 'node:assert/strict';
import { indexarSaldosPorMaterial, paginarCatalogo, lerPreferenciasCatalogo } from './catalogoEstoque.js';

test('paginação limita 5.000 registros e não muda ordem nem conteúdo', () => {
  const materiais = Array.from({length:5000}, (_,id)=>({id}));
  const antes = JSON.stringify(materiais);
  const primeira = paginarCatalogo(materiais,0);
  const ultima = paginarCatalogo(materiais,99);
  assert.equal(primeira.itens.length,50);
  assert.equal(ultima.itens[0],materiais[4950]);
  assert.equal(ultima.fim,5000);
  assert.equal(JSON.stringify(materiais),antes);
});
test('paginação ajusta página fora da faixa após reduzir os resultados', () => {
  const resultado=paginarCatalogo([{id:1}],99);
  assert.equal(resultado.pagina,0);
  assert.equal(resultado.inicio,1);
  assert.equal(resultado.fim,1);
});
test('catálogo vazio e página inválida não apresentam intervalo fictício', () => {
  assert.deepEqual(paginarCatalogo([],NaN),{pagina:0,totalPaginas:1,inicio:0,fim:0,itens:[]});
  assert.equal(paginarCatalogo([1],-3).pagina,0);
});
test('índice mantém os saldos, unidades e reservas originais em vários depósitos', () => {
  const saldos=[{id:1,material:{id:2},quantidadeDisponivel:10,quantidadeReservada:3},{id:2,material:{id:'2'},metragemDisponivel:.125},{id:3,material:{id:8}}];
  const antes=JSON.stringify(saldos),indice=indexarSaldosPorMaterial(saldos);
  for(const id of [2,8,99])assert.deepEqual(indice.get(String(id))||[],saldos.filter(s=>String(s.material.id)===String(id)));
  assert.equal(indice.get('2')[0],saldos[0]);
  assert.equal(JSON.stringify(saldos),antes);
});
test('preferências inválidas ou armazenamento bloqueado não impedem abrir o estoque', () => {
  for(const storage of [{getItem(){throw Error('bloqueado')}},{getItem:()=>'{erro'}, {getItem:()=>null}, {getItem:()=>'{"modo":"outro","fotos":"true"}'}]) {
    assert.deepEqual(lerPreferenciasCatalogo(storage),{modo:'confortavel',fotos:false});
  }
});
test('preferência válida conserva apenas modo e exibição de fotos', () => {
  assert.deepEqual(lerPreferenciasCatalogo({getItem:()=>'{"modo":"compacto","fotos":true,"saldo":300}'}),{modo:'compacto',fotos:true});
});
