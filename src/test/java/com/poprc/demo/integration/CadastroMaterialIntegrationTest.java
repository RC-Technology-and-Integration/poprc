package com.poprc.demo.integration;

import com.poprc.demo.model.*;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.support.BancoAberturaDescartavel;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** HTTP with real SESSION/CSRF/reauth; independent committed-state reads, no test transaction. */
@SpringBootTest(properties = {"spring.config.import=", "app.security.enabled=true",
        "app.security.dev-login-enabled=false", "spring.task.scheduling.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(CadastroMaterialIntegrationTest.Recursos.class)
class CadastroMaterialIntegrationTest {
    static final BancoAberturaDescartavel banco = new BancoAberturaDescartavel();
    @TempDir static Path uploads;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired FuncionarioRepository funcionarios;
    @Autowired PasswordEncoder encoder;
    JdbcTemplate independente;
    Cookie sessao;
    Cookie csrfCookie;
    String csrf;
    long funcionarioId, localId;
    static final String SENHA = "SenhaSinteticaR01abc123";

    static class Recursos implements BeforeAllCallback {
        @Override public void beforeAll(ExtensionContext context) {
            context.getStore(ExtensionContext.Namespace.create(Recursos.class))
                    .put("banco", (ExtensionContext.Store.CloseableResource) banco::close);
        }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        banco.create();
        registry.add("spring.datasource.url", banco::url);
        registry.add("spring.datasource.username", banco::user);
        registry.add("spring.datasource.password", banco::password);
        registry.add("app.upload.dir", () -> uploads.toString());
    }

    @BeforeEach void prepararDestinoELogin() throws Exception {
        banco.assertDestination(jdbc);
        independente = new JdbcTemplate(new DriverManagerDataSource(banco.url(), banco.user(), banco.password()));
        banco.assertDestination(independente);
        var tables = jdbc.queryForList("select tablename from pg_tables where schemaname='public' and tablename <> 'flyway_schema_history'", String.class);
        assertFalse(tables.isEmpty());
        assertTrue(tables.stream().allMatch(name -> name.matches("[a-z0-9_]+")));
        jdbc.execute("TRUNCATE " + String.join(",", tables.stream().map(name -> "public.\"" + name + "\"").toList()) + " RESTART IDENTITY CASCADE");
        var admin = new Funcionario(); admin.setNome("TESTE R01 DESCARTAVEL"); admin.setCpf("11144477735");
        admin.setPerfilAcesso(PerfilAcesso.ADMIN); admin.setAtivo(true); admin.setSenhaHash(encoder.encode(SENHA));
        funcionarioId = funcionarios.saveAndFlush(admin).getId();
        var login = mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("cpf", admin.getCpf(), "senha", SENHA)))).andReturn().getResponse();
        assertEquals(200, login.getStatus(), login.getContentAsString());
        sessao = login.getCookie("SESSION"); assertNotNull(sessao);
        var token = mvc.perform(get("/api/auth/csrf").cookie(sessao)).andReturn().getResponse();
        assertEquals(200, token.getStatus());
        csrf = mapper.readTree(token.getContentAsString()).path("token").asText(); assertFalse(csrf.isBlank());
        csrfCookie = token.getCookie("XSRF-TOKEN"); assertNotNull(csrfCookie);
        assertEquals(csrf, csrfCookie.getValue());
        enviar(post("/api/auth/reauth"), Map.of("senha", SENHA), 204);
        localId = id(enviar(post("/api/estoque/locais"), Map.of("nome", "TESTE R01 deposito"), 201));
    }

    Map<String, Object> enviar(MockHttpServletRequestBuilder request, Object body, int status) throws Exception {
        var response = mvc.perform(request.cookie(sessao, csrfCookie).header("X-XSRF-TOKEN", csrf)
                .contentType("application/json").content(mapper.writeValueAsString(body))).andReturn().getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return response.getContentAsByteArray().length == 0 ? Map.of() : mapper.readValue(response.getContentAsString(), Map.class);
    }

    long id(Map<String, Object> value) { return ((Number) value.get("id")).longValue(); }
    void decimal(String expected, Object actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));
    }
    long count(String table) { return independente.queryForObject("select count(*) from " + table, Long.class); }

    Map<String, Object> cadastro(String tipo, String unidade, String saldo) {
        var body = new LinkedHashMap<String, Object>();
        body.put("nome", "TESTE CAD HIST " + tipo); body.put("partNumber", "TESTE-CAD-HIST");
        body.put("tipoControle", tipo); body.put("unidadeMedida", unidade);
        body.put("quantidadeDisponivel", List.of("FRACIONADO", "METRAGEM").contains(tipo) ? 0 : new BigDecimal(saldo));
        body.put("metragemDisponivel", List.of("FRACIONADO", "METRAGEM").contains(tipo) ? new BigDecimal(saldo) : BigDecimal.ZERO);
        body.put("comprimentoPorPeca", new BigDecimal("3"));
        body.put("custoMedio", new BigDecimal("8.12345")); body.put("localizacao", "TESTE R01 deposito");
        return body;
    }

    @ParameterizedTest
    @CsvSource({"FRACIONADO,UNIDADE", "FRACIONADO,PACOTE", "METRAGEM,METRO"})
    void cadastroDecimalPersisteSaldoCustoReservasEUmEvento(String tipo, String unidade) throws Exception {
        long material = id(enviar(post("/api/estoque/materiais"), cadastro(tipo, unidade, "0.125"), 201));
        conferirSaldo(material, 0, "0.125", "0.125", unidade);
        decimal("8.1235", independente.queryForObject("select custo_medio from materiais where id=?", BigDecimal.class, material));
        var evento = independente.queryForMap("select * from movimentacoes_estoque where material_id=?", material);
        assertEquals("ENTRADA", evento.get("tipo")); assertNull(evento.get("quantidade"));
        decimal("0.125", evento.get("metragem")); decimal("0", evento.get("saldo_anterior"));
        decimal("0.125", evento.get("saldo_posterior")); decimal("8.1235", evento.get("custo_unitario"));
        decimal("1.0154", evento.get("valor_total_movimentacao")); assertEquals(unidade, evento.get("unidade_medida"));
        assertEquals(1, count("movimentacoes_estoque"));
        var historico = mvc.perform(get("/api/estoque/historico").cookie(sessao)).andReturn().getResponse();
        assertEquals(200, historico.getStatus());
        assertEquals(unidade, mapper.readTree(historico.getContentAsString()).get(0).path("unidadeMedida").asText());
    }

    @Test void cadastroFracionadoZeroNaoCriaMovimento() throws Exception {
        for (boolean saldoAusente : List.of(false, true)) {
            var body = cadastro("FRACIONADO", "PACOTE", "0");
            body.put("partNumber", "TESTE-CAD-ZERO-" + saldoAusente);
            if (saldoAusente) {
                body.remove("quantidadeDisponivel"); body.remove("metragemDisponivel");
            }
            long material = id(enviar(post("/api/estoque/materiais"), body, 201));
            conferirSaldo(material, 0, "0", "0", "PACOTE"); assertEquals(0, count("movimentacoes_estoque"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNIDADE", "PECA_COM_COMPRIMENTO"})
    void cadastroInteiroRejeitaFracaoSemGravar(String tipo) throws Exception {
        enviar(post("/api/estoque/materiais"), cadastro(tipo, tipo.equals("UNIDADE") ? "UNIDADE" : "PECA", "1.125"), 400);
        assertEquals(0, count("materiais")); assertEquals(0, count("saldos_materiais_locais"));
        assertEquals(0, count("movimentacoes_estoque"));
    }

    @ParameterizedTest
    @CsvSource({"UNIDADE,UNIDADE,0", "PECA_COM_COMPRIMENTO,PECA,6"})
    void cadastroInteiroPreservaComprimentoDerivado(String tipo, String unidade, String metros) throws Exception {
        long material = id(enviar(post("/api/estoque/materiais"), cadastro(tipo, unidade, "2.0"), 201));
        conferirSaldo(material, 2, metros, "2", unidade); assertEquals(1, count("movimentacoes_estoque"));
        var evento = independente.queryForMap("select * from movimentacoes_estoque where material_id=?", material);
        assertEquals(2, evento.get("quantidade")); decimal("16.2470", evento.get("valor_total_movimentacao"));
        assertEquals(unidade, evento.get("unidade_medida"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BOBINA", "ROLO"})
    void cadastroRastreavelContinuaZeradoEAguardaUnidadeIndividual(String tipo) throws Exception {
        for (boolean unidadeAusente : List.of(false, true)) {
            var body = cadastro(tipo, tipo, "2"); body.put("metragemDisponivel", new BigDecimal("100"));
            body.put("partNumber", "TESTE-CAD-RASTREAVEL-" + unidadeAusente);
            if (unidadeAusente) body.remove("unidadeMedida");
            long material = id(enviar(post("/api/estoque/materiais"), body, 201));
            conferirSaldo(material, 0, "0", "0", tipo); assertEquals(0, count("movimentacoes_estoque"));
            assertEquals(0, count("unidades_estoque_rastreaveis"));
        }
    }

    @Test void editarCadastroNaoAlteraSaldoLocalReservaOuHistoricoEConservaSnapshot() throws Exception {
        var body = cadastro("FRACIONADO", "PACOTE", "0.125");
        long material = id(enviar(post("/api/estoque/materiais"), body, 201));
        var saldosAntes = independente.queryForList("select * from saldos_materiais_locais");
        var movimentosAntes = independente.queryForList("select * from movimentacoes_estoque");
        body.put("nome", "TESTE CAD HIST EDITADO"); body.put("unidadeMedida", "UNIDADE");
        body.put("metragemDisponivel", new BigDecimal("900")); body.put("quantidadeDisponivel", 99);
        body.put("quantidadeReservada", 8); body.put("metragemReservada", new BigDecimal("50"));
        enviar(put("/api/estoque/materiais/" + material), body, 200);
        conferirSaldo(material, 0, "0.125", "0.125", "UNIDADE");
        assertEquals(saldosAntes, independente.queryForList("select * from saldos_materiais_locais"));
        assertEquals(movimentosAntes, independente.queryForList("select * from movimentacoes_estoque"));
        assertEquals("PACOTE", movimentosAntes.getFirst().get("unidade_medida"));
    }

    void conferirSaldo(long material, int quantidade, String metros, String livre, String unidade) {
        var global = independente.queryForMap("select * from materiais where id=?", material);
        var local = independente.queryForMap("select * from saldos_materiais_locais where material_id=?", material);
        assertEquals(quantidade, global.get("quantidade_disponivel")); assertEquals(quantidade, local.get("quantidade_disponivel"));
        decimal(metros, global.get("metragem_disponivel")); decimal(metros, local.get("metragem_disponivel"));
        assertEquals(0, global.get("quantidade_reservada")); assertEquals(0, local.get("quantidade_reservada"));
        decimal("0", global.get("metragem_reservada")); decimal("0", local.get("metragem_reservada"));
        boolean decimal = List.of("FRACIONADO", "METRAGEM", "BOBINA", "ROLO").contains(global.get("tipo_controle"));
        decimal(livre, decimal ? ((BigDecimal) global.get("metragem_disponivel")).subtract((BigDecimal) global.get("metragem_reservada"))
                : BigDecimal.valueOf((Integer) global.get("quantidade_disponivel") - (Integer) global.get("quantidade_reservada")));
        assertEquals(unidade, global.get("unidade_medida")); assertEquals(localId, ((Number) local.get("local_estoque_id")).longValue());
    }
}
