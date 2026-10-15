package com.poprc.demo.integration;

import com.poprc.demo.dto.SincronizacaoSaldosPlanilhaRequest;
import com.poprc.demo.model.*;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.service.ImportacaoEstoquePlanilhaService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.poprc.demo.support.BancoAberturaDescartavel;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** Regressão com catálogo sintético; não valida saldos operacionais ou abertura única.
 * Concorrência cobre o serviço, sem alegar tratamento HTTP da exceção concorrente.
 */
@SpringBootTest(properties={"spring.config.import=", "app.security.enabled=true", "spring.task.scheduling.enabled=false", "app.security.dev-login-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(EnsaioAberturaEstoqueTest.Recursos.class)
class EnsaioAberturaEstoqueTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired FuncionarioRepository funcionarios;
    @Autowired PasswordEncoder encoder;
    @Autowired ImportacaoEstoquePlanilhaService service;
    Cookie cookie;
    long jp,natal;
    List<Map<String,Object>> fixture;
    List<Map<String,Object>> items;
    String hash;
    static final BancoAberturaDescartavel banco = new BancoAberturaDescartavel();
    @TempDir static java.nio.file.Path uploads;

    // O Store fecha o recurso inclusive se o contexto Spring falhar ao inicializar.
    static class Recursos implements BeforeAllCallback {
        @Override public void beforeAll(ExtensionContext context) {
            context.getStore(ExtensionContext.Namespace.create(Recursos.class))
                .put("banco", (ExtensionContext.Store.CloseableResource) banco::close);
        }
    }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        banco.create();
        properties.add("spring.datasource.url", banco::url);
        properties.add("spring.datasource.username", banco::user);
        properties.add("spring.datasource.password", banco::password);
        properties.add("app.upload.dir", () -> uploads.toString());
    }

    @BeforeEach void isolatedSetup() throws Exception {
        banco.assertDestination(jdbc);
        // Somente o banco criado por esta classe; nunca limpa o TEST_DB_URL recebido.
        var tables=jdbc.queryForList("select tablename from pg_tables where schemaname='public' and tablename <> 'flyway_schema_history'",String.class);
        assertFalse(tables.isEmpty());
        assertTrue(tables.stream().allMatch(x->x.matches("[a-z0-9_]+")));
        jdbc.execute("TRUNCATE "+String.join(",",tables.stream().map(x->"public.\""+x+"\"").toList())+" RESTART IDENTITY CASCADE");
        var admin=new Funcionario();admin.setNome("TESTE ENSAIO DESCARTAVEL");admin.setCpf("11144477735");
        admin.setEmail("ensaio@example.invalid");admin.setPerfilAcesso(PerfilAcesso.ADMIN);admin.setAtivo(true);
        admin.setSenhaHash(encoder.encode("SenhaSomenteEnsaio123"));funcionarios.saveAndFlush(admin);
        var login=mvc.perform(post("/api/auth/login").contentType("application/json")
            .content("{\"cpf\":\"11144477735\",\"senha\":\"SenhaSomenteEnsaio123\"}")).andReturn().getResponse();
        assertEquals(200,login.getStatus(),login.getContentAsString());cookie=login.getCookie("SESSION");assertNotNull(cookie);
        jp=number(postJson("/api/estoque/locais",Map.of("nome","Depósito João Pessoa"),201).get("id"));
        natal=number(postJson("/api/estoque/locais",Map.of("nome","Depósito Natal"),201).get("id"));
        fixture=syntheticCatalog();
        hash="b".repeat(64);

    }
    long number(Object n){return ((Number)n).longValue();}
    Map<String,Object> postJson(String route,Object body,int expected) throws Exception {
        var r=mvc.perform(post(route).cookie(cookie).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body))).andReturn().getResponse();
        assertEquals(expected,r.getStatus(),route+" "+r.getContentAsString());
        return mapper.readValue(r.getContentAsString(),Map.class);
    }
    void registerCatalog() throws Exception {
        items=new ArrayList<>();
        for(var f:fixture){
            var saved=postJson("/api/estoque/materiais",f.get("cadastro"),201);
            items.add(new LinkedHashMap<>(Map.of("materialId",number(saved.get("id")),"nomePlanilha",saved.get("nome"),"saldo",new BigDecimal(f.get("saldo").toString()),"linhaOrigem",f.get("linha"))));
        }
        assertEquals(78,count("materiais"));assertEquals(78,count("saldos_materiais_locais"));
        assertEquals(0,count("movimentacoes_estoque"));assertEquals(0,count("importacoes_estoque_planilha"));
        assertEquals(0,jdbc.queryForObject("select count(*) from materiais where quantidade_disponivel<>0 or quantidade_reservada<>0 or metragem_disponivel<>0 or metragem_reservada<>0",Integer.class));
    }
    Map<String,Object> body(){return new LinkedHashMap<>(Map.of("nomeArquivo","TESTE-ABERTURA-78.csv","hashSha256",hash,"localEstoqueId",jp,"itens",items));}
    long count(String table){return jdbc.queryForObject("select count(*) from "+table,Long.class);}
    List<Map<String,Object>> snapshot(){
        var state=new ArrayList<>(jdbc.queryForList("select id,part_number,tipo_controle,unidade_medida,custo_medio,quantidade_disponivel,metragem_disponivel,quantidade_reservada,metragem_reservada from materiais order by id"));
        state.addAll(jdbc.queryForList("select * from saldos_materiais_locais order by id"));
        state.addAll(jdbc.queryForList("select * from importacoes_estoque_planilha_itens order by id"));
        return state;
    }
    void unchanged(List<Map<String,Object>> before,long imports,long moves){assertEquals(before,snapshot());assertEquals(imports,count("importacoes_estoque_planilha"));assertEquals(moves,count("movimentacoes_estoque"));}
    void reject(Map<String,Object> request) throws Exception {
        var before=snapshot();long imports=count("importacoes_estoque_planilha"),moves=count("movimentacoes_estoque");
        postJson("/api/estoque/importacoes/saldos",request,400);unchanged(before,imports,moves);
        assertEquals(0,count("importacoes_estoque_planilha_itens"));
    }
    void verifyFull() throws Exception {
        assertEquals(1,count("importacoes_estoque_planilha"));assertEquals(78,count("importacoes_estoque_planilha_itens"));assertEquals(64,count("movimentacoes_estoque"));
        for(int i=0;i<items.size();i++){
            var item=items.get(i);var f=fixture.get(i);var cadastro=(Map<String,Object>)f.get("cadastro");long id=number(item.get("materialId"));
            var m=jdbc.queryForMap("select * from materiais where id=?",id);
            boolean meter="METRAGEM".equals(m.get("tipo_controle"));
            var qty=new BigDecimal(m.get(meter?"metragem_disponivel":"quantidade_disponivel").toString());
            assertEquals(0,new BigDecimal(f.get("saldo").toString()).compareTo(qty));
            assertEquals(cadastro.get("tipoControle"),m.get("tipo_controle"));assertEquals(cadastro.get("unidadeMedida"),m.get("unidade_medida"));
            assertEquals(0,new BigDecimal(cadastro.get("custoMedio").toString()).compareTo((BigDecimal)m.get("custo_medio")));
            assertEquals(0,new BigDecimal(m.get("quantidade_reservada").toString()).signum());assertEquals(0,((BigDecimal)m.get("metragem_reservada")).signum());
            String col=meter?"metragem_disponivel":"quantidade_disponivel";
            var local=jdbc.queryForObject("select coalesce(sum("+col+"),0) from saldos_materiais_locais where material_id=? and local_estoque_id=?",BigDecimal.class,id,jp);
            var other=jdbc.queryForObject("select coalesce(sum("+col+"),0) from saldos_materiais_locais where material_id=? and local_estoque_id=?",BigDecimal.class,id,natal);
            assertEquals(0,qty.compareTo(local));assertEquals(0,other.signum());
        }
        assertEquals(0,count("projetos"));assertEquals(0,count("ordens_servico"));assertEquals(0,count("ordens_retirada"));
        assertEquals(0, jdbc.queryForObject("select count(*) from saldos_materiais_locais where quantidade_reservada<>0 or metragem_reservada<>0", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from saldos_materiais_locais where local_estoque_id=? and (quantidade_disponivel<>0 or metragem_disponivel<>0)", Integer.class, natal));
        assertEquals(2, jdbc.queryForObject("select count(*) from materiais where custo_medio=0", Integer.class));
        assertEquals(14, jdbc.queryForObject("select count(*) from materiais where quantidade_disponivel=0 and metragem_disponivel=0", Integer.class));
        assertEquals("METRAGEM",jdbc.queryForObject("select tipo_controle from materiais where nome='TESTE METRO 01'",String.class));
        assertTrue(jdbc.queryForObject("select descricao from materiais where part_number='TESTE-SINTETICO-008'",String.class).contains("ZERO_PROVISORIO_PRECO_DESCONHECIDO"));
    }
    @Test void aberturaCompletaHttpERepeticaoMesmoHash() throws Exception {
        registerCatalog();var result=postJson("/api/estoque/importacoes/saldos",body(),201);verifyFull();
        assertEquals(78,number(result.get("itensProcessados")));assertEquals(64,number(result.get("ajustesPositivos")));
        assertEquals(0,expectedTotal().compareTo(new BigDecimal(result.get("valorTotalImportado").toString())));
        var before=snapshot();postJson("/api/estoque/importacoes/saldos",body(),400);unchanged(before,1,64);
    }
    @Test void erroNoUltimoItemReverteLoteInteiro() throws Exception {registerCatalog();items.getLast().put("nomePlanilha","NOME INCORRETO TESTE");reject(body());}
    @Test void coberturaIncompletaRejeitada() throws Exception {registerCatalog();items.removeLast();reject(body());}
    @Test void duplicataRejeitadaSemGravacao() throws Exception {registerCatalog();items.add(new LinkedHashMap<>(items.getFirst()));reject(body());}
    @Test void unidadeInteiraFracionadaRejeitada() throws Exception {registerCatalog();items.getLast().put("saldo",new BigDecimal("0.125"));reject(body());}
    @Test void destinoInexistenteRejeitado() throws Exception {registerCatalog();var b=body();b.put("localEstoqueId",9999999L);reject(b);}
    @Test void saldoNegativoRejeitado() throws Exception {registerCatalog();items.getLast().put("saldo",BigDecimal.valueOf(-1));reject(body());}
    @Test void metragemFracionadaECustoQuatroCasas() throws Exception {
        var f=fixture.stream().filter(x->"TESTE METRO 01".equals(((Map)x.get("cadastro")).get("nome"))).findFirst().orElseThrow();
        var b=new LinkedHashMap<>((Map<String,Object>)f.get("cadastro"));b.put("custoMedio",new BigDecimal("1.2345"));
        var m=postJson("/api/estoque/materiais",b,201);items=new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("materialId",number(m.get("id")),"nomePlanilha","TESTE METRO 01","saldo",new BigDecimal("0.125"),"linhaOrigem",1))));
        postJson("/api/estoque/importacoes/saldos",body(),201);
        assertEquals(0,new BigDecimal("0.125").compareTo(jdbc.queryForObject("select metragem_disponivel from materiais",BigDecimal.class)));
        assertEquals(0,new BigDecimal("1.2345").compareTo(jdbc.queryForObject("select custo_medio from materiais",BigDecimal.class)));
    }
    @Test void chamadasConcorrentesMesmoHashNaoDuplicam() throws Exception {
        registerCatalog();var request=mapper.readValue(mapper.writeValueAsString(body()),SincronizacaoSaldosPlanilhaRequest.class);
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<String> task=()->{ready.countDown();if(!go.await(10,TimeUnit.SECONDS))throw new AssertionError("barreira");try{service.sincronizarSaldos(request,"TESTE CONCORRENCIA");return "SUCESSO";}catch(RuntimeException e){return e.getClass().getSimpleName();}};
            var a=pool.submit(task);var b=pool.submit(task);assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();
            var outcomes=List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter("SUCESSO"::equals).count());
            assertTrue(outcomes.stream().allMatch(x -> Set.of("SUCESSO", "DataIntegrityViolationException", "IllegalArgumentException").contains(x)), outcomes.toString());
            verifyFull();
        }
    }
    @Test void respostaPerdidaPodeSerConferidaSemReenvio() throws Exception {
        registerCatalog();service.sincronizarSaldos(mapper.readValue(mapper.writeValueAsString(body()),SincronizacaoSaldosPlanilhaRequest.class),"TESTE RESPOSTA PERDIDA");
        // Resultado descartado intencionalmente: recuperação somente por leitura.
        var response=mvc.perform(get("/api/estoque/importacoes/planilha").cookie(cookie)).andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());assertTrue(response.getContentAsString().contains("TESTE-ABERTURA-78.csv"));verifyFull();
    }
    @Test void novoHashPodeAlterarSaldoEExigeGuardaDeAbertura() throws Exception {
        registerCatalog();postJson("/api/estoque/importacoes/saldos",body(),201);
        // Caracteriza limite conhecido: é sincronização, não trava única de abertura.
        items.getFirst().put("saldo",new BigDecimal(items.getFirst().get("saldo").toString()).add(BigDecimal.ONE));
        var b=body();b.put("hashSha256","a".repeat(64));assertNotEquals(hash,b.get("hashSha256"));
        postJson("/api/estoque/importacoes/saldos",b,201);
        assertEquals(2,count("importacoes_estoque_planilha"));assertEquals(65,count("movimentacoes_estoque"));
    }

    /** 78 produtos artificiais: 5 por metro, 73 inteiros, 64 positivos e 14 zeros.
     * Dois custos zero, um deles documentado como provisório; nenhuma fonte operacional.
     */
    List<Map<String,Object>> syntheticCatalog() {
        List<Map<String,Object>> catalog = new ArrayList<>();
        for (int i=1; i<=78; i++) {
            boolean meter=i<=5;
            String name=meter ? "TESTE METRO %02d".formatted(i) : "TESTE UNIDADE %02d".formatted(i);
            Map<String,Object> registration=new LinkedHashMap<>();
            registration.put("nome",name);
            registration.put("partNumber","TESTE-SINTETICO-%03d".formatted(i));
            registration.put("descricao",i==8 ? "TESTE ZERO_PROVISORIO_PRECO_DESCONHECIDO" : "TESTE SINTETICO SEM VALIDADE OPERACIONAL");
            registration.put("tipoControle",meter ? "METRAGEM" : "UNIDADE");
            registration.put("unidadeMedida",meter ? "METRO" : "UNIDADE");
            registration.put("custoMedio",i==7 || i==8 ? BigDecimal.ZERO : new BigDecimal("2.3456").add(BigDecimal.valueOf(i)));
            for (String field : List.of("quantidadeDisponivel","quantidadeReservada","metragemDisponivel","metragemReservada")) registration.put(field,0);
            registration.put("localizacao","Depósito João Pessoa");
            // Último item inteiro e positivo: a falha nele sucede 77 itens processados.
            BigDecimal balance=i>=64 && i<=77 ? BigDecimal.ZERO : BigDecimal.valueOf(i+10);
            catalog.add(Map.of("cadastro",registration,"saldo",balance,"linha",i));
        }
        return catalog;
    }

    BigDecimal expectedTotal() {
        return fixture.stream().map(f -> new BigDecimal(f.get("saldo").toString())
            .multiply(new BigDecimal(((Map<?,?>)f.get("cadastro")).get("custoMedio").toString()))
            .setScale(2,java.math.RoundingMode.HALF_UP)).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
}
