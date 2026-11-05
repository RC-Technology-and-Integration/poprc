import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams, Link } from 'react-router-dom';
import api, { getApiErrorMessage } from '../services/api';
import { useAuth } from '../contexts/AuthContext';
import './retiradasV2.css';

const BASE = '/ordens-retirada/v2';
const data = valor => valor ? new Date(valor).toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo' }) : '—';
const decimal = m => ['FRACIONADO', 'METRAGEM'].includes(m.tipoControle);
const numero = valor => Number(valor || 0).toLocaleString('pt-BR', { maximumFractionDigits: 3 });
const chave = () => crypto.randomUUID();
const emBranco = { localId: '', osId: '', destinoId: '', destino: '', motivo: '', entregaPor: '', retirante: '', observacoes: '' };
const legivel = s => String(s).replaceAll('_', ' ');

/** Interface real, carregada apenas na seção ativa. Nenhum dado de demonstração. */
export default function RetiradasV2({ secao, materiais = [], locais = [], saldos = [], comarcas = [], aoMudar }) {
  const { usuario } = useAuth();
  const atorId = usuario?.funcionarioId;
  const operador = ['ADMIN', 'ESTOQUE'].includes(usuario?.perfil);
  const admin = usuario?.perfil === 'ADMIN';
  const revisor = ['ADMIN', 'SUPERVISOR_TECNICO'].includes(usuario?.perfil);
  const [params, setParams] = useSearchParams();
  const selecionada = params.get('or');
  const [config, setConfig] = useState(null);
  const [lista, setLista] = useState(null);
  const [pagina, setPagina] = useState(0);
  const [or, setOr] = useState(null);
  const [erro, setErro] = useState('');
  const [ocupado, setOcupado] = useState(false);
  const trava = useRef(false);
  const [tentativa, setTentativa] = useState(null);
  const [form, setForm] = useState(emBranco);
  const [cesta, setCesta] = useState([]);
  const [busca, setBusca] = useState('');
  const [categoria, setCategoria] = useState('');
  const [paginaMateriais, setPaginaMateriais] = useState(0);
  const [osContexto, setOsContexto] = useState(null);
  const [quantidades, setQuantidades] = useState({});
  const [motivo, setMotivo] = useState('');
  const [entregaPor, setEntregaPor] = useState('');
  const [retirante, setRetirante] = useState('');
  const [autorrevisao, setAutorrevisao] = useState(false);
  const [arquivo, setArquivo] = useState(null);
  const [assinatura, setAssinatura] = useState('');
  const novo = params.get('nova') === '1';
  const editar = novo || (params.get('editar') === '1' && or?.fisico === 'RASCUNHO');
  const guardarTentativa = valor => {
    setTentativa(valor);
    const k = `poprc.orv2.intencao.${atorId}`;
    if (valor) sessionStorage.setItem(k, JSON.stringify(valor)); else sessionStorage.removeItem(k);
  };
  const abrir = id => setParams({ or: String(id) });
  const detalhe = async (id, signal) => {
    const r = await api.get(`${BASE}/${id}`, { signal });
    if (signal?.aborted) return r.data;
    setOr(r.data);setEntregaPor(r.data.rascunho.entregaPor || '');setRetirante(r.data.rascunho.retirante || '');
    setQuantidades(Object.fromEntries(r.data.itens.map(i => [i.material_id, r.data.fisico === 'RESERVADA' ? i.solicitado : i.categoria === 'FERRAMENTA' ? i.efetivo : 0])));
    setAutorrevisao(false);setMotivo('');return r.data;
  };
  const carregar = async () => {
    setErro('');
    try { const r = await api.get(BASE, { params: { fila: secao === 'pendencias' ? 'pendencias' : 'retiradas', pagina } });setLista(r.data); }
    catch (e) { setErro(getApiErrorMessage(e)); }
  };
  useEffect(() => {
    let ativa = true;
    api.get(`${BASE}/config`).then(r => { if (ativa) setConfig(r.data); }).catch(e => { if (ativa) setErro(getApiErrorMessage(e)); });
    try { const saved = sessionStorage.getItem(`poprc.orv2.intencao.${atorId}`);if (saved) setTentativa(JSON.parse(saved)); } catch { /* sessão anterior ilegível não envia nada */ }
    return () => { ativa = false; };
  }, [atorId]);
  useEffect(() => { carregar(); }, [pagina, secao]);
  useEffect(() => {
    setArquivo(null);setAssinatura('');
  }, [selecionada, novo]);
  useEffect(() => {
    if (!selecionada) { setOr(null); return; }
    setOr(null);setErro('');
    const controller = new AbortController();
    detalhe(selecionada, controller.signal).catch(e => { if (!controller.signal.aborted) setErro(getApiErrorMessage(e)); });
    return () => controller.abort();
  }, [selecionada]);
  useEffect(() => {
    if (novo) {setForm(emBranco);setCesta([]);setOsContexto(null);}
    else if (or && params.get('editar') === '1') {setForm(or.rascunho);setCesta(or.rascunho.itens);}
  }, [novo, params.get('editar'), or?.id]);
  useEffect(() => {
    setOsContexto(null);
    if (!form.osId) return;
    let ativa = true;
    api.get(`${BASE}/contexto-os/${form.osId}`).then(r => { if (ativa) setOsContexto(r.data); }).catch(e => { if (ativa) setErro(getApiErrorMessage(e)); });
    return () => { ativa = false; };
  }, [form.osId]);
  const enviar = async (t, binario) => {
    if (trava.current) return;
    trava.current = true;setOcupado(true);setErro('');
    try {
      let corpo = t.corpo;
      if (t.multipart) {
        if (!binario) throw new Error('Selecione novamente o mesmo arquivo para repetir o envio. A intenção foi preservada.');
        corpo = new FormData();corpo.append('acao', new Blob([JSON.stringify(t.corpo)], { type: 'application/json' }));corpo.append('arquivo', binario);
      }
      const r = await api.request({ method: t.metodo, url: t.url, data: corpo });
      guardarTentativa(null);setArquivo(null);setAssinatura('');setOr(r.data);abrir(r.data.id);
      if (String(r.data.id) === selecionada) await detalhe(r.data.id);
      await carregar();
      if (/\/(entrega|devolucao|emissoes|revisoes|cancelamento)$/.test(t.url)) aoMudar?.();
    } catch (e) {
      if (e.response?.status === 503 && e.response.data?.codigo === 'ESTOQUE_OCUPADO') {
        setErro('O estoque está ocupado. Aguarde e repita a mesma intenção.');
      } else {
        setErro(getApiErrorMessage(e));
        if (e.response && e.response.status < 500) guardarTentativa(null);
      }
    } finally { trava.current = false;setOcupado(false); }
  };
  const executar = (acao, extra = {}, binario) => {
    if (tentativa || trava.current) return;
    const t = { metodo: 'POST', url: `${BASE}/${or.id}/${acao}`, corpo: { chave: chave(), revisao: or.revisao, versao: or.versao_emitida, motivo, entregaPor, retirante, autorrevisao: false, ...extra }, multipart: !!binario };
    guardarTentativa(t);enviar(t, binario);
  };
  const salvar = e => {
    e.preventDefault();if (tentativa || trava.current) return;
    const corpo = { ...form, osId: form.osId ? Number(form.osId) : null, localId: Number(form.localId), itens: cesta.map(i => ({ ...i, quantidade: Number(i.quantidade) })), revisao: novo ? 0 : or.revisao, chave: chave() };
    const t = { metodo: novo ? 'POST' : 'PATCH', url: novo ? `${BASE}/rascunhos` : `${BASE}/${or.id}/rascunho`, corpo };
    guardarTentativa(t);enviar(t);
  };
  const baixar = async f => {
    setErro('');try {const r = await api.get(`${BASE}/${or.id}/arquivos/${f.id}`, { responseType: 'blob' });const u = URL.createObjectURL(new Blob([r.data], { type: f.mime }));const link = document.createElement('a');link.href = u;link.target = '_blank';link.rel = 'noopener';link.click();setTimeout(() => URL.revokeObjectURL(u), 60000);} catch (e) {setErro(getApiErrorMessage(e, 'Não foi possível abrir o arquivo preservado.'));}
  };
  const saldo = m => {
    const linhas = saldos.filter(s => s.material?.id === m.id && (!form.localId || String(s.localEstoque?.id) === String(form.localId)));
    return linhas.reduce((v,s) => v + Number(decimal(m) ? s.metragemDisponivel : s.quantidadeDisponivel), 0);
  };
  const disponivel = m => saldos.filter(s => s.material?.id === m.id && (!form.localId || String(s.localEstoque?.id) === String(form.localId))).reduce((v,s) => v + Number(decimal(m) ? s.metragemDisponivel : s.quantidadeDisponivel) - Number(decimal(m) ? s.metragemReservada : s.quantidadeReservada), 0);
  const elegiveis = useMemo(() => materiais.filter(m => !['BOBINA','ROLO'].includes(m.tipoControle) && (!form.osId || osContexto?.itens.some(i => i.material_id === m.id)) && (!categoria || m.categoria === categoria) && `${m.nome} ${m.partNumber} ${m.id}`.toLowerCase().includes(busca.toLowerCase())), [materiais, form.osId, osContexto, categoria, busca]);
  const adicionar = m => { if (!cesta.some(i => i.materialId === m.id)) setCesta([...cesta, { materialId: m.id, materialItemId: form.osId ? osContexto.itens.find(i => i.material_id === m.id).material_item_id : null, quantidade: 1 }]); };
  const campo = (nome, label, obrigatorio = true) => <label>{label}<input required={obrigatorio} maxLength={nome === 'destinoId' ? 100 : 2000} value={form[nome] || ''} onChange={e => setForm({ ...form, [nome]: e.target.value })} /></label>;
  const vetor = or?.itens.map(i => ({ materialId: i.material_id, quantidade: Number(quantidades[i.material_id] ?? 0) }));
  const acoesBloqueadas = ocupado || !!tentativa;
  return <section className="estoque-catalogo retiradas-v2" aria-label="Novas retiradas e comprovantes">
    {editar && <button className="r2-cesta-atalho" type="button" onClick={() => { const el=document.getElementById('r2-selecionados');el?.scrollIntoView({block:'start'});el?.focus(); }}>Rever selecionados ({cesta.length})</button>}
    <div className="estoque-heading"><div><p className="estoque-eyebrow">ENTREGA FÍSICA / DOCUMENTAÇÃO</p><h2>{secao === 'pendencias' ? 'Comprovantes pendentes' : secao === 'historico' ? 'Histórico das novas ORs' : 'Retiradas com comprovação posterior'}</h2></div>
      {operador && secao === 'retiradas' && <button className="estoque-primary" disabled={!config?.habilitado || acoesBloqueadas} onClick={() => setParams({ nova: '1' })}>Nova retirada</button>}</div>
    {config && !config.habilitado && <p role="status">Novas criações e emissões desabilitadas pelo servidor. Entregas autorizadas, devoluções e obrigações documentais existentes continuam acessíveis.</p>}
    {config && <p className="r2-aviso">{config.calendario.aviso} Prazos: 3 dias úteis, fim do dia em America/Sao_Paulo. Atraso não altera saldos.</p>}
    {erro && <div className="r2-erro" role="alert">{erro} <button type="button" onClick={() => { carregar();if (or) detalhe(or.id).catch(e => setErro(getApiErrorMessage(e))); }}>Atualizar dados</button></div>}
    {tentativa && <div className="r2-aviso" role="status">Intenção preservada. Uma falha de resposta não comprova que a operação falhou. {tentativa.multipart && <label>Mesmo comprovante<input type="file" accept="application/pdf,image/png,image/jpeg" onChange={e => setArquivo(e.target.files[0])} /></label>}<button className="estoque-secondary" disabled={ocupado} onClick={() => enviar(tentativa, arquivo)}>Consultar/repetir a mesma intenção</button></div>}
    {editar ? <form onSubmit={salvar} className="r2-editor"><h3>{novo ? 'Preparar retirada' : `Editar ${or.numero}`}</h3><p>Rascunho não cria nova reserva. Bobinas e rolos permanecem no fluxo existente.</p>
      <fieldset disabled={acoesBloqueadas}><legend>Contexto e responsáveis</legend><div className="r2-campos">
        <label>Depósito único<select required value={form.localId} onChange={e => setForm({ ...form, localId: e.target.value })}><option value="">Escolha o depósito</option>{locais.filter(l => l.ativo).map(l => <option key={l.id} value={l.id}>{l.nome}</option>)}</select></label>
        <label>OS ou saída avulsa<select value={form.osId || ''} onChange={e => { setForm({ ...form, osId: e.target.value });setCesta([]); }}><option value="">Avulsa — emissão exige autorização ADMIN</option>{comarcas.filter(c => c.ordemServico?.id && (config?.todasOsHabilitadas || config?.osHabilitadas.includes(c.ordemServico.id))).map(c => <option key={c.id} value={c.ordemServico.id}>{c.ordemServico.numeroOs} · {c.nomeComarca}</option>)}</select></label>
        {campo('destinoId','Identificador do destino')}{campo('destino','Destino / obra')}{campo('motivo','Motivo da retirada')}{campo('entregaPor','Quem entrega / confere')}{campo('retirante','Quem retira')}{campo('observacoes','Observações / cronograma', false)}
      </div></fieldset>
      <div className="r2-selecao"><div><h3>Selecionar materiais</h3><div className="r2-campos"><label>Buscar nome / código<input value={busca} onChange={e => {setBusca(e.target.value);setPaginaMateriais(0);}} /></label><label>Categoria<select value={categoria} onChange={e => {setCategoria(e.target.value);setPaginaMateriais(0);}}><option value="">Todas</option><option value="MATERIAL_CONSUMO">Consumo</option><option value="FERRAMENTA">Ferramentas</option></select></label></div>
        {elegiveis.slice(paginaMateriais * 12, paginaMateriais * 12 + 12).map(m => <article key={m.id} className="r2-material"><div><strong>{m.nome}</strong><small>{m.partNumber} · {m.unidadeMedida}</small></div><div><b>{numero(disponivel(m))} livre</b><small>{numero(saldo(m))} físico no depósito</small></div><button type="button" className="estoque-secondary" disabled={acoesBloqueadas || cesta.some(i => i.materialId === m.id) || !form.localId} onClick={() => adicionar(m)}>Selecionar {m.nome}</button></article>)}
        {!elegiveis.length && <p>Nenhum material elegível encontrado.</p>}<div className="r2-botoes"><button type="button" disabled={!paginaMateriais} onClick={() => setPaginaMateriais(paginaMateriais - 1)}>Anterior</button><span>Página {paginaMateriais + 1}</span><button type="button" disabled={(paginaMateriais + 1) * 12 >= elegiveis.length} onClick={() => setPaginaMateriais(paginaMateriais + 1)}>Próxima</button></div>
      </div><aside id="r2-selecionados" tabIndex={-1} aria-label="Materiais selecionados"><h3>Selecionados ({cesta.length})</h3>{cesta.map(i => {const m = materiais.find(x => x.id === i.materialId);return <div key={i.materialId} className="r2-item"><label>{m?.nome || `Material #${i.materialId}`} · {m?.unidadeMedida}<input required type="number" min={decimal(m || {}) ? '.001' : '1'} step={decimal(m || {}) ? '.001' : '1'} value={i.quantidade} onChange={e => setCesta(cesta.map(x => x.materialId === i.materialId ? { ...x, quantidade: e.target.value } : x))} /></label>{Number(i.quantidade) > saldo(m || {}) && <p role="alert">Quantidade maior que o saldo físico do depósito.</p>}<button type="button" onClick={() => setCesta(cesta.filter(x => x.materialId !== i.materialId))}>Remover {m?.nome}</button></div>;})}<p>A emissão validará saldo livre e reserva da OS no servidor.</p><button className="estoque-primary" disabled={acoesBloqueadas || !cesta.length || (novo && !config?.habilitado)}>Salvar rascunho e revisar</button><button type="button" className="estoque-secondary" onClick={() => setParams({})}>Voltar às ORs</button></aside></div>
    </form> : or ? <div className="r2-detalhe"><div className="r2-botoes"><h3>{or.numero} · versão {or.versao_emitida || 'não emitida'}</h3><button className="estoque-secondary" onClick={() => setParams({})}>Voltar à lista</button></div><p><strong>{legivel(or.fisico)}</strong> · Documentação: <strong>{legivel(or.documental)}</strong>{or.atrasada ? ' · PRAZO EM ATRASO' : ''}</p><p>{or.destino} · Depósito: <strong>{or.deposito}</strong> · {or.osId ? `OS #${or.osId}` : 'Avulsa'}</p><p>Prazo de anexação: {data(or.prazo_anexacao)} · revisão: {data(or.prazo_revisao)}</p>
      <div className="r2-tabela"><table><caption>Resumo das quantidades — confirmar a versão impressa antes da entrega</caption><thead><tr><th>Material</th><th>Impresso</th><th>Efetivo</th><th>Devolvido</th></tr></thead><tbody>{or.itens.map(i => <tr key={i.material_id}><th>{i.nome}<small>{i.unidade}</small></th><td>{numero(i.solicitado)}</td><td>{numero(i.efetivo)}</td><td>{numero(i.devolvido)}</td></tr>)}</tbody></table></div>
      <details open><summary>Documentos preservados ({or.arquivos.length})</summary>{or.arquivos.map(f => <p key={f.id}><button className="estoque-secondary" onClick={() => baixar(f)}>{f.tipo === 'EMISSAO' ? 'Abrir / imprimir documento emitido' : `Abrir ${legivel(f.tipo)}`} · v{f.versao} · #{f.id}</button><small> {data(f.criado_em)} · {f.hash.slice(0,12)}</small></p>)}<p>Documento emitido e comprovante assinado são arquivos distintos. Anexar ou conferir não gera baixa.</p></details>
      {operador && secao !== 'historico' && <fieldset disabled={acoesBloqueadas}><legend>Ações operacionais</legend>
        <div className="r2-campos"><label>Justificativa / motivo<textarea value={motivo} onChange={e => setMotivo(e.target.value)} maxLength={2000} /></label><label>Quem entrega / confere<input value={entregaPor} onChange={e => setEntregaPor(e.target.value)} /></label><label>Quem retira / devolve<input value={retirante} onChange={e => setRetirante(e.target.value)} /></label></div>
        {or.fisico === 'RASCUNHO' && <><div className="r2-botoes"><button className="estoque-secondary" onClick={() => setParams({ or: String(or.id), editar: '1' })}>Editar rascunho</button>{admin && !or.osId && <button className="estoque-secondary" disabled={!motivo.trim()} onClick={() => executar('autorizacoes/AVULSA')}>Autorizar esta emissão avulsa</button>}{admin && <button className="estoque-secondary" disabled={!motivo.trim()} onClick={() => executar('autorizacoes/EXCECAO_DOCUMENTAL')}>Autorizar exceção documental individual</button>}<button className="estoque-primary" disabled={!config?.habilitado} onClick={() => executar('emissoes')}>Confirmar resumo e emitir com reserva</button></div><p>Revise itens, depósito e responsáveis acima. Emissão preserva versão e reserva; impressão não confirma a entrega.</p></>}
        {['RASCUNHO','RESERVADA'].includes(or.fisico) && <div className="r2-botoes">{or.fisico === 'RESERVADA' && <button className="estoque-secondary" disabled={!motivo.trim()} onClick={() => executar('revisoes')}>Substituir emissão / revisar</button>}<button className="estoque-secondary" disabled={!motivo.trim()} onClick={() => executar('cancelamento')}>Cancelar antes da entrega</button></div>}
        {(or.fisico === 'RESERVADA' || (or.entrega_id && !or.devolvida)) && <details><summary>{or.fisico === 'RESERVADA' ? 'Conferir entrega física' : 'Registrar devolução e concluir retorno'}</summary><p>Uma confirmação por OR. Complemento exige outra OR; informe zero para item não entregue/devolvido.</p>{or.itens.map(i => <label className="r2-quantidade" key={i.material_id}>{i.nome} · {i.unidade}<input type="number" min="0" max={or.fisico === 'RESERVADA' ? i.solicitado : i.efetivo} step={['FRACIONADO','METRAGEM'].includes(i.controle) ? '.001' : '1'} value={quantidades[i.material_id] ?? 0} onChange={e => setQuantidades({ ...quantidades, [i.material_id]: e.target.value })} /></label>)}
          {or.fisico === 'RESERVADA' ? <button className="estoque-primary" disabled={!entregaPor.trim() || !retirante.trim()} onClick={() => executar('entrega', { itens: vetor })}>Confirmar quantidades efetivas e registrar baixa única</button> : <><label>Assinatura de recebimento da devolução (PNG)<input type="file" accept="image/png" onChange={e => {const f = e.target.files[0];if (!f) return;const reader = new FileReader();reader.onload = () => setAssinatura(reader.result);reader.readAsDataURL(f);}} /></label><p>Ferramentas exigem retorno integral. A assinatura recebida é preservada; não é criada pelo sistema.</p><button className="estoque-primary" disabled={!assinatura || !entregaPor.trim() || !retirante.trim()} onClick={() => executar('devolucao', { itens: vetor, assinaturaRecebimentoBase64: assinatura })}>Confirmar devolução</button></>}
        </details>}
        {['AGUARDANDO_COMPROVANTE','REJEITADO'].includes(or.documental) && <div><label>Comprovante assinado — PDF, PNG ou JPEG (até 10 MiB)<input type="file" accept="application/pdf,image/png,image/jpeg" onChange={e => setArquivo(e.target.files[0])} /></label><button className="estoque-primary" disabled={!arquivo} onClick={() => executar('comprovantes', {}, arquivo)}>{or.documental === 'REJEITADO' ? 'Reenviar novo comprovante' : 'Anexar comprovante assinado'}</button></div>}
      </fieldset>}
      {revisor && or.documental === 'ANEXADO' && secao !== 'historico' && <fieldset disabled={acoesBloqueadas}><legend>Conferência documental</legend><p>Abra o comprovante ativo #{or.comprovanteAtivo} e confira a emissão, entrega e assinaturas físicas.</p><label>Justificativa (obrigatória na rejeição e autorrevisão)<textarea value={motivo} onChange={e => setMotivo(e.target.value)} /></label>{admin && <label><input type="checkbox" checked={autorrevisao} onChange={e => setAutorrevisao(e.target.checked)} /> Solicitar explicitamente autorrevisão excepcional de ADMIN, com justificativa auditada</label>}<div className="r2-botoes"><button className="estoque-primary" disabled={autorrevisao && !motivo.trim()} onClick={() => executar('conferencia', { arquivoId: or.comprovanteAtivo, decisao: 'CONFERIR', autorrevisao })}>Conferir comprovante</button><button className="estoque-secondary" disabled={!motivo.trim()} onClick={() => executar('conferencia', { arquivoId: or.comprovanteAtivo, decisao: 'REJEITAR', autorrevisao })}>Rejeitar e manter pendência</button></div></fieldset>}
      <details><summary>Histórico e autorizações</summary>{or.autorizacoes.map(x => <p key={x.id}>{legivel(x.tipo)} · autor #{x.autor} · {data(x.criado_em)} · {x.consumida ? 'consumida' : 'não consumida'} · {x.motivo}</p>)}<ol>{or.eventos.map(e => <li key={e.id}><strong>{legivel(e.operacao)}</strong> · autor #{e.autor} · {data(e.ocorrido_em)}<pre>{e.dados}</pre></li>)}</ol></details>
    </div> : (selecionada && !erro) || !lista ? <p role="status">Carregando retiradas…</p> : <><div className="r2-lista">{lista.itens.map(o => <article key={o.id}><div><h3>{o.numero}</h3><p>{o.destino} · <strong>{o.deposito}</strong></p><p>{legivel(o.fisico)} · {legivel(o.documental)}{o.atrasada ? ' · EM ATRASO' : ''}</p></div><button className="estoque-secondary" onClick={() => abrir(o.id)}>Abrir {o.numero}</button></article>)}{!lista.itens.length && <p>Nenhuma OR encontrada nesta fila.</p>}</div><div className="r2-botoes"><button className="estoque-secondary" disabled={!pagina} onClick={() => setPagina(pagina - 1)}>Anterior</button><span>Página {pagina + 1}</span><button className="estoque-secondary" disabled={!lista.mais} onClick={() => setPagina(pagina + 1)}>Próxima</button></div></>}
    {secao === 'materiais' && <Link to="/estoque/retiradas?nova=1">Preparar retirada</Link>}
  </section>;
}
