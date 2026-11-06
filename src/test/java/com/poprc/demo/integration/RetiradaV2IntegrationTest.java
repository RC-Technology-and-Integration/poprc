package com.poprc.demo.integration;

import com.poprc.demo.dto.RetiradaV2Request.*;
import com.poprc.demo.model.*;
import com.poprc.demo.repository.*;
import com.poprc.demo.security.UsuarioAutenticado;
import com.poprc.demo.service.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

/** Transações reais com commits e duas conexões concorrentes. Somente banco/arquivos descartáveis. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1","app.retiradas-v2.enabled=true","app.security.enabled=true","app.ambiente=teste-local"})
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RetiradaV2IntegrationTest {
 static final com.poprc.demo.support.BancoAberturaDescartavel banco=new com.poprc.demo.support.BancoAberturaDescartavel();
 @org.springframework.test.context.DynamicPropertySource static void bancoIsolado(org.springframework.test.context.DynamicPropertyRegistry p){banco.create();p.add("spring.datasource.url",banco::url);p.add("spring.datasource.username",banco::user);p.add("spring.datasource.password",banco::password);p.add("spring.datasource.hikari.maximum-pool-size",()->4);}

 static String storageAnterior;
 @BeforeAll static void storageIsolado(){storageAnterior=System.getProperty("app.upload.dir");if(storageAnterior==null)System.setProperty("app.upload.dir",Path.of("target","test-uploads").toAbsolutePath().toString());}
 @AfterAll static void restaurarStorage(){if(storageAnterior==null)System.clearProperty("app.upload.dir");}
 @Autowired RetiradaV2Service service;
 @Autowired EstoqueService estoque;
 @Autowired SaldoLocalService saldo;
 @Autowired FuncionarioRepository funcionarios;
 @Autowired MaterialRepository materiais;
 @Autowired OrdemServicoRepository osRepo;
 @Autowired ComarcaRepository comarcas;
 @Autowired ComarcaService obras;
 @Autowired ReservaRetiradaV2Guard guard;
 @Autowired JdbcTemplate jdbc;
 @Autowired ArquivoRetiradaV2 arquivos;
 @Autowired org.springframework.transaction.PlatformTransactionManager transacoes;
 @org.springframework.boot.test.web.server.LocalServerPort int porta;
 @Autowired OrdemRetiradaService legado;
 @Autowired OrdemServicoService osService;
 @Autowired ContratoRepository contratos;
 @Autowired ProjetoRepository projetos;
 @Autowired MaterialItemRepository previstos;
 Authentication admin,estoquista,revisor,tecnico;
 Long material,local;
 String destino;
 @BeforeEach void fixture(){
  banco.assertDestination(jdbc);
  admin=usuario(PerfilAcesso.ADMIN);estoquista=usuario(PerfilAcesso.ESTOQUE);revisor=usuario(PerfilAcesso.SUPERVISOR_TECNICO);tecnico=usuario(PerfilAcesso.TECNICO);
  ReflectionTestUtils.setField(guard,"enabled",true);ReflectionTestUtils.setField(guard,"osIds","");
  Material m=new Material();m.setNome("Material sintético V2");m.setPartNumber("R2-"+UUID.randomUUID());m.setQuantidadeDisponivel(10);m.setCustoMedio(new BigDecimal("2.5000"));m.setLocalizacao("Depósito V2 "+UUID.randomUUID());
  material=estoque.cadastrarMaterial(m).getId();local=saldo.listarSaldos(material).getFirst().getLocalEstoque().getId();destino="TESTE-"+UUID.randomUUID();
 }
 Authentication usuario(PerfilAcesso perfil){var f=new Funcionario();f.setNome("Fictício "+perfil);f.setEmail(UUID.randomUUID()+"@example.invalid");f.setPerfilAcesso(perfil);f=funcionarios.saveAndFlush(f);var u=UsuarioAutenticado.de(f,"LOCAL");return new UsernamePasswordAuthenticationToken(u,null,u.getAuthorities());}
 long id(Map<String,Object> v){return ((Number)v.get("id")).longValue();}
 long rev(Map<String,Object> v){return ((Number)v.get("revisao")).longValue();}
 Acao acao(Map<String,Object> v,BigDecimal q,String motivo){return new Acao(UUID.randomUUID(),rev(v),((Number)v.get("versao_emitida")).intValue(),motivo,q==null?null:List.of(new Efetivo(material,q)),null,null,false,"Entregador fictício","Retirante fictício",null);}
 Rascunho rascunho(Long os,Long previsto,int q){return new Rascunho(UUID.randomUUID(),0,os,local,destino,"Obra inteiramente fictícia","Ensaio descartável","Entrega fictícia","Retirante fictício","Não operacional",List.of(new Item(material,previsto,BigDecimal.valueOf(q))));}
 Map<String,Object> criar(int q){return service.criar(rascunho(null,null,q),estoquista);}
 Map<String,Object> autorizar(Map<String,Object> v){return service.autorizar(id(v),"AVULSA",acao(v,null,"Autorização fictícia específica"),admin);}
 Map<String,Object> emitir(int q){var v=autorizar(criar(q));return service.emitir(id(v),acao(v,null,null),estoquista);}
 void conferirSaldo(int p,int r){var m=materiais.findById(material).orElseThrow();assertThat(m.getQuantidadeDisponivel()).isEqualTo(p);assertThat(m.getQuantidadeReservada()).isEqualTo(r);assertThat(jdbc.queryForObject("select sum(quantidade_disponivel) from saldos_materiais_locais where material_id=?",Integer.class,material)).isEqualTo(p);assertThat(jdbc.queryForObject("select sum(quantidade_reservada) from saldos_materiais_locais where material_id=?",Integer.class,material)).isEqualTo(r);}
 byte[] png()throws Exception {var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
 Long criarLegada(int q){
  var os=new OrdemServico();os.setNumeroOs("TESTE-LEGADO-"+UUID.randomUUID());os.setStatus(StatusOS.AGUARDANDO_RETIRADA);os=osRepo.saveAndFlush(os);
  var c=new Comarca();c.setNomeComarca("Obra legada sintética");c.setOrdemServico(os);c=comarcas.saveAndFlush(c);obras.adicionarMaterialPrevisto(c.getId(),material,"Material",BigDecimal.valueOf(q));
  c.setMateriais(previstos.findByComarcaIdOrderByIdAsc(c.getId()));return legado.criarParaOrdemServico(os,c,"Teste isolado").getId();
 }
 <T>T concorrente(CyclicBarrier barreira,Set<Integer> pids,java.util.function.Supplier<T> acao){return new org.springframework.transaction.support.TransactionTemplate(transacoes).execute(status->{pids.add(jdbc.queryForObject("select pg_backend_pid()",Integer.class));try{barreira.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}return acao.get();});}
 @Test void rascunhoEmissaoEntregaRetryEPayloadDivergente(){
  var draft=criar(4);conferirSaldo(10,0);var v=autorizar(draft);v=service.emitir(id(v),acao(v,null,null),estoquista);conferirSaldo(10,4);
  var a=acao(v,new BigDecimal("3"),"Parcial: um item não entregue");var entregue=service.entregar(id(v),a,estoquista);conferirSaldo(7,0);
  assertThat(entregue.get("fisico")).isEqualTo("ENTREGUE_PARCIAL");assertThat(service.entregar(id(v),a,estoquista).get("entrega_id")).isEqualTo(entregue.get("entrega_id"));
  var diferente=new Acao(a.chave(),a.revisao(),a.versao(),a.motivo(),List.of(new Efetivo(material,BigDecimal.ONE)),null,null,false,a.entregaPor(),a.retirante(),null);
  Long oid=id(v);assertThatThrownBy(()->service.entregar(oid,diferente,estoquista)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("conteúdo diferente");
  assertThat(jdbc.queryForObject("select count(*) from movimentacoes_estoque where ordem_retirada_id=? and tipo='RETIRADA_OR'",Long.class,oid)).isEqualTo(1L);conferirSaldo(7,0);
 }
 @Test void mesmaEntregaEmDuasConexoesTemUmaBaixa()throws Exception {
  var v=emitir(4);var a=acao(v,new BigDecimal("4"),null);var inicio=new CyclicBarrier(2);Set<Integer> pids=ConcurrentHashMap.newKeySet();
  try(var pool=Executors.newFixedThreadPool(2)){
   var t1=pool.submit(()->concorrente(inicio,pids,()->service.entregar(id(v),a,estoquista)));var t2=pool.submit(()->concorrente(inicio,pids,()->service.entregar(id(v),a,estoquista)));
   assertThat(t1.get(30,TimeUnit.SECONDS).get("entrega_id")).isEqualTo(t2.get(30,TimeUnit.SECONDS).get("entrega_id"));
  }assertThat(pids).hasSize(2);System.out.println("CONCORRENCIA_V2_PIDS="+pids);conferirSaldo(6,0);
 }
 @Test void chavesDistintasNaoEntregamDuasVezes(){var v=emitir(4);service.entregar(id(v),acao(v,new BigDecimal("4"),null),estoquista);assertThatThrownBy(()->service.entregar(id(v),acao(v,new BigDecimal("4"),null),estoquista)).isInstanceOf(ResponseStatusException.class);conferirSaldo(6,0);}
 @Test void ultimaReservaDisputadaPorDuasOrs()throws Exception {
  var a=autorizar(criar(7));var b=autorizar(criar(7));var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)) {var tarefas=List.of(a,b).stream().map(v->pool.submit(()->{start.await();try{service.emitir(id(v),acao(v,null,null),estoquista);return "OK";}catch(ResponseStatusException e){return "CONFLITO";}})).toList();start.countDown();assertThat(List.of(tarefas.get(0).get(30,TimeUnit.SECONDS),tarefas.get(1).get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","CONFLITO");}conferirSaldo(10,7);
 }
 @Test void cancelamentoERevisaoPreservamDocumentos(){
  var v=emitir(4);Long oid=id(v);v=service.revisarOuCancelar(oid,acao(v,null,"Corrigir observação"),estoquista,false);conferirSaldo(10,0);
  assertThat(v.get("arquivos")).asList().hasSize(1);v=autorizar(v);v=service.emitir(oid,acao(v,null,null),estoquista);assertThat(v.get("versao_emitida")).isEqualTo(2);assertThat(v.get("arquivos")).asList().hasSize(2);
  service.revisarOuCancelar(oid,acao(v,null,"Cancelamento de ensaio"),estoquista,true);conferirSaldo(10,0);
 }
 @Test void documentosRejeicaoReenvioConferenciaNaoMovimentamEstoque()throws Exception{
  var v=emitir(4);v=service.entregar(id(v),acao(v,new BigDecimal("3"),"Parcial"),estoquista);var pendente=v;byte[] imagem=png();
  v=service.anexar(id(v),acao(v,null,null),imagem,estoquista);Long oid=id(v),arquivo=((Number)v.get("comprovanteAtivo")).longValue();conferirSaldo(7,0);
  var revisar=new Acao(UUID.randomUUID(),rev(v),1,"Foto insuficiente",null,arquivo,"REJEITAR",false,null,null,null);v=service.conferir(oid,revisar,revisor);assertThat(v.get("documental")).isEqualTo("REJEITADO");
  v=service.anexar(oid,acao(v,null,null),imagem,estoquista);arquivo=((Number)v.get("comprovanteAtivo")).longValue();
  var confirmar=new Acao(UUID.randomUUID(),rev(v),1,null,null,arquivo,"CONFERIR",false,null,null,null);v=service.conferir(oid,confirmar,revisor);assertThat(v.get("documental")).isEqualTo("CONFERIDO");
  assertThat(v.get("arquivos")).asList().hasSize(3);conferirSaldo(7,0);
  assertThat(jdbc.queryForObject("select count(*) from movimentacoes_estoque where ordem_retirada_id=?",Long.class,oid)).isEqualTo(1L);
 }
 @Test void adminAutorrevisaSomenteExplicitamenteComJustificativa()throws Exception{
  var v=emitir(2);v=service.entregar(id(v),acao(v,BigDecimal.ONE,"Parcial"),admin);v=service.anexar(id(v),acao(v,null,null),png(),admin);long oid=id(v);long arq=((Number)v.get("comprovanteAtivo")).longValue();long revisao=rev(v);
  assertThatThrownBy(()->service.conferir(oid,new Acao(UUID.randomUUID(),revisao,1,null,null,arq,"CONFERIR",false,null,null,null),admin)).hasMessageContaining("explicitamente");
  assertThatThrownBy(()->service.conferir(oid,new Acao(UUID.randomUUID(),revisao,1,"",null,arq,"CONFERIR",true,null,null,null),admin)).hasMessageContaining("Justificativa");
  assertThat(service.conferir(oid,new Acao(UUID.randomUUID(),revisao,1,"Ensaio excepcional justificado",null,arq,"CONFERIR",true,null,null,null),admin).get("documental")).isEqualTo("CONFERIDO");
 }
 @Test void outroPerfilNaoGanhaExcecaoDeAutorrevisao()throws Exception{
  var v=emitir(2);v=service.entregar(id(v),acao(v,BigDecimal.ONE,"Parcial"),estoquista);v=service.anexar(id(v),acao(v,null,null),png(),estoquista);
  long oid=id(v);var a=new Acao(UUID.randomUUID(),rev(v),1,"Não permitido",null,((Number)v.get("comprovanteAtivo")).longValue(),"CONFERIR",true,null,null,null);
  assertThatThrownBy(()->service.conferir(oid,a,estoquista)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("perfil");
  assertThatThrownBy(()->service.conferir(oid,a,revisor)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("não se aplica");
 }
 @Test void flagDesligadaNaoInterrompeObrigacoes()throws Exception{
  var v=emitir(2);ReflectionTestUtils.setField(guard,"enabled",false);
  assertThatThrownBy(()->criar(1)).hasMessageContaining("desabilitadas");v=service.entregar(id(v),acao(v,new BigDecimal("2"),null),estoquista);v=service.anexar(id(v),acao(v,null,null),png(),estoquista);assertThat(v.get("documental")).isEqualTo("ANEXADO");conferirSaldo(8,0);
 }
 @Test void arquivosProtegidosEPermissoesHttp()throws Exception{
  var v=emitir(2);long oid=id(v);long arq=((Number)((Map<?,?>)((List<?>)v.get("arquivos")).getFirst()).get("id")).longValue();String arquivo="/api/ordens-retirada/v2/"+oid+"/arquivos/"+arq;
  var anon=java.net.http.HttpClient.newHttpClient();assertThat(anon.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+porta+arquivo)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
  assertThat(http(loginHttp(tecnico,"93541134780"),"GET",arquivo,null,true).statusCode()).isEqualTo(403);
  var adm=loginHttp(admin,"52998224725");assertThat(http(adm,"GET","/uploads/.retiradas-v2/qualquer",null,true).statusCode()).isEqualTo(403);
  assertThat(http(loginHttp(estoquista,"11144477735"),"POST","/api/ordens-retirada/v2/"+oid+"/conferencia","{}",true).statusCode()).isEqualTo(403);
  assertThat(http(adm,"GET",arquivo,null,true).statusCode()).isEqualTo(200);
  assertThat(service.arquivo(oid,arq,admin).bytes()).startsWith((byte)'%',(byte)'P',(byte)'D',(byte)'F');
  var outra=criar(1);assertThatThrownBy(()->service.arquivo(id(outra),arq,admin)).hasMessageContaining("não encontrado");
 }
 @Test void parcialDeOsReaproveitaReservaEConservaSobra(){
  criarLegada(2);
  OrdemServico os=new OrdemServico();os.setNumeroOs("TESTE-V2-"+UUID.randomUUID());os.setStatus(StatusOS.AGUARDANDO_RETIRADA);os=osRepo.saveAndFlush(os);
  Comarca c=new Comarca();c.setNomeComarca("Obra de ensaio V2");c.setOrdemServico(os);c=comarcas.saveAndFlush(c);
  obras.adicionarMaterialPrevisto(c.getId(),material,"Material",new BigDecimal("4"));Long previsto=jdbc.queryForObject("select id from comarca_materiais where comarca_id=?",Long.class,c.getId());
  ReflectionTestUtils.setField(guard,"osIds",os.getId().toString());var v=service.criar(rascunho(os.getId(),previsto,4),estoquista);conferirSaldo(10,6);
  v=service.emitir(id(v),acao(v,null,null),estoquista);conferirSaldo(10,6);service.revisarOuCancelar(id(v),acao(v,null,"Cancelamento antes da entrega"),estoquista,true);conferirSaldo(10,6);
  v=service.criar(rascunho(os.getId(),previsto,4),estoquista);v=service.emitir(id(v),acao(v,null,null),estoquista);conferirSaldo(10,6);v=service.entregar(id(v),acao(v,new BigDecimal("3"),"Parcial"),estoquista);conferirSaldo(7,3);
  assertThat(jdbc.queryForObject("select quantidade_retirada_v2 from comarca_materiais where id=?",BigDecimal.class,previsto)).isEqualByComparingTo("3");
 }
 @Test void reservaIdentificadaNaoPodeSerConsumidaPorAjusteLegado(){var v=emitir(8);assertThatThrownBy(()->estoque.transferirLocalizacao(material,local,saldo.cadastrarLocal("Destino "+UUID.randomUUID(),"Ensaio").getId(),new BigDecimal("4"),"Teste","Teste","Teste")).isInstanceOf(RuntimeException.class);conferirSaldo(10,8);}
 @Test void falhaDeStorageRollbackPermiteRetomarMesmaIntencao()throws Exception{
  var v=autorizar(criar(4));var a=acao(v,null,null);String original=System.getProperty("app.upload.dir");Path falha=Files.createTempFile("r2-storage-fault-",".test");
  try{System.setProperty("app.upload.dir",falha.toString());assertThatThrownBy(()->service.emitir(id(v),a,estoquista)).isInstanceOf(ResponseStatusException.class);conferirSaldo(10,0);}finally{if(original==null)System.clearProperty("app.upload.dir");else System.setProperty("app.upload.dir",original);}
  assertThat(service.emitir(id(v),a,estoquista).get("fisico")).isEqualTo("RESERVADA");conferirSaldo(10,4);
 }
 @Test void devolucaoExigeAssinaturaRetornoFerramentaEConservaPendencia()throws Exception{
  var m=materiais.findById(material).orElseThrow();m.setCategoria("FERRAMENTA");materiais.saveAndFlush(m);
  var v=emitir(2);v=service.entregar(id(v),acao(v,new BigDecimal("2"),null),estoquista);long oid=id(v);long revisao=rev(v);
  String assinatura="data:image/png;base64,"+Base64.getEncoder().encodeToString(png());
  var incompleta=new Acao(UUID.randomUUID(),revisao,1,"Retorno de ensaio",List.of(new Efetivo(material,BigDecimal.ONE)),null,null,false,"Recebedor fictício","Devolvedor fictício",assinatura);
  assertThatThrownBy(()->service.devolver(oid,incompleta,estoquista)).hasMessageContaining("integralmente");conferirSaldo(8,0);
  var completa=new Acao(UUID.randomUUID(),revisao,1,"Retorno de ensaio",List.of(new Efetivo(material,new BigDecimal("2"))),null,null,false,"Recebedor fictício","Devolvedor fictício",assinatura);
  v=service.devolver(oid,completa,estoquista);assertThat(v.get("devolvida")).isEqualTo(true);assertThat(v.get("documental")).isEqualTo("AGUARDANDO_COMPROVANTE");conferirSaldo(10,0);
  service.devolver(oid,completa,estoquista);conferirSaldo(10,0);
 }
 @Test void excecaoIndividualPreservaPendenciaEExigeNovaAutorizacaoSeEditar(){
  var anterior=emitir(2);service.entregar(id(anterior),acao(anterior,new BigDecimal("2"),null),estoquista);
  var v=autorizar(criar(1));long oid=id(v);var semExcecao=acao(v,null,null);assertThatThrownBy(()->service.emitir(oid,semExcecao,estoquista)).hasMessageContaining("EXCECAO_DOCUMENTAL");
  v=service.autorizar(oid,"EXCECAO_DOCUMENTAL",acao(v,null,"Necessidade operacional específica"),admin);v=service.emitir(oid,acao(v,null,null),estoquista);
  assertThat(service.detalhe(id(anterior),admin).get("documental")).isEqualTo("AGUARDANDO_COMPROVANTE");assertThat(v.get("fisico")).isEqualTo("RESERVADA");conferirSaldo(8,1);
 }
 @Test void tiposEPrecisaoNaoSaoConvertidos(){
  var m=materiais.findById(material).orElseThrow();m.setQuantidadeDisponivel(0);m.setTipoControle(TipoControleEstoque.FRACIONADO);m.setUnidadeMedida(UnidadeMedida.UNIDADE);m.setMetragemDisponivel(new BigDecimal("10"));materiais.saveAndFlush(m);
  jdbc.update("update saldos_materiais_locais set quantidade_disponivel=0,metragem_disponivel=10 where material_id=?",material);
  var r=new Rascunho(UUID.randomUUID(),0,null,local,destino,"Destino fictício","Ensaio","Entrega","Retirante","",List.of(new Item(material,null,new BigDecimal("0.125"))));
  var v=service.criar(r,estoquista);v=autorizar(v);v=service.emitir(id(v),acao(v,null,null),estoquista);v=service.entregar(id(v),acao(v,new BigDecimal("0.125"),null),estoquista);
  assertThat(materiais.findById(material).orElseThrow().getMetragemDisponivel()).isEqualByComparingTo("9.875");assertThat(materiais.findById(material).orElseThrow().getUnidadeMedida()).isEqualTo(UnidadeMedida.UNIDADE);
  var ruim=new Rascunho(UUID.randomUUID(),0,null,local,destino,"Destino fictício","Ensaio","Entrega","Retirante","",List.of(new Item(material,null,new BigDecimal("0.0001"))));assertThatThrownBy(()->service.criar(ruim,estoquista)).hasMessageContaining("Quantidade inválida");
 }
 @Test void arquivoAusenteNaoERegeneradoESaldoNaoMuda()throws Exception{
  var v=emitir(2);long oid=id(v);long arq=((Number)((Map<?,?>)((List<?>)v.get("arquivos")).getFirst()).get("id")).longValue();
  String caminho=jdbc.queryForObject("select caminho from retirada_v2_arquivos where id=?",String.class,arq);Path arquivo=arquivos.raiz().resolve(caminho);byte[] antes=Files.readAllBytes(arquivo);
  Path preservado=arquivo.resolveSibling(caminho+".ensaio-ausente");Files.move(arquivo,preservado);
  try{assertThatThrownBy(()->service.arquivo(oid,arq,admin)).hasMessageContaining("não será regenerado");conferirSaldo(10,2);}finally{Files.move(preservado,arquivo);}
  assertThat(service.arquivo(oid,arq,admin).bytes()).containsExactly(antes);
 }
 @Test void snapshotDoPdfMultipaginaTemCabecalhoERodapeEmTodasAsPaginas()throws Exception{
  var itens=new ArrayList<Map<String,Object>>();for(int i=1;i<=60;i++)itens.add(Map.of("materialId",i,"nome","Material sintético "+i+" descrição para ensaio de impressão multipágina","unidade","UNIDADE","quantidade",new BigDecimal("0.125")));
  byte[] pdf=arquivos.pdf("OR-TESTE-60",1,"Obra fictícia","Depósito fictício","AVULSA",itens,"Cronograma de ensaio fictício","EMISSÃO");
  try(var doc=org.apache.pdfbox.Loader.loadPDF(pdf)){assertThat(doc.getNumberOfPages()).isGreaterThan(1);var stripper=new org.apache.pdfbox.text.PDFTextStripper();for(int p=1;p<=doc.getNumberOfPages();p++){stripper.setStartPage(p);stripper.setEndPage(p);String texto=stripper.getText(doc);assertThat(texto).contains("OR-TESTE-60","SEM VALIDADE OPERACIONAL","Página "+p);if(texto.contains("Material sintético"))assertThat(texto).contains("Código");}}
  String raiz=System.getProperty("app.upload.dir");Files.createDirectories(Path.of(raiz));Files.write(Path.of(raiz).resolve("PDF_TESTE_60_ITENS.pdf"),pdf);
 }
 @Test void baixaLegadaEV2ConcorrentesNaoInvademReservas()throws Exception{
  Long antiga=criarLegada(2);var v=emitir(4);conferirSaldo(10,6);
  var request=new com.poprc.demo.dto.ExecutarOrdemRetiradaRequest();request.setConferidoPor("Teste legado");request.setLevadoPor("Teste legado");String assinatura="data:image/png;base64,"+Base64.getEncoder().encodeToString(png());request.setAssinaturaConferenteBase64(assinatura);request.setAssinaturaRetiranteBase64(assinatura);
  var inicio=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var t1=pool.submit(()->{inicio.await();return legado.executarRetirada(antiga,request);});var t2=pool.submit(()->{inicio.await();return service.entregar(id(v),acao(v,new BigDecimal("4"),null),estoquista);});inicio.countDown();t1.get(30,TimeUnit.SECONDS);t2.get(30,TimeUnit.SECONDS);}conferirSaldo(4,0);
 }
 @Test void falhaNoMovimentoRollbackDaBaixaPermiteRetomar(){
  var v=emitir(3);var a=acao(v,new BigDecimal("3"),null);long oid=id(v);String nome="r2_falha_"+UUID.randomUUID().toString().replace("-","");
  jdbc.execute("create function "+nome+"() returns trigger language plpgsql as $$ begin if new.ordem_retirada_id="+oid+" then raise exception 'falha sintética de movimento'; end if; return new; end $$");
  jdbc.execute("create trigger "+nome+" before insert on movimentacoes_estoque for each row execute function "+nome+"()");
  try{assertThatThrownBy(()->service.entregar(oid,a,estoquista)).isInstanceOf(RuntimeException.class);conferirSaldo(10,3);assertThat(service.detalhe(oid,admin).get("fisico")).isEqualTo("RESERVADA");}finally{jdbc.execute("drop trigger "+nome+" on movimentacoes_estoque");jdbc.execute("drop function "+nome+"()");}
  service.entregar(oid,a,estoquista);conferirSaldo(7,0);
 }
 @Test @Order(Integer.MAX_VALUE) void fixtureNavegavelUsaLoginRealEDadosSomenteSinteticos()throws Exception{
  var encoder=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);var contas=new ArrayList<Map<String,Object>>();
  String[] cpfs={"52998224725","11144477735","12345678909"};Authentication[] atores={admin,estoquista,revisor};
  for(int i=0;i<atores.length;i++){var u=(UsuarioAutenticado)atores[i].getPrincipal();var f=funcionarios.findById(u.getFuncionarioId()).orElseThrow();jdbc.update("update funcionarios set cpf=null where cpf=?",cpfs[i]);f.setCpf(cpfs[i]);f.setSenhaHash(encoder.encode("RetiradasTeste2026!"));funcionarios.saveAndFlush(f);contas.add(Map.of("cpf",cpfs[i],"id",f.getId(),"perfil",f.getPerfilAcesso().name()));}
  var mOs=new Material();mOs.setNome("Material sintético da OS");mOs.setPartNumber("UI-OS-"+UUID.randomUUID());mOs.setQuantidadeDisponivel(10);mOs.setLocalizacao(saldo.listarLocais().stream().filter(l->l.getId().equals(local)).findFirst().orElseThrow().getNome());mOs=estoque.cadastrarMaterial(mOs);var osUi=criarOsReal(mOs.getId());Long comarcaUi=jdbc.queryForObject("select id from comarcas where ordem_servico_id=?",Long.class,osUi.getId());obras.salvarFotoVistoria(comarcaUi,new org.springframework.mock.web.MockMultipartFile("foto","sintetica.png","image/png",png()));obras.salvarAssinaturaVistoria(comarcaUi,"data:image/png;base64,"+Base64.getEncoder().encodeToString(png()));obras.avancarParaInfraestrutura(comarcaUi);
  var v=emitir(2);var out=new java.util.LinkedHashMap<String,Object>();out.putAll(Map.of("databaseUrl",banco.url(),"databaseUser",banco.user(),"contas",contas,"orId",id(v),"materialId",material,"localId",local,"destinoId",destino));out.put("osId",osUi.getId());out.put("osMaterialId",mOs.getId());
  Files.writeString(Path.of(System.getProperty("app.upload.dir")).resolve("FIXTURE_UI.json"),new tools.jackson.databind.ObjectMapper().writeValueAsString(out));
  var imagem=new java.awt.image.BufferedImage(600,400,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=imagem.createGraphics();g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,600,400);g.setColor(java.awt.Color.BLACK);g.drawString("TESTE - DOCUMENTO SINTETICO SEM ASSINATURAS",20,30);g.dispose();javax.imageio.ImageIO.write(imagem,"png",Path.of(System.getProperty("app.upload.dir")).resolve("COMPROVANTE_SINTETICO.png").toFile());
 }
 record SessaoHttp(java.net.http.HttpClient client,String token){}
 SessaoHttp loginHttp(Authentication auth,String cpf)throws Exception{
  var f=funcionarios.findById(((UsuarioAutenticado)auth.getPrincipal()).getFuncionarioId()).orElseThrow();jdbc.update("update funcionarios set cpf=null where cpf=?",cpf);f.setCpf(cpf);f.setSenhaHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("RetiradasTeste2026!"));funcionarios.saveAndFlush(f);
  var cookies=new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL);var client=java.net.http.HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(java.time.Duration.ofSeconds(10)).build();String base="http://127.0.0.1:"+porta;
  var login=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/auth/login")).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"cpf\":\""+cpf+"\",\"senha\":\"RetiradasTeste2026!\"}")).build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
  var resposta=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/auth/csrf")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());String token=new tools.jackson.databind.ObjectMapper().readTree(resposta.body()).path("token").asText();return new SessaoHttp(client,token);
 }
 java.net.http.HttpResponse<String> http(SessaoHttp s,String method,String path,String body,boolean csrf)throws Exception{var r=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+porta+path)).timeout(java.time.Duration.ofSeconds(20)).header("Content-Type","application/json");if(csrf)r.header("X-XSRF-TOKEN",s.token());r.method(method,body==null?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofString(body));return s.client().send(r.build(),java.net.http.HttpResponse.BodyHandlers.ofString());}
 @Test void comandoHttpSemOpcaoDeAutorrevisaoNaoFalhaNaDesserializacao()throws Exception{
  var v=criar(1);String url="/api/ordens-retirada/v2/"+id(v)+"/autorizacoes/AVULSA";String payload="{\"chave\":\""+UUID.randomUUID()+"\",\"revisao\":0,\"motivo\":\"Ensaio específico\"}";var sessao=loginHttp(admin,"52998224725");
  assertThat(http(sessao,"POST",url,payload,false).statusCode()).isEqualTo(403);
  var resposta=http(sessao,"POST",url,payload,true);assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(200);
 }
 @Test void perfilSupervisorNaoPodeAutorrevisarMesmoComMotivo()throws Exception{
  var v=emitir(1);v=service.entregar(id(v),acao(v,BigDecimal.ONE,null),admin);v=service.anexar(id(v),acao(v,null,null),png(),admin);long oid=id(v);
  var u=(UsuarioAutenticado)admin.getPrincipal();var f=funcionarios.findById(u.getFuncionarioId()).orElseThrow();f.setPerfilAcesso(PerfilAcesso.SUPERVISOR_TECNICO);funcionarios.saveAndFlush(f);var atual=UsuarioAutenticado.de(f,"LOCAL");Authentication sup=new UsernamePasswordAuthenticationToken(atual,null,atual.getAuthorities());
  var a=new Acao(UUID.randomUUID(),rev(v),1,"Justificativa não concede exceção a SUP",null,((Number)v.get("comprovanteAtivo")).longValue(),"CONFERIR",true,null,null,null);
  assertThatThrownBy(()->service.conferir(oid,a,sup)).hasMessageContaining("diferente");
 }
 @Test void prazosPersistidosContamTresDiasUteisSemBaixaDocumental()throws Exception{
  var v=emitir(1);v=service.entregar(id(v),acao(v,BigDecimal.ONE,null),estoquista);long oid=id(v);var c=new CalendarioRetirada("");
  assertThat(java.time.Instant.parse((String)v.get("prazo_anexacao"))).isEqualTo(c.prazo(java.time.Instant.parse((String)v.get("entregue_em"))));
  v=service.anexar(oid,acao(v,null,null),png(),estoquista);var emitido=jdbc.queryForObject("select criado_em from retirada_v2_arquivos where id=?",java.sql.Timestamp.class,v.get("comprovanteAtivo"));assertThat(java.time.Instant.parse((String)v.get("prazo_revisao"))).isEqualTo(c.prazo(emitido.toInstant()));conferirSaldo(9,0);
 }
 @Test void editarConteudoInvalidaAutorizacaoSemReescreverAutoriaOuEscopo(){
  var v=autorizar(criar(1));long oid=id(v);var edit=new Rascunho(UUID.randomUUID(),rev(v),null,local,destino,"Destino editado","Motivo","Entrega","Retirante","Observação alterada",List.of(new Item(material,null,BigDecimal.ONE)));v=service.editar(oid,edit,estoquista);
  assertThat(jdbc.queryForObject("select revisao from retirada_v2_autorizacoes where or_id=?",Long.class,oid)).isZero();var emitir=acao(v,null,null);assertThatThrownBy(()->service.emitir(oid,emitir,estoquista)).hasMessageContaining("AVULSA");conferirSaldo(10,0);
 }
 @Test void conversaoDeUnidadeNaoPassaNemPorEscritorDireto(){
  emitir(2);assertThatThrownBy(()->jdbc.update("update materiais set unidade_medida='PECA' where id=?",material)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(materiais.findById(material).orElseThrow().getUnidadeMedida()).isEqualTo(UnidadeMedida.UNIDADE);conferirSaldo(10,2);
 }
 @Test void custoDaBaixaSegueMediaAtualERetornoPreservaCustoHistorico()throws Exception{
  var v=emitir(2);jdbc.update("update materiais set custo_medio=3.5 where id=?",material);v=service.entregar(id(v),acao(v,BigDecimal.ONE,"Parcial"),estoquista);long oid=id(v);
  assertThat(jdbc.queryForObject("select custo_unitario from movimentacoes_estoque where ordem_retirada_id=? and tipo='RETIRADA_OR'",BigDecimal.class,oid)).isEqualByComparingTo("3.5");
  jdbc.update("update materiais set custo_medio=4 where id=?",material);var a=new Acao(UUID.randomUUID(),rev(v),1,"Retorno",List.of(new Efetivo(material,BigDecimal.ONE)),null,null,false,"Recebedor","Devolvedor","data:image/png;base64,"+Base64.getEncoder().encodeToString(png()));service.devolver(oid,a,estoquista);
  assertThat(jdbc.queryForObject("select custo_unitario from movimentacoes_estoque where ordem_retirada_id=? and tipo='DEVOLUCAO_OR'",BigDecimal.class,oid)).isEqualByComparingTo("3.5");conferirSaldo(10,0);
 }
 @Test void vetorCompletoDeVariosMateriaisRespeitaPrecisaoESaldoPorDeposito(){
  var segundo=new Material();segundo.setNome("Fracionado sintético");segundo.setPartNumber("V2-MULTI-"+UUID.randomUUID());segundo.setTipoControle(TipoControleEstoque.FRACIONADO);segundo.setUnidadeMedida(UnidadeMedida.UNIDADE);segundo.setMetragemDisponivel(new BigDecimal("10.125"));segundo.setQuantidadeDisponivel(0);segundo.setLocalizacao(saldo.listarLocais().stream().filter(l->l.getId().equals(local)).findFirst().orElseThrow().getNome());Long mid=estoque.cadastrarMaterial(segundo).getId();
  var r=new Rascunho(UUID.randomUUID(),0,null,local,destino,"Destino sintético","Ensaio","Entrega","Retirante","",List.of(new Item(material,null,new BigDecimal("2")),new Item(mid,null,new BigDecimal("0.125"))));
  var v=service.criar(r,estoquista);v=autorizar(v);v=service.emitir(id(v),acao(v,null,null),estoquista);long oid=id(v);var incompleta=acao(v,BigDecimal.ONE,"Parcial");assertThatThrownBy(()->service.entregar(oid,incompleta,estoquista)).hasMessageContaining("todos os itens");
  var completa=new Acao(UUID.randomUUID(),rev(v),1,"Parcial de um dos itens",List.of(new Efetivo(material,BigDecimal.ONE),new Efetivo(mid,new BigDecimal("0.125"))),null,null,false,"Entrega","Retirante",null);service.entregar(oid,completa,estoquista);conferirSaldo(9,0);assertThat(materiais.findById(mid).orElseThrow().getMetragemDisponivel()).isEqualByComparingTo("10.000");
 }
 @Test void multipartNoLimiteDeArquivoPreservaMargemParaMetadados()throws Exception{
  var v=emitir(1);v=service.entregar(id(v),acao(v,BigDecimal.ONE,null),estoquista);var s=loginHttp(estoquista,"11144477735");String boundary="R2"+UUID.randomUUID();var out=new java.io.ByteArrayOutputStream();
  String meta=new tools.jackson.databind.ObjectMapper().writeValueAsString(acao(v,null,null));out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"acao\"\r\nContent-Type: application/json\r\n\r\n"+meta+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"sintetico.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));out.write(Arrays.copyOf(png(),10*1024*1024));out.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  var r=s.client().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+porta+"/api/ordens-retirada/v2/"+id(v)+"/comprovantes")).header("Content-Type","multipart/form-data; boundary="+boundary).header("X-XSRF-TOKEN",s.token()).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).as(r.body()).isEqualTo(200);assertThat(r.body()).contains("ANEXADO");conferirSaldo(9,0);
 }
 OrdemServico criarOsReal(Long materialId)throws Exception{
  var contrato=new Contrato();contrato.setContrato("V2-CONTRATO-"+UUID.randomUUID());contrato.setCliente("Cliente inteiramente fictício");contrato.setVigenciaInicio(java.time.LocalDate.now());contrato.setVigenciaFim(java.time.LocalDate.now().plusYears(1));contrato=contratos.saveAndFlush(contrato);
  var projeto=new Projeto();projeto.setContrato(contrato);projeto.setResponsavel(funcionarios.findById(((UsuarioAutenticado)tecnico.getPrincipal()).getFuncionarioId()).orElseThrow());projeto.setDataInicio(java.time.LocalDate.now());projeto.setStatus(ProjetoStatus.EM_ANDAMENTO);projeto=projetos.saveAndFlush(projeto);
  var comarca=new Comarca();comarca.setNomeComarca("Obra fictícia via criação normal da OS");comarca.setProjeto(projeto);comarca=comarcas.saveAndFlush(comarca);
  var r=new com.poprc.demo.dto.CriarOrdemServicoRequest();r.setContratoId(contrato.getId());r.setProjetoId(projeto.getId());r.setDescricao("Jornada sintética do novo fluxo");r.setDataHoraInicio(java.time.LocalDateTime.now());r.setDataHoraFim(java.time.LocalDateTime.now().plusHours(4));r.setDeadline(java.time.LocalDateTime.now().plusDays(1));
  var item=new com.poprc.demo.dto.CriarOrdemServicoRequest.MaterialPrevistoRequest();item.setMaterialId(materialId);item.setQuantidadePrevista(new BigDecimal("4"));r.setMateriais(List.of(item));return osService.criar(r);
 }
 @Test void criacaoNormalDaOsNaoGeraOrLegadaDuplicadaEVistoriaContinuaObrigatoria()throws Exception{
  var os=criarOsReal(material);conferirSaldo(10,4);assertThat(jdbc.queryForObject("select count(*) from ordens_retirada where ordem_servico_id=?",Long.class,os.getId())).isZero();
  Long previsto=jdbc.queryForObject("select i.id from comarca_materiais i join comarcas c on c.id=i.comarca_id where c.ordem_servico_id=?",Long.class,os.getId());var v=service.criar(rascunho(os.getId(),previsto,4),estoquista);v=service.emitir(id(v),acao(v,null,null),estoquista);conferirSaldo(10,4);
  long oid=id(v);long arquivoId=((Number)((Map<?,?>)((List<?>)v.get("arquivos")).getFirst()).get("id")).longValue();try(var documento=org.apache.pdfbox.Loader.loadPDF(service.arquivo(oid,arquivoId,admin).bytes())){assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(documento)).contains("Contrato V2-CONTRATO-","Responsáveis indicados");}assertThat(http(loginHttp(tecnico,"93541134780"),"GET","/api/ordens-retirada/v2/"+oid+"/arquivos/"+arquivoId,null,true).statusCode()).isEqualTo(200);var entrega=acao(v,new BigDecimal("4"),null);assertThatThrownBy(()->service.entregar(oid,entrega,estoquista)).hasMessageContaining("vistoria");conferirSaldo(10,4);
  Long comarca=jdbc.queryForObject("select id from comarcas where ordem_servico_id=?",Long.class,os.getId());
  obras.salvarFotoVistoria(comarca,new org.springframework.mock.web.MockMultipartFile("foto","sintetica.png","image/png",png()));obras.salvarAssinaturaVistoria(comarca,"data:image/png;base64,"+Base64.getEncoder().encodeToString(png()));obras.avancarParaInfraestrutura(comarca);
  service.entregar(oid,entrega,estoquista);conferirSaldo(6,0);assertThat(jdbc.queryForObject("select count(*) from ordens_retirada where ordem_servico_id=?",Long.class,os.getId())).isEqualTo(1L);
 }
 @Test void reservaPropriaSemSaldoLivrePodeSerEntregue(){
  jdbc.update("update materiais set quantidade_disponivel=4 where id=?",material);jdbc.update("update saldos_materiais_locais set quantidade_disponivel=4 where material_id=?",material);var v=emitir(4);conferirSaldo(4,4);service.entregar(id(v),acao(v,new BigDecimal("4"),null),estoquista);conferirSaldo(0,0);
 }
 @Test void corridaEntregaCancelamentoTemUmResultado()throws Exception{
  var v=emitir(4);var entrega=acao(v,new BigDecimal("4"),null);var cancelamento=acao(v,null,"Cancelamento concorrente de ensaio");var inicio=new CyclicBarrier(2);Set<Integer> pids=ConcurrentHashMap.newKeySet();
  try(var pool=Executors.newFixedThreadPool(2)){
   var t1=pool.submit(()->concorrente(inicio,pids,()->{try{service.entregar(id(v),entrega,estoquista);return "OK";}catch(ResponseStatusException e){return "CONFLITO";}}));
   var t2=pool.submit(()->concorrente(inicio,pids,()->{try{service.revisarOuCancelar(id(v),cancelamento,estoquista,true);return "OK";}catch(ResponseStatusException e){return "CONFLITO";}}));
   // Uma transação perdedora pode ficar rollback-only; o conflito continua sendo o resultado esperado.
   var resultados=new ArrayList<String>();for(var future:List.of(t1,t2)){try{resultados.add(future.get(30,TimeUnit.SECONDS));}catch(ExecutionException e){if(e.getCause() instanceof org.springframework.transaction.UnexpectedRollbackException)resultados.add("CONFLITO");else throw e;}}
   assertThat(resultados).containsExactlyInAnyOrder("OK","CONFLITO");
  }
  assertThat(pids).hasSize(2);var finalizada=service.detalhe(id(v),admin);boolean cancelada="CANCELADA".equals(finalizada.get("fisico"));conferirSaldo(cancelada?10:6,0);assertThat(jdbc.queryForObject("select count(*) from movimentacoes_estoque where ordem_retirada_id=? and tipo='RETIRADA_OR'",Long.class,id(v))).isEqualTo(cancelada?0L:1L);
 }

 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({"0,false","0,true","1,false","1,true","2,false","2,true"})
 void f01PrevisaoTrocadaNaoRoubaOrigem(int etapa,boolean outraOs)throws Exception{
  var os=criarOsReal(material);Long cid=jdbc.queryForObject("select id from comarcas where ordem_servico_id=?",Long.class,os.getId());Long pid=jdbc.queryForObject("select id from comarca_materiais where comarca_id=?",Long.class,cid);
  var v=service.criar(rascunho(os.getId(),pid,4),estoquista);
  if(etapa>0){v=service.emitir(id(v),acao(v,null,null),estoquista);v=service.revisarOuCancelar(id(v),acao(v,null,"Preparar troca legítima"),estoquista,etapa==2);if(etapa==2)v=service.criar(rascunho(os.getId(),pid,4),estoquista);}
  Long b=materialB();obras.atualizarMaterialPrevisto(pid,b,"Previsão B",new BigDecimal("4"));if(outraOs)criarLegada(4);
  var antes=jdbc.queryForList("select id,quantidade_disponivel,quantidade_reservada from materiais where id in (?,?) order by id",material,b);long oid=id(v);var comando=acao(v,null,null);
  assertThatThrownBy(()->service.emitir(oid,comando,estoquista)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Previsão");
  assertThat(jdbc.queryForList("select id,quantidade_disponivel,quantidade_reservada from materiais where id in (?,?) order by id",material,b)).isEqualTo(antes);
  assertThat(jdbc.queryForObject("select count(*) from retirada_v2_reservas where or_id=?",Long.class,oid)).isZero();assertThat(jdbc.queryForObject("select quantidade_retirada_v2 from comarca_materiais where id=?",BigDecimal.class,pid)).isZero();
  var edit=new Rascunho(UUID.randomUUID(),rev(v),os.getId(),local,destino,"Obra fictícia","Troca legítima","Entrega","Retirante","",List.of(new Item(b,pid,new BigDecimal("4"))));
  v=service.editar(oid,edit,estoquista);v=service.emitir(oid,acao(v,null,null),estoquista);
  assertThat(jdbc.queryForObject("select origem_os from retirada_v2_reservas where or_id=? and material_id=? and local_id=?",BigDecimal.class,oid,b,local)).isEqualByComparingTo("4");
  assertThat(jdbc.queryForObject("select quantidade_reservada from materiais where id=?",Integer.class,material)).isEqualTo(outraOs?4:0);
  vistoria(cid);var entrega=new Acao(UUID.randomUUID(),rev(v),((Number)v.get("versao_emitida")).intValue(),"Parcial sintética",List.of(new Efetivo(b,new BigDecimal("2"))),null,null,false,"Entrega","Retirante",null);service.entregar(oid,entrega,estoquista);
  assertThat(jdbc.queryForObject("select quantidade_retirada_v2 from comarca_materiais where id=? and material_id=?",BigDecimal.class,pid,b)).isEqualByComparingTo("2");
  assertThat(jdbc.queryForObject("select quantidade_reservada from materiais where id=?",Integer.class,material)).isEqualTo(outraOs?4:0);assertThat(materiais.findById(b).orElseThrow().getQuantidadeReservada()).isEqualTo(2);
 }
 Long materialB(){var b=new Material();b.setNome("Material B sintético");b.setPartNumber("FIX-B-"+UUID.randomUUID());b.setQuantidadeDisponivel(10);b.setLocalizacao(saldo.listarLocais().stream().filter(l->l.getId().equals(local)).findFirst().orElseThrow().getNome());return estoque.cadastrarMaterial(b).getId();}
 void vistoria(Long cid)throws Exception{obras.salvarFotoVistoria(cid,new org.springframework.mock.web.MockMultipartFile("foto","sintetica.png","image/png",png()));obras.salvarAssinaturaVistoria(cid,"data:image/png;base64,"+Base64.getEncoder().encodeToString(png()));obras.avancarParaInfraestrutura(cid);}

 @Test void f02RetryComprovanteAposReinicioRealDaJvm()throws Exception{
  Path dir=Path.of("target","f02-restart-"+UUID.randomUUID());Files.createDirectories(dir);Path estado=dir.resolve("estado.json");
  assertThat(processoF02("preparar",estado,dir.resolve("01-commit.log"))).isZero();
  int codigo=77;for(int tentativa=0;tentativa<16&&codigo==77;tentativa++)codigo=processoF02("retry",estado,dir.resolve("02-retry-"+tentativa+".log"));
  assertThat(codigo).as("Retry em JVM distinta, com ordem Map.of diferente; evidencias em "+dir).isZero();
 }
 int processoF02(String modo,Path estado,Path log)throws Exception{
  Path args=log.resolveSibling(log.getFileName()+".args");String cp=System.getProperty("java.class.path").replace('\\','/');
  String raiz=Path.of(System.getProperty("app.upload.dir","target/test-uploads")).toAbsolutePath().toString().replace('\\','/');
  Files.writeString(args,"-Dspring.devtools.restart.enabled=false\n-Dspring.config.import=\n\"-Dapp.upload.dir="+raiz+"\"\n-cp\n\""+cp+"\"\ncom.poprc.demo.integration.RetiradaV2ProcessoProbe\n"+modo+"\n\""+estado.toAbsolutePath().toString().replace('\\','/')+"\"\n");
  var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"@"+args.toAbsolutePath()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
  assertThat(p.waitFor(100,TimeUnit.SECONDS)).as("Processo JVM deve encerrar: "+log).isTrue();return p.exitValue();
 }

 @Test void f02IntencoesV1PreservamHashEConteudoNasDuasOrdens()throws Exception{
  var mapper=new tools.jackson.databind.ObjectMapper();
  for(boolean reversa:List.of(false,true)){
   destino="LEGADO-"+UUID.randomUUID();var v=emitir(1);v=service.entregar(id(v),acao(v,BigDecimal.ONE,null),estoquista);var a=acao(v,null,null);long oid=id(v);byte[] bytes=png();service.anexar(oid,a,bytes,estoquista);
   var antigo=new LinkedHashMap<String,Object>();for(String k:reversa?List.of("hash","acao"):List.of("acao","hash"))antigo.put(k,k.equals("acao")?a:arquivos.hash(bytes));
   String hash=arquivos.hash(mapper.writeValueAsString(antigo).getBytes(java.nio.charset.StandardCharsets.UTF_8));
   jdbc.update("update retirada_v2_operacoes set algoritmo='JSON_V1',hash=? where escopo=? and chave=?",hash,oid+":ANEXAR",a.chave());
   var antes=jdbc.queryForList("select * from retirada_v2_operacoes where escopo=?",oid+":ANEXAR");service.anexar(oid,a,bytes,estoquista);
   assertThat(jdbc.queryForList("select * from retirada_v2_operacoes where escopo=?",oid+":ANEXAR")).isEqualTo(antes);
   var diferente=new Acao(a.chave(),a.revisao(),a.versao(),"Conteúdo alterado",a.itens(),a.arquivoId(),a.decisao(),false,a.entregaPor(),a.retirante(),null);
   assertThatThrownBy(()->service.anexar(oid,diferente,bytes,estoquista)).hasMessageContaining("conteúdo diferente");
   var img=new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB);img.setRGB(0,0,0xffffff);var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(img,"png",out);
   assertThatThrownBy(()->service.anexar(oid,a,out.toByteArray(),estoquista)).hasMessageContaining("conteúdo diferente");assertThat(jdbc.queryForObject("select count(*) from retirada_v2_arquivos where or_id=?",Long.class,oid)).isEqualTo(2L);
  }
 }
 @Test void f02ComandoV1SemMapaMantemComparacaoOriginal()throws Exception{
  var r=rascunho(null,null,2);var v=service.criar(r,estoquista);String hash=arquivos.hash(new tools.jackson.databind.ObjectMapper().writeValueAsString(r).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  // O escopo real é recuperado pelo ID e chave, sem pressupor o formato interno.
  jdbc.update("update retirada_v2_operacoes set algoritmo='JSON_V1',hash=? where or_id=? and chave=?",hash,id(v),r.chave());
  var antes=jdbc.queryForList("select * from retirada_v2_operacoes where or_id=?",id(v));assertThat(((Number)service.criar(r,estoquista).get("id")).longValue()).isEqualTo(((Number)v.get("id")).longValue());assertThat(jdbc.queryForList("select * from retirada_v2_operacoes where or_id=?",id(v))).isEqualTo(antes);
  assertThatThrownBy(()->service.criar(new Rascunho(r.chave(),0,null,local,destino,r.destino(),"Divergente",r.entregaPor(),r.retirante(),r.observacoes(),r.itens()),estoquista)).hasMessageContaining("conteúdo diferente");
 }

 @Autowired OrdemRetiradaPdfService pdfsV2Teste;

 @Autowired UnidadeEstoqueRastreavelService unidades;
 Long serializado(TipoControleEstoque tipo){var b=new Material();b.setNome("Serializado sintético "+tipo);b.setPartNumber("FIX-SERIAL-"+UUID.randomUUID());b.setTipoControle(tipo);b.setUnidadeMedida(UnidadeMedida.METRO);b.setLocalizacao("Depósito serializado "+UUID.randomUUID());b=estoque.cadastrarMaterial(b);unidades.cadastrar(b.getId(),"SER-"+UUID.randomUUID(),new BigDecimal("10"),"Teste isolado",null);return b.getId();}
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({"BOBINA,0","ROLO,0","BOBINA,1","ROLO,1","BOBINA,2","ROLO,2"})
 void f03OsFicouMistaAntesDaEmissaoContinuaNoLegado(TipoControleEstoque tipo,int etapa)throws Exception{
  var os=criarOsReal(material);Long cid=jdbc.queryForObject("select id from comarcas where ordem_servico_id=?",Long.class,os.getId());Long pid=jdbc.queryForObject("select id from comarca_materiais where comarca_id=?",Long.class,cid);var v=service.criar(rascunho(os.getId(),pid,4),estoquista);
  if(etapa>0){v=service.emitir(id(v),acao(v,null,null),estoquista);v=service.revisarOuCancelar(id(v),acao(v,null,"Mudança antes da entrega"),estoquista,etapa==2);if(etapa==2)v=service.criar(rascunho(os.getId(),pid,4),estoquista);}
  Long serial=serializado(tipo);obras.adicionarMaterialPrevisto(cid,serial,"Serializado",BigDecimal.ONE);long oid=id(v);var comando=acao(v,null,null);
  assertThatThrownBy(()->service.emitir(oid,comando,estoquista)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("mista");conferirSaldo(10,4);
  assertThat(jdbc.queryForObject("select count(*) from retirada_v2_reservas where or_id=?",Long.class,oid)).isZero();
  assertThat(service.contextoOs(os.getId(),estoquista).get("habilitada")).isEqualTo(false);
  var antiga=legado.criarAdicionalParaOs(os.getId(),"Teste isolado");assertThat(antiga.isNovoFluxo()).isFalse();assertThat(antiga.getItens()).extracting(i->i.getMaterial().getId()).containsExactlyInAnyOrder(material,serial);
  assertThat(pdfsV2Teste.obterPdfArquivado(antiga.getId())).isNotEmpty();
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=TipoControleEstoque.class,names={"BOBINA","ROLO"})
 void f03OsMistaAntesDoRascunhoNaoComecaV2(TipoControleEstoque tipo)throws Exception{
  var os=criarOsReal(material);Long cid=jdbc.queryForObject("select id from comarcas where ordem_servico_id=?",Long.class,os.getId());Long pid=jdbc.queryForObject("select id from comarca_materiais where comarca_id=?",Long.class,cid);Long serial=serializado(tipo);obras.adicionarMaterialPrevisto(cid,serial,"Serial",BigDecimal.ONE);
  assertThatThrownBy(()->service.criar(rascunho(os.getId(),pid,4),estoquista)).hasMessageContaining("mista");var antiga=legado.criarAdicionalParaOs(os.getId(),"Teste");assertThat(antiga.getItens()).hasSize(2);
 }

 @Test void f02CanonicoPreservaPrecisaoOrdemDasListasESemAlterarMapper(){
  var mapper=new tools.jackson.databind.ObjectMapper();boolean antes=mapper.isEnabled(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  var a=new LinkedHashMap<String,Object>();a.put("z",List.of("b","a"));a.put("n",new BigDecimal("12345678901234567890.001"));
  var b=new LinkedHashMap<String,Object>();b.put("n",new BigDecimal("12345678901234567890.001"));b.put("z",List.of("b","a"));
  String ca=ReflectionTestUtils.invokeMethod(service,"representarIntencao",a),cb=ReflectionTestUtils.invokeMethod(service,"representarIntencao",b);assertThat(ca).isEqualTo(cb).contains("12345678901234567890.001","[\"b\",\"a\"]");
  b.put("n",new BigDecimal("12345678901234567890.002"));assertThat((String)ReflectionTestUtils.invokeMethod(service,"representarIntencao",b)).isNotEqualTo(ca);
  var global=(tools.jackson.databind.ObjectMapper)ReflectionTestUtils.getField(service,"json");assertThat(global.isEnabled(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isEqualTo(antes);assertThat(global.isEnabled(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)).isFalse();
 }

 @Test void f01EntregaRecusaPrevisaoAdulteradaMesmoComTotaisValidos()throws Exception{
  var os=criarOsReal(material);Long cid=jdbc.queryForObject("select id from comarcas where ordem_servico_id=?",Long.class,os.getId());Long pid=jdbc.queryForObject("select id from comarca_materiais where comarca_id=?",Long.class,cid);var v=service.criar(rascunho(os.getId(),pid,4),estoquista);v=service.emitir(id(v),acao(v,null,null),estoquista);vistoria(cid);Long b=materialB();
  assertThatThrownBy(()->obras.atualizarMaterialPrevisto(pid,b,"Troca durante reserva",new BigDecimal("4"))).hasMessageContaining("reserva de OR V2");
  // Simula inconsistência persistida fora dos escritores participantes: não altera somas globais/locais.
  jdbc.update("update comarca_materiais set material_id=? where id=?",b,pid);long oid=id(v);var entrega=acao(v,new BigDecimal("4"),null);
  try{assertThatThrownBy(()->service.entregar(oid,entrega,estoquista)).hasMessageContaining("Previsão");conferirSaldo(10,4);assertThat(jdbc.queryForObject("select count(*) from movimentacoes_estoque where ordem_retirada_id=?",Long.class,oid)).isZero();assertThat(jdbc.queryForObject("select count(*) from retirada_v2_reservas where or_id=? and origem_os=4 and material_id=? and local_id=?",Long.class,oid,material,local)).isEqualTo(1L);}
  finally{jdbc.update("update comarca_materiais set material_id=? where id=?",material,pid);}
  service.entregar(oid,entrega,estoquista);conferirSaldo(6,0);
 }
 @Test void f01CorridaEdicaoEmissaoPreservaOrigemDeOutraOs()throws Exception{
  var os=criarOsReal(material);Long cid=jdbc.queryForObject("select id from comarcas where ordem_servico_id=?",Long.class,os.getId());Long pid=jdbc.queryForObject("select id from comarca_materiais where comarca_id=?",Long.class,cid);var v=service.criar(rascunho(os.getId(),pid,4),estoquista);Long b=materialB();criarLegada(4);var inicio=new CyclicBarrier(2);Set<Integer> pids=ConcurrentHashMap.newKeySet();
  try(var pool=Executors.newFixedThreadPool(2)){
   var emitir=pool.submit(()->concorrente(inicio,pids,()->{service.emitir(id(v),acao(v,null,null),estoquista);return "EMITIDA";}));
   var editar=pool.submit(()->concorrente(inicio,pids,()->{obras.atualizarMaterialPrevisto(pid,b,"Troca concorrente",new BigDecimal("4"));return "EDITADA";}));
   int sucessos=0;for(var f:List.of(emitir,editar)){try{f.get(30,TimeUnit.SECONDS);sucessos++;}catch(ExecutionException e){assertThat(e.getCause()).isInstanceOfAny(ResponseStatusException.class,org.springframework.transaction.UnexpectedRollbackException.class);}}assertThat(sucessos).isEqualTo(1);
  }
  assertThat(pids).hasSize(2);boolean emitida=service.detalhe(id(v),admin).get("fisico").equals("RESERVADA");conferirSaldo(10,emitida?8:4);
  assertThat(jdbc.queryForObject("select material_id from comarca_materiais where id=?",Long.class,pid)).isEqualTo(emitida?material:b);
  if(emitida)assertThat(jdbc.queryForObject("select origem_os from retirada_v2_reservas where or_id=? and material_id=? and local_id=?",BigDecimal.class,id(v),material,local)).isEqualByComparingTo("4");
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=TipoControleEstoque.class,names={"BOBINA","ROLO"})
 void f03ReservaV2AbertaNaoViraRegimeMistoENaoImpedeCancelamento(TipoControleEstoque tipo)throws Exception{
  var os=criarOsReal(material);Long cid=jdbc.queryForObject("select id from comarcas where ordem_servico_id=?",Long.class,os.getId());Long pid=jdbc.queryForObject("select id from comarca_materiais where comarca_id=?",Long.class,cid);var v=service.criar(rascunho(os.getId(),pid,4),estoquista);v=service.emitir(id(v),acao(v,null,null),estoquista);Long serial=serializado(tipo);
  assertThatThrownBy(()->obras.adicionarMaterialPrevisto(cid,serial,"Serial",BigDecimal.ONE)).hasMessageContaining("regime V2");assertThatThrownBy(()->obras.atualizarMaterialPrevisto(pid,serial,"Serial",BigDecimal.ONE)).isInstanceOf(RuntimeException.class);conferirSaldo(10,4);
  service.revisarOuCancelar(id(v),acao(v,null,"Cancelar antes da entrega"),estoquista,true);obras.adicionarMaterialPrevisto(cid,serial,"Serial",BigDecimal.ONE);assertThat(legado.criarAdicionalParaOs(os.getId(),"Teste").isNovoFluxo()).isFalse();
 }

 @Autowired MaterialProjetoRepository materiaisProjeto;

 private JdbcTemplate leituraIndependente() {
  var leitor = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
          banco.url(), banco.user(), banco.password()));
  banco.assertDestination(leitor);
  return leitor;
 }

 private Map<String,List<String>> estadoReparo(JdbcTemplate leitor) {
  Map<String,List<String>> estado = new LinkedHashMap<>();
  for (String tabela : List.of("comarcas", "comarca_materiais", "ordens_servico", "materiais",
          "saldos_materiais_locais", "retiradas_v2", "retirada_v2_reservas", "retirada_v2_itens",
          "retirada_v2_operacoes", "retirada_v2_eventos", "retirada_v2_arquivos", "movimentacoes_estoque")) {
   estado.put(tabela, leitor.queryForList("select to_jsonb(t)::text from " + tabela + " t order by 1", String.class));
  }
  return estado;
 }

 private Long fonteDoReparo(OrdemServico os, Long materialId, int quantidade) {
  var fonte = new MaterialProjeto();
  fonte.setProjeto(os.getProjeto());
  fonte.setMaterial(materiais.findById(materialId).orElseThrow());
  fonte.setQuantidadePrevista(quantidade);
  fonte.setQuantidadeUtilizada(0);
  return materiaisProjeto.saveAndFlush(fonte).getId();
 }

 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void n01ReparoProtegeOrigemEPermiteEntregaOuCancelamento(boolean cancelar) throws Exception {
  var leitor = leituraIndependente();
  var os = criarOsReal(material);
  Long cid = leitor.queryForObject("select id from comarcas where ordem_servico_id=?", Long.class, os.getId());
  Long pid = leitor.queryForObject("select id from comarca_materiais where comarca_id=?", Long.class, cid);
  var draft = service.criar(rascunho(os.getId(),pid,4),estoquista);
  var v = service.emitir(id(draft),acao(draft,null,null),estoquista);
  long oid = id(v);
  vistoria(cid);

  Long serial = serializado(TipoControleEstoque.BOBINA);
  var substituto = materiais.findById(serial).orElseThrow();
  substituto.setNome(materiais.findById(material).orElseThrow().getNome());
  materiais.saveAndFlush(substituto);
  var osSegura = criarOsReal(material);
  Long cidSegura = leitor.queryForObject("select id from comarcas where ordem_servico_id=?", Long.class, osSegura.getId());
  var comarcaSemVinculo = comarcas.findById(cidSegura).orElseThrow();
  comarcaSemVinculo.setOrdemServico(null);
  comarcas.saveAndFlush(comarcaSemVinculo);
  Long fonte = fonteDoReparo(os, serial, 4);
  try {
   var sessao = loginHttp(admin,"52998224725");
   var antes = estadoReparo(leitor);
   var resposta = http(sessao,"POST","/api/ordens-servico/reparar-vinculos-comarcas",null,true);
   var depois = estadoReparo(leitor);
   System.out.printf("N01 troca HTTP=%d persistenciaIgual=%s%n",resposta.statusCode(),antes.equals(depois));
   if (resposta.statusCode() == 200) {
    assertThatThrownBy(() -> service.entregar(oid,acao(v,BigDecimal.ONE,"Parcial"),estoquista))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Previsão");
    assertThatThrownBy(() -> service.revisarOuCancelar(oid,acao(v,null,"Cancelamento"),estoquista,true))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Previsão");
    System.out.println("N01 reprodução: entrega e cancelamento recusados depois do reparo.");
   }
   assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(400);
   assertThat(resposta.body()).contains("Reparo");
   assertThat(depois).isEqualTo(antes);
   assertThat(leitor.queryForObject("select ordem_servico_id from comarcas where id=?",Long.class,cidSegura)).isNull();
   assertThat(leitor.queryForMap("select material_id,local_id,quantidade,origem_os from retirada_v2_reservas where or_id=?",oid))
           .containsEntry("material_id",material).containsEntry("local_id",local)
           .containsEntry("quantidade",new BigDecimal("4.000")).containsEntry("origem_os",new BigDecimal("4.000"));
  } finally {
   materiaisProjeto.deleteById(fonte);
  }
  if (cancelar) {
   assertThat(service.revisarOuCancelar(oid,acao(v,null,"Cancelamento"),estoquista,true).get("fisico")).isEqualTo("CANCELADA");
  } else {
   assertThat(service.entregar(oid,acao(v,BigDecimal.ONE,"Parcial"),estoquista).get("fisico")).isEqualTo("ENTREGUE_PARCIAL");
  }
  assertThat(leitor.queryForObject("select count(*) from retirada_v2_reservas where or_id=?",Long.class,oid)).isZero();
  assertThat(leitor.queryForObject("select quantidade_retirada_v2 from comarca_materiais where id=?",BigDecimal.class,pid))
          .isEqualByComparingTo(cancelar ? "0" : "1");
  conferirSaldo(cancelar ? 10 : 9, cancelar ? 8 : 7);
 }

 @Test void n01ReparoNaoAdicionaSerializadoAOsComEntregaHistorica() throws Exception {
  var leitor = leituraIndependente();
  var os = criarOsReal(material);
  Long cid = leitor.queryForObject("select id from comarcas where ordem_servico_id=?",Long.class,os.getId());
  Long pid = leitor.queryForObject("select id from comarca_materiais where comarca_id=?",Long.class,cid);
  var draft = service.criar(rascunho(os.getId(),pid,4),estoquista);
  var emitida = service.emitir(id(draft),acao(draft,null,null),estoquista);
  vistoria(cid);
  var entregue = service.entregar(id(emitida),acao(emitida,new BigDecimal("4"),null),estoquista);
  Long fonte = fonteDoReparo(os,serializado(TipoControleEstoque.ROLO),4);
  try {
   var sessao = loginHttp(admin,"52998224725");
   var antes = estadoReparo(leitor);
   var resposta = http(sessao,"POST","/api/ordens-servico/reparar-vinculos-comarcas",null,true);
   var depois = estadoReparo(leitor);
   System.out.printf("N01 adição serial após entrega HTTP=%d persistenciaIgual=%s%n",resposta.statusCode(),antes.equals(depois));
   assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(400);
   assertThat(resposta.body()).contains("regime V2");
   assertThat(depois).isEqualTo(antes);
  } finally {
   materiaisProjeto.deleteById(fonte);
  }
  assertThat(service.anexar(id(entregue),acao(entregue,null,null),png(),estoquista).get("documental")).isEqualTo("ANEXADO");
  conferirSaldo(6,0);
 }

 @Test void n01ReparoLegitimoReligaEAcrescentaPrevisaoSemMoverSaldo() throws Exception {
  var leitor = leituraIndependente();
  var os = criarOsReal(material);
  Long cid = leitor.queryForObject("select id from comarcas where ordem_servico_id=?",Long.class,os.getId());
  var comarca = comarcas.findById(cid).orElseThrow();
  comarca.setOrdemServico(null);
  comarcas.saveAndFlush(comarca);
  var extra = new Material();
  extra.setNome("Novo material não serializado do projeto");
  extra.setPartNumber("REPARO-"+UUID.randomUUID());
  Long extraId = estoque.cadastrarMaterial(extra).getId();
  Long fonte = fonteDoReparo(os,extraId,2);
  Long mesmaPrevisao = fonteDoReparo(os,material,4);
  try {
   var semPermissao = loginHttp(estoquista,"11144477735");
   var antes = estadoReparo(leitor);
   assertThat(http(semPermissao,"POST","/api/ordens-servico/reparar-vinculos-comarcas",null,true).statusCode()).isEqualTo(403);
   assertThat(estadoReparo(leitor)).isEqualTo(antes);
   var sessao = loginHttp(admin,"52998224725");
   var resposta = http(sessao,"POST","/api/ordens-servico/reparar-vinculos-comarcas",null,true);
   assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(200);
   assertThat(leitor.queryForObject("select ordem_servico_id from comarcas where id=?",Long.class,cid)).isEqualTo(os.getId());
   assertThat(leitor.queryForMap("select quantidade_prevista,estoque_reservado from comarca_materiais where comarca_id=? and material_id=?",cid,extraId))
           .containsEntry("quantidade_prevista",new BigDecimal("2.000")).containsEntry("estoque_reservado",false);
   var reparado = estadoReparo(leitor);
   assertThat(reparado).containsEntry("materiais",antes.get("materiais"))
           .containsEntry("saldos_materiais_locais",antes.get("saldos_materiais_locais"))
           .containsEntry("movimentacoes_estoque",antes.get("movimentacoes_estoque"));
   assertThat(http(sessao,"POST","/api/ordens-servico/reparar-vinculos-comarcas",null,true).statusCode()).isEqualTo(200);
   assertThat(estadoReparo(leitor)).isEqualTo(reparado);
  } finally {
   materiaisProjeto.deleteById(fonte);
   materiaisProjeto.deleteById(mesmaPrevisao);
  }
 }

 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({
         "UNIDADE,false,OMITIDO,200", "UNIDADE,false,NULO,200", "UNIDADE,true,NULO,200",
         "UNIDADE,true,UNIDADE,200", "UNIDADE,true,FRACIONADO,409", "UNIDADE,false,INEXISTENTE,400",
         "FRACIONADO,false,OMITIDO,400", "FRACIONADO,false,NULO,400", "FRACIONADO,false,FRACIONADO,200"
 })
 void n02TipoControleHttpPreservaSemantica(TipoControleEstoque atual, boolean reservada, String entrada, int esperado) throws Exception {
  if (atual == TipoControleEstoque.FRACIONADO) {
   var fracionado = new Material();
   fracionado.setNome("Fracionado sintético");
   fracionado.setPartNumber("FRAC-"+UUID.randomUUID());
   fracionado.setTipoControle(atual);
   fracionado.setUnidadeMedida(UnidadeMedida.METRO);
   fracionado.setMetragemDisponivel(BigDecimal.TEN);
   material = estoque.cadastrarMaterial(fracionado).getId();
  }
  if (reservada) emitir(2);
  var sessao = loginHttp(admin,"52998224725");
  var leitor = leituraIndependente();
  var m = materiais.findById(material).orElseThrow();
  var dados = new LinkedHashMap<String,Object>();
  dados.put("nome","Nome editado via HTTP");
  dados.put("partNumber",m.getPartNumber());
  dados.put("categoria",m.getCategoria());
  dados.put("unidadeMedida",m.getUnidadeMedida());
  if (!entrada.equals("OMITIDO")) dados.put("tipoControle",entrada.equals("NULO") ? null : entrada);
  var antes = estadoReparo(leitor);
  var resposta = http(sessao,"PUT","/api/estoque/materiais/"+material,new tools.jackson.databind.ObjectMapper().writeValueAsString(dados),true);
  var depois = estadoReparo(leitor);
  System.out.printf("N02 atual=%s reservada=%s entrada=%s HTTP=%d%n",atual,reservada,entrada,resposta.statusCode());
  assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(esperado);
  assertThat(leitor.queryForObject("select tipo_controle from materiais where id=?",String.class,material)).isEqualTo(atual.name());
  if (esperado == 200) {
   assertThat(leitor.queryForObject("select nome from materiais where id=?",String.class,material)).isEqualTo("Nome editado via HTTP");
   assertThat(depois).containsEntry("saldos_materiais_locais",antes.get("saldos_materiais_locais"))
           .containsEntry("retirada_v2_reservas",antes.get("retirada_v2_reservas"))
           .containsEntry("movimentacoes_estoque",antes.get("movimentacoes_estoque"));
  } else {
   assertThat(depois).isEqualTo(antes);
  }
 }

 @Autowired io.micrometer.core.instrument.MeterRegistry guardMetricas;

 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(ints={3,5})
 void r02LimiteHttpReverteLegadoEV2EPermiteMesmaIntencao(int participantes) throws Exception {
  var leitor = leituraIndependente();
  var ad = loginHttp(admin,"52998224725");
  var est = loginHttp(estoquista,"11144477735");
  var v = autorizar(criar(2));
  var a = acao(v,null,null);
  String corpo = new tools.jackson.databind.ObjectMapper().writeValueAsString(a);
  String urlV2 = "/api/ordens-retirada/v2/"+id(v)+"/emissoes";
  var os = criarOsReal(material);
  String urlLegado = "/api/ordens-retirada/os/"+os.getId();
  var antes = estadoReparo(leitor);
  var movimentos = leitor.queryForList("select to_jsonb(t)::text from ordens_retirada t order by 1",String.class);
  var arquivosAntes = arquivos.raiz().toFile().list();
  double rejeicoes = guardMetricas.counter("poprc.estoque.guard.rejeicoes").count();
  var pool = Executors.newFixedThreadPool(participantes);
  try (var titular = java.sql.DriverManager.getConnection(banco.url(),banco.user(),banco.password())) {
   titular.setAutoCommit(false);
   try (var stmt = titular.createStatement()) { stmt.execute("select pg_advisory_xact_lock(7316041)"); }
   var largada = new CyclicBarrier(participantes);
   var chamadas = new ArrayList<Future<Long>>();
   for (int i=0;i<participantes;i++) {
    boolean legada = i==0;
    chamadas.add(pool.submit(()->{
     largada.await(5,TimeUnit.SECONDS);
     long inicio = System.nanoTime();
     var resposta = http(legada ? ad : est,"POST",legada ? urlLegado : urlV2,legada ? null : corpo,true);
     assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(503);
     assertThat(resposta.body()).contains("ESTOQUE_OCUPADO").doesNotContain("55P03","pg_advisory","SQLException");
     return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-inicio);
    }));
   }
   var ms = new ArrayList<Long>();
   for (var chamada:chamadas) ms.add(chamada.get(10,TimeUnit.SECONDS));
   assertThat(estadoReparo(leitor)).isEqualTo(antes);
   assertThat(leitor.queryForList("select to_jsonb(t)::text from ordens_retirada t order by 1",String.class)).isEqualTo(movimentos);
   assertThat(Arrays.equals(arquivos.raiz().toFile().list(),arquivosAntes)).isTrue();
   assertThat(guardMetricas.counter("poprc.estoque.guard.rejeicoes").count()).isEqualTo(rejeicoes+participantes);
   assertThat(guardMetricas.timer("poprc.estoque.guard.espera","resultado","timeout").count()).isGreaterThanOrEqualTo(participantes);
   System.out.println("R02 HTTP participantes="+participantes+" espera503Ms="+ms+" SQL e arquivos iguais");
   titular.rollback();
  } finally { pool.shutdownNow(); }

  var resposta = http(est,"POST",urlV2,corpo,true);
  assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(200);
  assertThat(http(est,"POST",urlV2,corpo,true).statusCode()).isEqualTo(200);
  assertThat(leitor.queryForObject("select count(*) from retirada_v2_arquivos where or_id=? and tipo='EMISSAO'",Long.class,id(v))).isEqualTo(1);
  assertThat(leitor.queryForObject("select count(*) from retirada_v2_operacoes where or_id=? and chave=?",Long.class,id(v),a.chave())).isEqualTo(1);
  assertThat(http(ad,"POST",urlLegado,null,true).statusCode()).isEqualTo(200);
  assertThat(leitor.queryForObject("select count(*) from ordens_retirada where ordem_servico_id=? and not novo_fluxo",Long.class,os.getId())).isEqualTo(1);
  assertThat(guardMetricas.timer("poprc.estoque.guard.duracao","resultado","commit").count()).isPositive();
  System.out.println("R02 HTTP recuperação participantes="+participantes+" V2 replay 1 arquivo/intenção e legado 1 OR");
 }

 @Test void r02GuardRestauraLimiteAnteriorNaMesmaTransacao() {
  new org.springframework.transaction.support.TransactionTemplate(transacoes).executeWithoutResult(tx -> {
   jdbc.execute("set local lock_timeout='133ms'");
   guard.lock();
   guard.lock();
   assertThat(jdbc.queryForObject("show lock_timeout",String.class)).isEqualTo("133ms");
  });
 }

 @Test void r01PrepararCopiaComAcessoProtegido() throws Exception {
  String saida = System.getenv("R01_FIXTURE_SAIDA");
  Assumptions.assumeTrue(saida!=null,"Fixture apenas para o ensaio de recuperação");
  var ad = loginHttp(admin,"52998224725");
  loginHttp(tecnico,"93541134780");
  var v = emitir(2);
  v = service.entregar(id(v),acao(v,BigDecimal.valueOf(2),null),estoquista);
  v = service.anexar(id(v),acao(v,null,null),png(),estoquista);
  String assinatura = "data:image/png;base64,"+Base64.getEncoder().encodeToString(png());
  var retorno = new Acao(UUID.randomUUID(),rev(v),1,"Retorno sintético",List.of(new Efetivo(material,BigDecimal.valueOf(2))),
          null,null,false,"Recebedor fictício","Devolvedor fictício",assinatura);
  service.devolver(id(v),retorno,estoquista);
  var files = jdbc.queryForList("select id,or_id,tipo,hash from retirada_v2_arquivos where or_id=? order by id",id(v));
  assertThat(files).hasSize(3);
  for (var f:files) assertThat(http(ad,"GET","/api/ordens-retirada/v2/"+id(v)+"/arquivos/"+f.get("id"),null,true).statusCode()).isEqualTo(200);
  Path path = Path.of(saida).toAbsolutePath().normalize();
  assertThat(path.getParent()).isEqualTo(Path.of("target").toAbsolutePath());
  Files.writeString(path,new tools.jackson.databind.ObjectMapper().writeValueAsString(
          Map.of("jdbc",banco.url(),"upload",arquivos.raiz().getParent().toString(),"arquivos",files)));
 }
}
