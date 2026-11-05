package com.poprc.demo.service;

import com.poprc.demo.dto.RetiradaV2Request.*;
import com.poprc.demo.model.*;
import com.poprc.demo.repository.*;
import com.poprc.demo.security.UsuarioAutenticado;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Novo agregado físico/documental. Os endpoints legados continuam exigindo suas assinaturas. */
@Service
@RequiredArgsConstructor
public class RetiradaV2Service {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final EntityManager em;
    private final OrdemRetiradaRepository ordens;
    private final OrdemServicoRepository osRepository;
    private final MaterialRepository materiais;
    private final MaterialItemRepository previstos;
    private final FuncionarioRepository funcionarios;
    private final LocalEstoqueRepository locais;
    private final SaldoMaterialLocalRepository saldos;
    private final MovimentacaoEstoqueRepository movimentos;
    private final SaldoLocalService saldoLocal;
    private final ReservaRetiradaV2Guard guard;
    private final CalendarioRetirada calendario;
    private final ArquivoRetiradaV2 arquivos;
    private final OrdemRetiradaPdfService pdfLegado;
    private final AcessoOperacionalService acesso;
    private final FluxoOrdemServicoService fluxo;

    private record Contexto(OrdemRetirada ordem, Map<String,Object> estado, Funcionario ator) {}
    private static ResponseStatusException erro(HttpStatus status, String texto) { return new ResponseStatusException(status, texto); }
    private static void exigir(boolean condicao, String texto) { if (!condicao) throw erro(HttpStatus.CONFLICT, texto); }
    private static String texto(String valor, String campo) {
        if (valor == null || valor.isBlank() || valor.length() > 2000) throw erro(HttpStatus.UNPROCESSABLE_ENTITY, campo + " obrigatório (até 2000 caracteres).");
        return valor.trim();
    }
    private Funcionario ator(Authentication auth, String... perfis) {
        if (auth == null || !(auth.getPrincipal() instanceof UsuarioAutenticado u)) throw erro(HttpStatus.UNAUTHORIZED,"Sessão autenticada necessária.");
        Funcionario f = funcionarios.findById(u.getFuncionarioId()).filter(x -> Boolean.TRUE.equals(x.getAtivo()))
                .orElseThrow(() -> erro(HttpStatus.FORBIDDEN,"Usuário inativo ou inexistente."));
        if (!f.getPerfilAcesso().name().equals(u.getPerfil()) || !Set.of(perfis).contains(f.getPerfilAcesso().name())) throw erro(HttpStatus.FORBIDDEN,"Operação não permitida para este perfil.");
        return f;
    }
    private Funcionario leitor(Authentication a) { return ator(a,"ADMIN","ESTOQUE","SUPERVISOR_TECNICO","AUDITOR","TECNICO"); }
    private String serializar(Object obj) { return json.writeValueAsString(obj); }
    @SuppressWarnings("unchecked") private Map<String,Object> mapa(String s) { return json.readValue(s, Map.class); }
    private long numero(Map<String,Object> m,String k) { return ((Number)m.get(k)).longValue(); }
    private Map<String,Object> estado(Long id) {
        return jdbc.queryForList("select * from retiradas_v2 where or_id=?",id).stream().findFirst()
                .orElseThrow(() -> erro(HttpStatus.NOT_FOUND,"OR do novo fluxo não encontrada."));
    }
    private void objeto(OrdemRetirada o, Authentication a) {
        if (o.getOrdemServico()==null && ator(a,"ADMIN","ESTOQUE","SUPERVISOR_TECNICO","AUDITOR","TECNICO").getPerfilAcesso()==PerfilAcesso.TECNICO)
            throw erro(HttpStatus.FORBIDDEN,"OR avulsa fora do escopo técnico.");
        acesso.garantirAcessoOrdemRetirada(o.getId(), a);
    }
    private Contexto travar(Long id, long revisao, Authentication a, String... perfis) {
        Funcionario f=ator(a,perfis);
        guard.lock(); // sempre antes de OS/OR/material; mesmo protocolo usado pelos escritores antigos
        OrdemRetirada o=ordens.findByIdForUpdate(id).filter(OrdemRetirada::isNovoFluxo)
                .orElseThrow(() -> erro(HttpStatus.NOT_FOUND,"OR do novo fluxo não encontrada."));
        objeto(o,a);
        Map<String,Object> v=estado(id);
        exigir(numero(v,"revisao")==revisao,"A OR foi alterada. Recarregue antes de continuar.");
        return new Contexto(o,v,f);
    }
    private Map<String,Object> intencao(String escopo, UUID chave, Object payload, Supplier<Map<String,Object>> executar) {
        if(chave==null)throw erro(HttpStatus.BAD_REQUEST,"Chave da intenção obrigatória.");
        String hash=hashIntencao("poprc.orv2.canonico-v2\n"+representarIntencao(payload));
        List<Map<String,Object>> anterior=jdbc.queryForList("select * from retirada_v2_operacoes where escopo=? and chave=?",escopo,chave);
        if(!anterior.isEmpty()) {
            var persistida=anterior.getFirst();String algoritmo=(String)persistida.get("algoritmo");
            boolean igual="CANONICO_V2".equals(algoritmo)?hash.equals(persistida.get("hash")):
                    "JSON_V1".equals(algoritmo)&&hashLegadoConfere(escopo,payload,(String)persistida.get("hash"));
            exigir(igual,"Chave já utilizada com conteúdo diferente.");
            return mapa((String)anterior.getFirst().get("resultado"));
        }
        Map<String,Object> resultado=executar.get();
        jdbc.update("insert into retirada_v2_operacoes(escopo,chave,hash,or_id,resultado,algoritmo) values(?,?,?,?,?,?)",escopo,chave,hash,resultado.get("id"),serializar(resultado),"CANONICO_V2");
        return resultado;
    }
    private String representarIntencao(Object payload) {
        Object arvore=json.readerFor(Object.class).with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(serializar(payload));
        return serializar(ordenarJson(arvore));
    }
    private String hashIntencao(String json) { return arquivos.hash(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private Object ordenarJson(Object valor) {
        if(valor instanceof Map<?,?> mapa) {var ordenado=new TreeMap<String,Object>();mapa.forEach((k,v)->ordenado.put((String)k,ordenarJson(v)));return ordenado;}
        if(valor instanceof List<?> lista)return lista.stream().map(this::ordenarJson).toList();
        return valor; // listas preservam a ordem e valores permanecem exatamente os do DTO normalizado
    }
    private boolean hashLegadoConfere(String escopo,Object payload,String esperado) {
        // Não reescreve o hash antigo. Só aceita representações que o algoritmo V1 realmente produzia.
        if(esperado.equals(hashIntencao(serializar(payload))))return true;
        if(escopo.endsWith(":ANEXAR")&&payload instanceof Map<?,?> mapa&&mapa.keySet().equals(Set.of("acao","hash"))) {
            for(var ordem:List.of(List.of("acao","hash"),List.of("hash","acao"))) {
                var antigo=new LinkedHashMap<String,Object>();for(String chave:ordem)antigo.put(chave,mapa.get(chave));
                if(esperado.equals(hashIntencao(serializar(antigo))))return true;
            }
        }
        return false;
    }
    private Map<String,Object> comando(Long id,Acao r,Authentication a,String operacao,String[] perfis,java.util.function.Consumer<Contexto> executar) {
        // Autorização é refeita inclusive no replay. Estado/revisão somente são comparados numa nova intenção.
        ator(a,perfis);guard.lock();
        OrdemRetirada o=ordens.findById(id).filter(OrdemRetirada::isNovoFluxo).orElseThrow(()->erro(HttpStatus.NOT_FOUND,"OR não encontrada."));objeto(o,a);
        return intencao(id+":"+operacao,r.chave(),r,()->{
            Contexto c=travar(id,r.revisao(),a,perfis); executar.accept(c);
            em.flush();jdbc.update("update retiradas_v2 set revisao=revisao+1 where or_id=?",id);
            evento(id,operacao,c.ator(),r);return detalheInterno(id);
        });
    }
    private void evento(Long id,String op,Funcionario f,Object dados) {
        if(dados instanceof Acao a)dados=new Acao(a.chave(),a.revisao(),a.versao(),a.motivo(),a.itens(),a.arquivoId(),a.decisao(),a.autorrevisao(),a.entregaPor(),a.retirante(),null);
        jdbc.update("insert into retirada_v2_eventos(or_id,operacao,autor,dados) values(?,?,?,?)",id,op,f.getId(),serializar(Map.of("perfil",f.getPerfilAcesso().name(),"entrada",dados)));
    }
    private static final String[] OPERADOR={"ADMIN","ESTOQUE"};
    private static final String[] ADMIN={"ADMIN"};
    private static final String[] REVISOR={"ADMIN","SUPERVISOR_TECNICO"};

    @Transactional(readOnly=true)
    public Map<String,Object> configuracao(Authentication a) {
        leitor(a);return Map.of("habilitado",guard.enabled(),"osHabilitadas",guard.osHabilitadas(),"todasOsHabilitadas",guard.todasOsHabilitadas(),"calendario",calendario.descricao(),
            "limiteItens",100,"tiposLegados",List.of("BOBINA","ROLO"));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> contextoOs(Long id,Authentication a) {
        ator(a,OPERADOR);acesso.garantirAcessoOrdem(id,a);
        var os=osRepository.findById(id).orElseThrow(()->erro(HttpStatus.NOT_FOUND,"OS inexistente."));
        return Map.of("id",id,"numero",os.getNumeroOs(),"habilitada",guard.osHabilitada(id)&&guard.osNaoSerializada(id),"itens",jdbc.queryForList("select i.id as material_item_id,i.material_id,i.quantidade_prevista-i.quantidade_retirada_v2 as restante,m.nome,m.tipo_controle,m.unidade_medida from comarca_materiais i join comarcas c on c.id=i.comarca_id join materiais m on m.id=i.material_id where c.ordem_servico_id=? and i.estoque_reservado=true order by i.id",id));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> listar(Authentication a,String fila,int pagina) {
        Funcionario f=leitor(a);int p=Math.max(0,pagina);
        String filtro="pendencias".equals(fila)?" and v.entrega_id is not null and v.documental<>'CONFERIDO'":"";
        List<Object> argumentos=new ArrayList<>();
        if(f.getPerfilAcesso()==PerfilAcesso.TECNICO){filtro+=" and exists(select 1 from ordens_servico os join projetos p on p.id=os.projeto_id where os.id=o.ordem_servico_id and (p.responsavel_id=? or exists(select 1 from projetos_membros pm where pm.projeto_id=p.id and pm.funcionario_id=?)))";argumentos.add(f.getId());argumentos.add(f.getId());}
        argumentos.add(p*20);
        List<Map<String,Object>> lista=jdbc.queryForList("select v.or_id from retiradas_v2 v join ordens_retirada o on o.id=v.or_id where true"+filtro+" order by v.or_id desc limit 21 offset ?",argumentos.toArray());
        List<Map<String,Object>> itens=new ArrayList<>();
        for(var row:lista){Long id=((Number)row.get("or_id")).longValue();OrdemRetirada o=ordens.findById(id).orElseThrow();
            try{objeto(o,a);}catch(ResponseStatusException negado){if(negado.getStatusCode().value()==403)continue;throw negado;}
            itens.add(resumo(id));}
        boolean mais=itens.size()>20;if(mais)itens.removeLast();return Map.of("itens",itens,"pagina",p,"mais",mais);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> detalhe(Long id,Authentication a) {
        leitor(a);OrdemRetirada o=ordens.findById(id).filter(OrdemRetirada::isNovoFluxo).orElseThrow(()->erro(HttpStatus.NOT_FOUND,"OR não encontrada."));objeto(o,a);return detalheInterno(id);
    }
    private Map<String,Object> resumo(Long id) {
        Map<String,Object> v=estado(id);Map<String,Object> out=new LinkedHashMap<>();
        for(String k:List.of("revisao","fisico","documental","versao_emitida","destino","destino_id","local_id","devolvida"))out.put(k,v.get(k));
        out.put("entrega_id",v.get("entrega_id")==null?null:v.get("entrega_id").toString());
        var o=ordens.findById(id).orElseThrow();out.put("id",id);out.put("numero",o.getStatus().equals("RASCUNHO_V2")?"Rascunho #"+id:o.getNumeroOr());out.put("osId",o.getOrdemServico()!=null?o.getOrdemServico().getId():null);
        out.put("deposito",locais.findById(numero(v,"local_id")).orElseThrow().getNome());
        for(String k:List.of("entregue_em","prazo_anexacao","prazo_revisao"))out.put(k,v.get(k)==null?null:((java.sql.Timestamp)v.get(k)).toInstant().toString());
        Instant prazo="ANEXADO".equals(v.get("documental"))&&v.get("prazo_revisao")!=null?((java.sql.Timestamp)v.get("prazo_revisao")).toInstant():v.get("prazo_anexacao")!=null?((java.sql.Timestamp)v.get("prazo_anexacao")).toInstant():null;
        out.put("atrasada",!"CONFERIDO".equals(v.get("documental"))&&prazo!=null&&Instant.now().isAfter(prazo));return out;
    }
    private Map<String,Object> detalheInterno(Long id) {
        Map<String,Object> out=resumo(id);out.put("rascunho",mapa((String)estado(id).get("dados")));
        out.put("itens",jdbc.queryForList("select * from retirada_v2_itens where or_id=? order by material_id",id));
        out.put("reservas",jdbc.queryForList("select material_id,local_id,quantidade,origem_os from retirada_v2_reservas where or_id=? order by material_id",id));
        out.put("arquivos",linhasPublicas(jdbc.queryForList("select id,versao,tipo,hash,mime,nome,autor,criado_em from retirada_v2_arquivos where or_id=? order by id",id)));
        out.put("eventos",linhasPublicas(jdbc.queryForList("select id,operacao,autor,ocorrido_em,dados from retirada_v2_eventos where or_id=? order by id",id)));
        out.put("autorizacoes",linhasPublicas(jdbc.queryForList("select id,tipo,revisao,motivo,autor,criado_em,consumida,pendencias from retirada_v2_autorizacoes where or_id=? order by id",id)));
        out.put("comprovanteAtivo",estado(id).get("comprovante_ativo"));return out;
    }
    private List<Map<String,Object>> linhasPublicas(List<Map<String,Object>> linhas) {
        for(var row:linhas)row.replaceAll((k,v)->v instanceof java.sql.Timestamp t?t.toInstant().toString():v);return linhas;
    }
    @Transactional
    public Map<String,Object> criar(Rascunho r,Authentication a) {
        Funcionario f=ator(a,OPERADOR);guard.lock();
        return intencao("criar:"+f.getId(),r.chave(),r,()->{
            exigir(guard.enabled(),"Novas retiradas desabilitadas pelo servidor.");
            OrdemRetirada o=new OrdemRetirada();o.setNovoFluxo(true);o.setNumeroOr("RASCUNHO-"+UUID.randomUUID());o.setStatus("RASCUNHO_V2");o.setGeradoPor(f.getNome());
            aplicarContexto(o,r,a);ordens.saveAndFlush(o);
            jdbc.update("insert into retiradas_v2(or_id,local_id,destino_id,destino,motivo,dados,autor) values(?,?,?,?,?,?,?)",o.getId(),r.localId(),r.destinoId(),r.destino(),r.motivo(),serializar(r),f.getId());
            salvarItens(o,r);evento(o.getId(),"RASCUNHO_CRIADO",f,r);return detalheInterno(o.getId());
        });
    }
    @Transactional
    public Map<String,Object> editar(Long id,Rascunho r,Authentication a) {
        ator(a,OPERADOR);guard.lock();OrdemRetirada o=ordens.findById(id).orElseThrow();objeto(o,a);
        return intencao(id+":rascunho",r.chave(),r,()->{
            Contexto c=travar(id,r.revisao(),a,OPERADOR);exigir("RASCUNHO".equals(c.estado().get("fisico")),"Somente rascunho pode ser editado; revise a emissão antes.");
            aplicarContexto(c.ordem(),r,a);salvarItens(c.ordem(),r);em.flush();
            jdbc.update("update retiradas_v2 set revisao=revisao+1,revisao_conteudo=revisao_conteudo+1,local_id=?,destino_id=?,destino=?,motivo=?,dados=? where or_id=?",r.localId(),r.destinoId(),r.destino(),r.motivo(),serializar(r),id);
            evento(id,"RASCUNHO_EDITADO",c.ator(),r);return detalheInterno(id);
        });
    }
    private void aplicarContexto(OrdemRetirada o,Rascunho r,Authentication a) {
        texto(r.destino(),"Destino");texto(r.motivo(),"Motivo");texto(r.entregaPor(),"Responsável pela entrega");texto(r.retirante(),"Retirante");
        if(r.destinoId()==null||!r.destinoId().matches("[A-Za-z0-9._:-]{1,100}"))throw erro(HttpStatus.UNPROCESSABLE_ENTITY,"Identificador estruturado do destino obrigatório (sem espaços).");
        locais.findById(r.localId()==null?-1L:r.localId()).filter(l->Boolean.TRUE.equals(l.getAtivo())).orElseThrow(()->erro(HttpStatus.UNPROCESSABLE_ENTITY,"Depósito ativo obrigatório."));
        if(r.osId()!=null){exigir(guard.osHabilitada(r.osId()),"OS não habilitada para criar novas ORs V2.");acesso.garantirAcessoOrdem(r.osId(),a);
            OrdemServico os=osRepository.findByIdForUpdate(r.osId()).orElseThrow(()->erro(HttpStatus.NOT_FOUND,"OS inexistente."));
            exigir(guard.osNaoSerializada(os.getId()),"OS mista: bobinas/rolos permanecem no fluxo legado. Nenhuma reserva V2 foi criada.");
            exigir(!Boolean.TRUE.equals(os.getArquivado()),"OS arquivada.");o.setOrdemServico(os);
            Comarca comarca=em.createQuery("select c from Comarca c where c.ordemServico.id=:os",Comarca.class).setParameter("os",os.getId()).getResultStream().findFirst().orElseThrow(()->erro(HttpStatus.CONFLICT,"OS sem obra vinculada."));o.setComarca(comarca);
        }else{o.setOrdemServico(null);o.setComarca(null);}
    }
    private void salvarItens(OrdemRetirada o,Rascunho r) {
        if(r.itens()==null||r.itens().isEmpty()||r.itens().size()>100)throw erro(HttpStatus.UNPROCESSABLE_ENTITY,"Selecione entre 1 e 100 materiais.");
        Set<Long> ids=new HashSet<>();List<OrdemRetiradaItem> novos=new ArrayList<>();
        for(Item item:r.itens().stream().sorted(Comparator.comparing(Item::materialId,Comparator.nullsLast(Long::compareTo))).toList()){
            if(item.materialId()==null||!ids.add(item.materialId()))throw erro(HttpStatus.UNPROCESSABLE_ENTITY,"Material ausente/duplicado.");
            Material m=materiais.findByIdForUpdate(item.materialId()).filter(x->Boolean.TRUE.equals(x.getAtivo())).orElseThrow(()->erro(HttpStatus.UNPROCESSABLE_ENTITY,"Material inativo/inexistente."));
            exigir(!Set.of(TipoControleEstoque.BOBINA,TipoControleEstoque.ROLO).contains(m.getTipoControle()),"Bobinas/rolos permanecem no fluxo legado.");quantidade(m,item.quantidade(),true);
            MaterialItem previsto=null;
            if(o.getOrdemServico()!=null){previsto=previstos.findById(item.materialItemId()==null?-1L:item.materialItemId()).orElseThrow(()->erro(HttpStatus.UNPROCESSABLE_ENTITY,"Item previsto da OS obrigatório."));
                exigir(Objects.equals(previsto.getComarca().getId(),o.getComarca().getId())&&Objects.equals(previsto.getMaterial().getId(),m.getId()),"Material previsto não pertence à OS/material.");
                BigDecimal entregue=guard.entregueItem(previsto.getId());
                exigir((!Boolean.TRUE.equals(previsto.getEstoqueBaixado())||entregue.signum()>0)&&Boolean.TRUE.equals(previsto.getEstoqueReservado()),"Previsão sem reserva elegível para a nova retirada.");
                exigir(item.quantidade().compareTo(previsto.getQuantidadePrevista().subtract(entregue))<=0,"Solicitação excede a previsão restante da OS.");
            }else exigir(item.materialItemId()==null,"Avulsa não aceita item previsto de OS.");
            OrdemRetiradaItem n=new OrdemRetiradaItem();n.setOrdemRetirada(o);n.setMaterial(m);n.setMaterialItem(previsto);n.setNomeMaterial(m.getNome());n.setCategoria(m.getCategoria());n.setQuantidadeSolicitada(item.quantidade());novos.add(n);
        }
        o.getItens().clear();em.flush();o.getItens().addAll(novos);ordens.saveAndFlush(o);
        jdbc.update("delete from retirada_v2_itens where or_id=?",o.getId());
        for(OrdemRetiradaItem i:novos)jdbc.update("insert into retirada_v2_itens(or_id,material_id,material_item_id,nome,unidade,controle,categoria,solicitado,custo) values(?,?,?,?,?,?,?,?,?)",o.getId(),i.getMaterial().getId(),i.getMaterialItem()==null?null:i.getMaterialItem().getId(),i.getNomeMaterial(),i.getMaterial().getUnidadeMedida().name(),i.getMaterial().getTipoControle().name(),i.getCategoria(),i.getQuantidadeSolicitada(),i.getMaterial().getCustoMedio());
    }
    @Transactional
    public Map<String,Object> autorizar(Long id,String tipo,Acao r,Authentication a) {
        exigir(Set.of("AVULSA","EXCECAO_DOCUMENTAL").contains(tipo),"Tipo de autorização inválido.");
        return comando(id,r,a,"AUTORIZAR_"+tipo,ADMIN,c->{
            exigir("RASCUNHO".equals(c.estado().get("fisico")),"Autorização pertence ao rascunho alvo.");texto(r.motivo(),"Justificativa da autorização");
            if(tipo.equals("AVULSA"))exigir(c.ordem().getOrdemServico()==null,"Autorização avulsa somente sem OS.");
            var pendencias=pendencias(c.ordem(),c.estado());
            if(tipo.equals("EXCECAO_DOCUMENTAL"))exigir(!pendencias.isEmpty(),"Não há pendências anteriores neste escopo.");
            // Autorizações vinculam-se à revisão de conteúdo, sem reescrever sua autoria/escopo original.
            jdbc.update("insert into retirada_v2_autorizacoes(or_id,revisao,tipo,motivo,pendencias,autor) values(?,?,?,?,?,?)",id,numero(c.estado(),"revisao_conteudo"),tipo,r.motivo(),serializar(pendencias),c.ator().getId());
        });
    }
    private List<Long> pendencias(OrdemRetirada o,Map<String,Object> v) {
        return jdbc.queryForList("select v.or_id from retiradas_v2 v join ordens_retirada o on o.id=v.or_id where v.entrega_id is not null and v.documental<>'CONFERIDO' and v.or_id<>? and "+(o.getOrdemServico()!=null?"o.ordem_servico_id=?":"o.ordem_servico_id is null and v.destino_id=?")+" order by v.or_id",Long.class,o.getId(),o.getOrdemServico()!=null?o.getOrdemServico().getId():v.get("destino_id"));
    }
    private void consumirAutorizacao(Contexto c,String tipo,List<Long> pendencias) {
        var rows=jdbc.queryForList("select * from retirada_v2_autorizacoes where or_id=? and revisao=? and tipo=? and consumida=false order by id desc",c.ordem().getId(),numero(c.estado(),"revisao_conteudo"),tipo);
        exigir(!rows.isEmpty(),"Autorização de ADMIN necessária: "+tipo);
        var row=rows.getFirst();if(tipo.equals("EXCECAO_DOCUMENTAL"))exigir(serializar(pendencias).equals(row.get("pendencias")),"Pendências mudaram; nova autorização individual necessária.");
        jdbc.update("update retirada_v2_autorizacoes set consumida=true where id=?",row.get("id"));
    }
    @Transactional
    public Map<String,Object> emitir(Long id,Acao r,Authentication a) {
        return comando(id,r,a,"EMITIR",OPERADOR,c->{
            exigir(guard.enabled(),"Novas emissões desabilitadas pelo servidor.");exigir("RASCUNHO".equals(c.estado().get("fisico")),"Somente rascunho pode ser emitido.");
            OrdemRetirada o=c.ordem();if(o.getOrdemServico()!=null){em.refresh(osRepository.findByIdForUpdate(o.getOrdemServico().getId()).orElseThrow());exigir(guard.osHabilitada(o.getOrdemServico().getId()),"OS não habilitada.");exigir(!Boolean.TRUE.equals(o.getOrdemServico().getArquivado()),"OS arquivada.");
                exigir(guard.osNaoSerializada(o.getOrdemServico().getId()),"OS mista: bobinas/rolos permanecem no fluxo legado. Cancele/revise emissões sem entrega antes de gerar a OR legada.");
                exigir(!jdbc.queryForObject("select exists(select 1 from ordens_retirada where ordem_servico_id=? and id<>? and status in ('GERADA','RETIRADA'))",Boolean.class,o.getOrdemServico().getId(),id),"Conclua a OR física ativa e sua devolução antes de emitir outra.");
            }else consumirAutorizacao(c,"AVULSA",List.of());
            List<Long> pendencias=pendencias(o,c.estado());if(!pendencias.isEmpty())consumirAutorizacao(c,"EXCECAO_DOCUMENTAL",pendencias);
            for(OrdemRetiradaItem i:ordenados(o)){
                Material m=bloquear(i);BigDecimal q=i.getQuantidadeSolicitada();quantidade(m,q,true);
                exigir(Boolean.TRUE.equals(m.getAtivo()),"Material removido desde o rascunho.");
                var snapshot=jdbc.queryForMap("select controle,unidade,material_item_id,solicitado from retirada_v2_itens where or_id=? and material_id=?",id,m.getId());
                exigir(Objects.equals(snapshot.get("material_item_id"),i.getMaterialItem()==null?null:i.getMaterialItem().getId())
                        && ((BigDecimal)snapshot.get("solicitado")).compareTo(q)==0,"Previsão/quantidade diverge do rascunho salvo.");
                exigir(m.getTipoControle().name().equals(snapshot.get("controle"))&&m.getUnidadeMedida().name().equals(snapshot.get("unidade")),"Unidade/tipo mudou; revise o rascunho.");
                BigDecimal origem=BigDecimal.ZERO;
                if(previsaoAtual(o,i,q)!=null)origem=q;
                BigDecimal reservaNova=q.subtract(origem), fixas=guard.fixa(m.getId(),numero(c.estado(),"local_id"));
                SaldoMaterialLocal s=local(m,numero(c.estado(),"local_id"));
                exigir(fisico(m).subtract(reservado(m)).compareTo(reservaNova)>=0&&fisicoLocal(m,s).subtract(fixas).compareTo(q)>=0,"Saldo global/depósito insuficiente para reservar.");
                setReservado(m,reservado(m).add(reservaNova));materiais.saveAndFlush(m);
                jdbc.update("update retirada_v2_itens set custo=? where or_id=? and material_id=?",m.getCustoMedio(),id,m.getId());
                jdbc.update("insert into retirada_v2_reservas(or_id,material_id,local_id,quantidade,origem_os) values(?,?,?,?,?)",id,m.getId(),numero(c.estado(),"local_id"),q,origem);
                saldoLocal.sincronizarReservas(m);
            }
            int versao=(int)numero(c.estado(),"versao_emitida")+1;
            if(o.getNumeroOr().startsWith("RASCUNHO"))o.setNumeroOr("ORV2-"+Year.now(CalendarioRetirada.ZONA)+"-"+id);
            o.setStatus("GERADA");o.setDataGeracao(LocalDateTime.now(CalendarioRetirada.ZONA));ordens.saveAndFlush(o);
            var dados=mapa((String)c.estado().get("dados"));
            byte[] pdf=arquivos.pdf(o.getNumeroOr(),versao,(String)c.estado().get("destino"),locais.findById(numero(c.estado(),"local_id")).orElseThrow().getNome(),referenciaOs(o),itensPdf(o,false),"Responsáveis indicados — entrega/conferência: "+Objects.toString(dados.get("entregaPor"),"")+"; retirante: "+Objects.toString(dados.get("retirante"),"")+".\n"+Objects.toString(dados.get("observacoes"),""),"EMISSÃO");
            registrarArquivo(id,versao,"EMISSAO",pdf,"application/pdf","OR-v"+versao+".pdf",c.ator());
            jdbc.update("insert into retirada_v2_emissoes(or_id,versao,snapshot,autor) values(?,?,?,?)",id,versao,serializar(Map.of("identificacaoOs",referenciaOs(o),"dados",dados,"itens",jdbc.queryForList("select * from retirada_v2_itens where or_id=? order by material_id",id),"reservas",jdbc.queryForList("select * from retirada_v2_reservas where or_id=? order by material_id",id))),c.ator().getId());
            jdbc.update("update retiradas_v2 set fisico='RESERVADA',documental='EMITIDO',versao_emitida=? where or_id=?",versao,id);
        });
    }
    /** Revalida a origem atual sob o guard comum e locks de previsão/obra, sem congelar rascunhos. */
    private MaterialItem previsaoAtual(OrdemRetirada o, OrdemRetiradaItem item, BigDecimal quantidade) {
        if (o.getOrdemServico()==null) {
            exigir(item.getMaterialItem()==null,"Previsão de OS não pode pertencer a OR avulsa.");
            return null;
        }
        Long pid=item.getMaterialItem()==null?null:item.getMaterialItem().getId();
        var rows=jdbc.queryForList("select i.material_id,i.comarca_id,c.ordem_servico_id from comarca_materiais i join comarcas c on c.id=i.comarca_id where i.id=? for update of i,c",pid);
        exigir(!rows.isEmpty(),"Previsão não existe mais; atualize o rascunho.");var atual=rows.getFirst();
        exigir(Objects.equals(atual.get("material_id"),item.getMaterial().getId())
                && o.getComarca()!=null && Objects.equals(atual.get("comarca_id"),o.getComarca().getId())
                && Objects.equals(atual.get("ordem_servico_id"),o.getOrdemServico().getId()),
                "Previsão mudou de material/obra/OS; atualize o rascunho antes de emitir ou entregar.");
        MaterialItem p=previstos.findById(pid).orElseThrow();em.refresh(p);
        BigDecimal entregue=guard.entregueItem(pid);
        exigir(Boolean.TRUE.equals(p.getEstoqueReservado())
                && (!Boolean.TRUE.equals(p.getEstoqueBaixado())||entregue.signum()>0)
                && quantidade.compareTo(p.getQuantidadePrevista().subtract(entregue))<=0,
                "Previsão sem reserva própria suficiente para esta OR.");
        return p;
    }
    private void validarReserva(Contexto c,OrdemRetiradaItem i,Map<String,Object> reserva) {
        exigir(numero(reserva,"or_id")==c.ordem().getId() && numero(reserva,"material_id")==i.getMaterial().getId()
                && numero(reserva,"local_id")==numero(c.estado(),"local_id"),"Depósito/material da reserva diverge da OR.");
        var snapshot=jdbc.queryForMap("select material_item_id,solicitado from retirada_v2_itens where or_id=? and material_id=?",c.ordem().getId(),i.getMaterial().getId());
        Long pid=i.getMaterialItem()==null?null:i.getMaterialItem().getId();
        exigir(Objects.equals(snapshot.get("material_item_id"),pid)
                && ((BigDecimal)reserva.get("quantidade")).compareTo(i.getQuantidadeSolicitada())==0
                && ((BigDecimal)snapshot.get("solicitado")).compareTo(i.getQuantidadeSolicitada())==0,"Origem/quantidade da reserva diverge do documento.");
        BigDecimal origem=(BigDecimal)reserva.get("origem_os");
        exigir(origem.compareTo(pid==null?BigDecimal.ZERO:i.getQuantidadeSolicitada())==0,"Origem da reserva não corresponde à previsão da OS.");
        previsaoAtual(c.ordem(),i,(BigDecimal)reserva.get("quantidade"));
    }
    private void liberar(Contexto c) {
        for(var row:jdbc.queryForList("select * from retirada_v2_reservas where or_id=? order by material_id",c.ordem().getId())){
            OrdemRetiradaItem item=ordenados(c.ordem()).stream().filter(i->i.getMaterial().getId().equals(numero(row,"material_id"))).findFirst().orElseThrow(()->erro(HttpStatus.CONFLICT,"Reserva sem item da OR."));
            validarReserva(c,item,row);
            Material m=materiais.findByIdForUpdate(numero(row,"material_id")).orElseThrow();
            BigDecimal propria=((BigDecimal)row.get("quantidade")).subtract((BigDecimal)row.get("origem_os"));
            setReservado(m,reservado(m).subtract(propria));materiais.saveAndFlush(m);
            jdbc.update("delete from retirada_v2_reservas where or_id=? and material_id=?",c.ordem().getId(),m.getId());saldoLocal.sincronizarReservas(m);
        }
    }
    @Transactional
    public Map<String,Object> revisarOuCancelar(Long id,Acao r,Authentication a,boolean cancelar) {
        return comando(id,r,a,cancelar?"CANCELAR":"REVISAR",OPERADOR,c->{
            exigir(Set.of("RASCUNHO","RESERVADA").contains(c.estado().get("fisico")),"Entrega confirmada não pode ser revisada/cancelada.");texto(r.motivo(),"Motivo");liberar(c);
            jdbc.update("update retiradas_v2 set fisico=?,documental=?,revisao_conteudo=revisao_conteudo+1 where or_id=?",cancelar?"CANCELADA":"RASCUNHO",cancelar?c.estado().get("documental"):"NAO_EMITIDO",id);
            c.ordem().setStatus(cancelar?"CANCELADA_V2":"RASCUNHO_V2");ordens.save(c.ordem());
        });
    }
    private Map<Long,BigDecimal> vetor(OrdemRetirada o,List<Efetivo> itens,boolean entrega) {
        if(itens==null||itens.size()!=o.getItens().size())throw erro(HttpStatus.UNPROCESSABLE_ENTITY,"Informe todos os itens, inclusive quantidades zero.");
        Map<Long,BigDecimal> out=new HashMap<>();for(var i:itens){if(i.materialId()==null||i.quantidade()==null||out.put(i.materialId(),i.quantidade())!=null)throw erro(HttpStatus.UNPROCESSABLE_ENTITY,"Vetor de quantidades inválido/duplicado.");}
        for(var i:o.getItens()){Material m=i.getMaterial();BigDecimal q=out.get(m.getId());quantidade(m,q,false);exigir(q.compareTo(entrega?i.getQuantidadeSolicitada():i.getQuantidadeRetirada())<=0,"Quantidade superior à impressa/entregue.");}
        return out;
    }
    @Transactional
    public Map<String,Object> entregar(Long id,Acao r,Authentication a) {
        return comando(id,r,a,"ENTREGAR",OPERADOR,c->{
            exigir("RESERVADA".equals(c.estado().get("fisico"))&&c.estado().get("entrega_id")==null,"OR não disponível para uma nova entrega.");
            exigir(r.versao()!=null&&r.versao()==(int)numero(c.estado(),"versao_emitida"),"Versão impressa foi substituída.");
            texto(r.entregaPor(),"Quem entrega/confere");texto(r.retirante(),"Quem retira");
            OrdemRetirada o=c.ordem();if(o.getOrdemServico()!=null)fluxo.validarRetiradaPermitida(o.getOrdemServico().getId());
            Map<Long,BigDecimal> efetivos=vetor(o,r.itens(),true);exigir(efetivos.values().stream().anyMatch(q->q.signum()>0),"Entrega deve conter quantidade positiva; cancele se nada foi entregue.");
            boolean parcial=o.getItens().stream().anyMatch(i->efetivos.get(i.getMaterial().getId()).compareTo(i.getQuantidadeSolicitada())<0);if(parcial)texto(r.motivo(),"Justificativa da entrega parcial");
            for(var i:ordenados(o)){
                Material m=bloquear(i);BigDecimal q=efetivos.get(m.getId());var res=jdbc.queryForMap("select * from retirada_v2_reservas where or_id=? and material_id=?",id,m.getId());
                validarReserva(c,i,res);
                BigDecimal total=(BigDecimal)res.get("quantidade"),origem=(BigDecimal)res.get("origem_os");
                SaldoMaterialLocal s=local(m,numero(c.estado(),"local_id"));BigDecimal antes=fisico(m);
                exigir(antes.compareTo(q)>=0&&fisicoLocal(m,s).compareTo(q)>=0&&reservado(m).compareTo(total)>=0,"Cobertura da reserva indisponível.");
                // OS conserva a sobra prevista. Avulsa libera toda a reserva própria, consumida ou não.
                BigDecimal reduzirReserva=origem.signum()>0?q:total;
                setFisico(m,antes.subtract(q));setReservado(m,reservado(m).subtract(reduzirReserva));setFisicoLocal(m,s,fisicoLocal(m,s).subtract(q));
                materiais.save(m);saldos.save(s);em.flush();jdbc.update("delete from retirada_v2_reservas where or_id=? and material_id=?",id,m.getId());saldoLocal.sincronizarReservas(m);
                i.setQuantidadeRetirada(q);if(i.getMaterialItem()!=null&&q.signum()>0){MaterialItem p=i.getMaterialItem();p.setQuantidadeRetiradaV2(p.getQuantidadeRetiradaV2().add(q));p.setEstoqueBaixado(true);p.setEstoqueReservado(p.getQuantidadePrevista().compareTo(p.getQuantidadeRetiradaV2())>0);p.setDataHoraRetirada(LocalDateTime.now(CalendarioRetirada.ZONA));previstos.save(p);}
                // Mesmo critério financeiro do legado: custo médio na baixa; devolução reaproveita esse custo.
                jdbc.update("update retirada_v2_itens set efetivo=?,custo=? where or_id=? and material_id=?",q,m.getCustoMedio(),id,m.getId());
                if(q.signum()>0)movimento(c,i,q,TipoMovimentacao.RETIRADA_OR,antes,fisico(m),r.motivo());
            }
            o.setStatus("RETIRADA");o.setDataRetirada(LocalDateTime.now(CalendarioRetirada.ZONA));o.setConferidoPor(r.entregaPor());o.setLevadoPor(r.retirante());ordens.saveAndFlush(o);
            Instant agora=Instant.now();jdbc.update("update retiradas_v2 set fisico=?,documental='AGUARDANDO_COMPROVANTE',entrega_id=?,entregue_em=?,operador_entrega=?,prazo_anexacao=? where or_id=?",parcial?"ENTREGUE_PARCIAL":"ENTREGUE",UUID.randomUUID(),java.sql.Timestamp.from(agora),c.ator().getId(),java.sql.Timestamp.from(calendario.prazo(agora)),id);
            if(o.getOrdemServico()!=null)fluxo.registrarRetirada(o.getOrdemServico().getId(),c.ator().getNome());
        });
    }
    @Transactional
    public Map<String,Object> devolver(Long id,Acao r,Authentication a) {
        // O arquivo de assinatura é guardado no regime existente; não é assinatura gerada pelo sistema.
        return comando(id,r,a,"DEVOLVER",OPERADOR,c->{
            exigir(c.estado().get("entrega_id")!=null&&!Boolean.TRUE.equals(c.estado().get("devolvida")),"OR sem entrega ou devolução já registrada.");
            texto(r.entregaPor(),"Quem recebe/confere devolução");texto(r.retirante(),"Quem devolve");
            String assinatura=r.assinaturaRecebimentoBase64();if(assinatura==null||!assinatura.startsWith("data:image/png;base64,")||assinatura.length()>1500000)throw erro(HttpStatus.UNPROCESSABLE_ENTITY,"Assinatura física de recebimento obrigatória, PNG válido.");
            try{byte[] b=Base64.getDecoder().decode(assinatura.substring(22));exigir("image/png".equals(arquivos.validar(b)),"Assinatura deve ser PNG.");}catch(IllegalArgumentException e){throw erro(HttpStatus.UNPROCESSABLE_ENTITY,"Assinatura inválida.");}
            var vetor=vetor(c.ordem(),r.itens(),false);
            for(var i:ordenados(c.ordem())){
                Material m=bloquear(i);BigDecimal q=vetor.get(m.getId());if("FERRAMENTA".equals(i.getCategoria()))exigir(q.compareTo(i.getQuantidadeRetirada())==0,"Ferramenta deve retornar integralmente.");
                BigDecimal antes=fisico(m);setFisico(m,antes.add(q));materiais.saveAndFlush(m);if(q.signum()>0)saldoLocal.creditar(m,numero(c.estado(),"local_id"),q);
                i.setQuantidadeDevolvida(q);jdbc.update("update retirada_v2_itens set devolvido=? where or_id=? and material_id=?",q,id,m.getId());
                if(q.signum()>0)movimento(c,i,q,TipoMovimentacao.DEVOLUCAO_OR,antes,fisico(m),r.motivo());
            }
            var o=c.ordem();o.setStatus("DEVOLVIDA");o.setDataDevolucao(LocalDateTime.now(CalendarioRetirada.ZONA));o.setDevolvidoPor(r.retirante());o.setRecebidoPor(r.entregaPor());o.setAssinaturaRecebimentoBase64(assinatura);ordens.saveAndFlush(o);
            jdbc.update("update retiradas_v2 set devolvida=true where or_id=?",id);
            // Mesmo gerador de devolução do legado: preserva a assinatura recebida e os vínculos históricos.
            byte[] pdf=arquivos.pdf(o.getNumeroOr(),(int)numero(c.estado(),"versao_emitida"),(String)c.estado().get("destino"),locais.findById(numero(c.estado(),"local_id")).orElseThrow().getNome(),referenciaOs(o),itensPdf(o,true),"Recebido por "+r.entregaPor()+"; devolvido por "+r.retirante(),"DEVOLUÇÃO");
            pdf=arquivos.juntar(pdf,pdfLegado.gerarPdf(o),o.getNumeroOr(),(int)numero(c.estado(),"versao_emitida"));
            registrarArquivo(id,(int)numero(c.estado(),"versao_emitida"),"DEVOLUCAO",pdf,"application/pdf","devolucao.pdf",c.ator());
            if(o.getOrdemServico()!=null)fluxo.registrarDevolucao(o.getOrdemServico().getId(),c.ator().getNome());
        });
    }
    @Transactional
    public Map<String,Object> anexar(Long id,Acao r,byte[] bytes,Authentication a) {
        String mime=arquivos.validar(bytes);String hash=arquivos.hash(bytes);
        // Hash dos bytes integra a intenção, impedindo replay da mesma chave com outro comprovante.
        ator(a,OPERADOR);guard.lock();var o=ordens.findById(id).orElseThrow();objeto(o,a);
        return intencao(id+":ANEXAR",r.chave(),Map.of("acao",r,"hash",hash),()->{
            Contexto c=travar(id,r.revisao(),a,OPERADOR);exigir(c.estado().get("entrega_id")!=null&&Set.of("AGUARDANDO_COMPROVANTE","REJEITADO").contains(c.estado().get("documental")),"Comprovante só pode ser anexado após entrega ou rejeição.");
            exigir(r.versao()!=null&&r.versao()==(int)numero(c.estado(),"versao_emitida"),"Versão do documento divergente.");
            long arq=registrarArquivo(id,r.versao(),"COMPROVANTE",bytes,mime,"comprovante-v"+r.versao()+mimeSuffix(mime),c.ator());
            Instant anexado=jdbc.queryForObject("select criado_em from retirada_v2_arquivos where id=?",java.sql.Timestamp.class,arq).toInstant();
            jdbc.update("update retiradas_v2 set documental='ANEXADO',comprovante_ativo=?,prazo_revisao=?,revisao=revisao+1 where or_id=?",arq,java.sql.Timestamp.from(calendario.prazo(anexado)),id);
            evento(id,"COMPROVANTE_ANEXADO",c.ator(),Map.of("arquivoId",arq,"hash",hash,"versao",r.versao()));return detalheInterno(id);
        });
    }
    private String mimeSuffix(String mime){return switch(mime){case "application/pdf"->".pdf";case "image/png"->".png";default->".jpg";};}
    @Transactional
    public Map<String,Object> conferir(Long id,Acao r,Authentication a) {
        return comando(id,r,a,"CONFERIR",REVISOR,c->{
            exigir("ANEXADO".equals(c.estado().get("documental")),"Nenhum comprovante ativo aguardando revisão.");
            exigir(r.arquivoId()!=null&&r.arquivoId().equals(((Number)c.estado().get("comprovante_ativo")).longValue()),"Comprovante ativo mudou.");
            var arq=jdbc.queryForMap("select * from retirada_v2_arquivos where id=? and or_id=? and tipo='COMPROVANTE'",r.arquivoId(),id);
            arquivos.ler((String)arq.get("caminho"),(String)arq.get("hash"));
            exigir(Set.of("CONFERIR","REJEITAR").contains(Objects.toString(r.decisao(),"")),"Decisão inválida.");
            boolean propria=Objects.equals(c.ator().getId(),numero(c.estado(),"operador_entrega"))||Objects.equals(c.ator().getId(),numero(arq,"autor"));
            if(propria){if(c.ator().getPerfilAcesso()!=PerfilAcesso.ADMIN)throw erro(HttpStatus.FORBIDDEN,"Revisor deve ser diferente de quem entregou/anexou.");
                exigir(r.autorrevisao(),"ADMIN deve escolher explicitamente a autorrevisão excepcional.");texto(r.motivo(),"Justificativa da autorrevisão");
            }else exigir(!r.autorrevisao(),"Autorrevisão não se aplica a esta autoria.");
            if("REJEITAR".equals(r.decisao()))texto(r.motivo(),"Motivo da rejeição");
            jdbc.update("update retiradas_v2 set documental=? where or_id=?",r.decisao().equals("CONFERIR")?"CONFERIDO":"REJEITADO",id);
        });
    }
    public record ArquivoPublico(byte[] bytes,String mime,String nome) {}
    @Transactional(readOnly=true)
    public boolean novoRegime(Long id){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from retiradas_v2 where or_id=?)",Boolean.class,id));}
    @Transactional(readOnly=true)
    public ArquivoPublico emissaoAtual(Long id,Authentication a){
        detalhe(id,a);Long arquivo=jdbc.queryForList("select id from retirada_v2_arquivos where or_id=? and tipo='EMISSAO' order by versao desc limit 1",Long.class,id).stream().findFirst().orElseThrow(()->erro(HttpStatus.CONFLICT,"Rascunho ainda não possui documento emitido."));return arquivo(id,arquivo,a);
    }
    @Transactional(readOnly=true)
    public ArquivoPublico arquivo(Long id,Long arquivoId,Authentication a) {
        detalhe(id,a);var rows=jdbc.queryForList("select * from retirada_v2_arquivos where id=? and or_id=?",arquivoId,id);
        if(rows.isEmpty())throw erro(HttpStatus.NOT_FOUND,"Arquivo desta OR não encontrado.");var row=rows.getFirst();return new ArquivoPublico(arquivos.ler((String)row.get("caminho"),(String)row.get("hash")),(String)row.get("mime"),(String)row.get("nome"));
    }
    private long registrarArquivo(Long id,int v,String tipo,byte[] bytes,String mime,String nome,Funcionario f) {
        var arq=arquivos.guardar(bytes,mime);
        return jdbc.queryForObject("insert into retirada_v2_arquivos(or_id,versao,tipo,caminho,hash,mime,nome,autor) values(?,?,?,?,?,?,?,?) returning id",Long.class,id,v,tipo,arq.caminho(),arq.hash(),mime,nome,f.getId());
    }
    private String referenciaOs(OrdemRetirada o){if(o.getOrdemServico()==null)return "AVULSA AUTORIZADA";var os=o.getOrdemServico();return os.getNumeroOs()+(os.getContrato()==null?"":" | Contrato "+os.getContrato().getContrato());}
    private List<OrdemRetiradaItem> ordenados(OrdemRetirada o){return o.getItens().stream().sorted(Comparator.comparing(i->i.getMaterial().getId())).toList();}
    private Material bloquear(OrdemRetiradaItem i){Material m=materiais.findByIdForUpdate(i.getMaterial().getId()).orElseThrow();i.setMaterial(m);return m;}
    private boolean decimal(Material m){return m.getTipoControle()==TipoControleEstoque.FRACIONADO||m.getTipoControle()==TipoControleEstoque.METRAGEM;}
    private BigDecimal valor(BigDecimal v){return v==null?BigDecimal.ZERO:v;}
    private BigDecimal valor(Integer v){return BigDecimal.valueOf(v==null?0:v);}
    private BigDecimal fisico(Material m){return decimal(m)?valor(m.getMetragemDisponivel()):valor(m.getQuantidadeDisponivel());}
    private BigDecimal reservado(Material m){return decimal(m)?valor(m.getMetragemReservada()):valor(m.getQuantidadeReservada());}
    private void setFisico(Material m,BigDecimal v){if(decimal(m))m.setMetragemDisponivel(v);else m.setQuantidadeDisponivel(v.intValueExact());}
    private void setReservado(Material m,BigDecimal v){exigir(v.signum()>=0,"Reserva global negativa.");if(decimal(m))m.setMetragemReservada(v);else m.setQuantidadeReservada(v.intValueExact());}
    private SaldoMaterialLocal local(Material m,Long local){return saldos.findByMaterialIdAndLocalEstoqueIdForUpdate(m.getId(),local).orElseThrow(()->erro(HttpStatus.CONFLICT,"Material sem saldo neste depósito."));}
    private BigDecimal fisicoLocal(Material m,SaldoMaterialLocal s){return decimal(m)?valor(s.getMetragemDisponivel()):valor(s.getQuantidadeDisponivel());}
    private void setFisicoLocal(Material m,SaldoMaterialLocal s,BigDecimal v){if(decimal(m))s.setMetragemDisponivel(v);else s.setQuantidadeDisponivel(v.intValueExact());}
    private void quantidade(Material m,BigDecimal q,boolean positiva){if(q==null||q.signum()<0||(positiva&&q.signum()==0)||q.precision()-q.scale()>10||q.stripTrailingZeros().scale()>(decimal(m)?3:0))throw erro(HttpStatus.UNPROCESSABLE_ENTITY,"Quantidade inválida para "+m.getNome()+" ("+m.getUnidadeMedida()+").");}
    private List<Map<String,Object>> itensPdf(OrdemRetirada o,boolean devolucao){return ordenados(o).stream().map(i->Map.<String,Object>of("materialId",i.getMaterial().getId(),"nome",i.getNomeMaterial(),"unidade",i.getMaterial().getUnidadeMedida().name(),"quantidade",devolucao?i.getQuantidadeDevolvida():i.getQuantidadeSolicitada())).toList();}
    private void movimento(Contexto c,OrdemRetiradaItem i,BigDecimal q,TipoMovimentacao tipo,BigDecimal antes,BigDecimal depois,String motivo){
        Material m=i.getMaterial();MovimentacaoEstoque x=new MovimentacaoEstoque();x.setMaterial(m);x.setOrdemRetirada(c.ordem());x.setOrdemServico(c.ordem().getOrdemServico());x.setComarca(c.ordem().getComarca());x.setFuncionario(c.ator());
        if(c.ordem().getOrdemServico()!=null)x.setProjeto(c.ordem().getOrdemServico().getProjeto());x.setUnidadeMedida(m.getUnidadeMedida());if(decimal(m))x.setMetragem(q);else{x.setQuantidade(q.intValueExact());if(m.getComprimentoPorPeca()!=null)x.setMetragem(q.multiply(m.getComprimentoPorPeca()));}
        x.setTipo(tipo);x.setDataMovimentacao(LocalDateTime.now(CalendarioRetirada.ZONA));x.setSaldoAnterior(antes);x.setSaldoPosterior(depois);x.setMotivo(motivo);x.setObservacao(c.ordem().getNumeroOr()+" | novo fluxo | "+tipo);x.setLancadoPor(c.ator().getNome());
        BigDecimal custo=(BigDecimal)jdbc.queryForMap("select custo from retirada_v2_itens where or_id=? and material_id=?",c.ordem().getId(),m.getId()).get("custo");x.setCustoUnitario(custo);x.setValorTotalMovimentacao(custo.multiply(q).setScale(4,RoundingMode.HALF_UP));x.setCustoEstimado(false);
        String deposito=locais.findById(numero(c.estado(),"local_id")).orElseThrow().getNome();x.setEstoqueOrigem(tipo==TipoMovimentacao.RETIRADA_OR?deposito:(String)c.estado().get("destino"));x.setEstoqueDestino(tipo==TipoMovimentacao.DEVOLUCAO_OR?deposito:(String)c.estado().get("destino"));movimentos.save(x);
    }
}
