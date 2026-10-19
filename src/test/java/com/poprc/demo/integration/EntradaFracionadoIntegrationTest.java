package com.poprc.demo.integration;

import com.poprc.demo.model.*;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.security.SessaoAutenticacaoService;
import com.poprc.demo.service.ComarcaService;
import com.poprc.demo.support.BancoAberturaDescartavel;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
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
import org.springframework.session.SessionRepository;
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
@ExtendWith(EntradaFracionadoIntegrationTest.Recursos.class)
class EntradaFracionadoIntegrationTest {
    static final BancoAberturaDescartavel banco = new BancoAberturaDescartavel();
    @TempDir static Path uploads;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired FuncionarioRepository funcionarios;
    @Autowired PasswordEncoder encoder;
    @Autowired ComarcaService obras;
    @Autowired ComarcaRepository comarcas;
    @Autowired SessionRepository<?> sessoes;
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
    long cadastrar(String tipo, String unidade) throws Exception {
        return id(enviar(post("/api/estoque/materiais"), Map.of("nome", "TESTE R01 " + tipo,
                "partNumber", "TESTE-R01-" + tipo, "tipoControle", tipo, "unidadeMedida", unidade,
                "quantidadeDisponivel", 0, "metragemDisponivel", BigDecimal.ZERO,
                "custoMedio", new BigDecimal("2.0000"), "localizacao", "TESTE R01 deposito"), 201));
    }
    Map<String, Object> entrada(long materialId, String valor) {
        var body = new LinkedHashMap<String, Object>();
        body.put("materialId", materialId); body.put("quantidade", null); body.put("metragem", new BigDecimal(valor));
        body.put("custoUnitarioEntrada", new BigDecimal("1.2345")); body.put("funcionarioId", funcionarioId); body.put("localEstoqueId", localId);
        return body;
    }
    void decimal(String expected, Object actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));
    }
    long count(String table) { return independente.queryForObject("select count(*) from " + table, Long.class); }

    @ParameterizedTest
    @CsvSource({"FRACIONADO,UNIDADE", "FRACIONADO,PACOTE", "METRAGEM,METRO"})
    void entradaDecimalPreservaReservasCustoUnidadeEHistorico(String tipo, String unidade) throws Exception {
        long material = cadastrar(tipo, unidade);
        var inicial = entrada(material, "1"); inicial.put("custoUnitarioEntrada", new BigDecimal("2"));
        enviar(post("/api/estoque/entrada"), inicial, 201);
        var comarca = new Comarca(); comarca.setNomeComarca("TESTE R01 obra");
        comarca = comarcas.saveAndFlush(comarca);
        obras.adicionarMaterialPrevisto(comarca.getId(), material, "TESTE R01 " + tipo, new BigDecimal("0.375"));
        long movimentos = count("movimentacoes_estoque");

        enviar(post("/api/estoque/entrada"), entrada(material, "0.125"), 201);

        conferirEntrada(material, unidade, "1.125", "0.375", "1.9149");
        assertEquals(movimentos + 1, count("movimentacoes_estoque"));
        var evento = independente.queryForMap("select * from movimentacoes_estoque order by id desc limit 1");
        assertEquals("ENTRADA", evento.get("tipo")); decimal("0.125", evento.get("metragem"));
        assertNull(evento.get("quantidade")); decimal("1.2345", evento.get("custo_unitario"));
        decimal("0.1543", evento.get("valor_total_movimentacao"));
        decimal("1", evento.get("saldo_anterior")); decimal("1.125", evento.get("saldo_posterior"));
    }

    void conferirEntrada(long material, String unidade, String saldo, String reserva, String custo) {
        var persisted = independente.queryForMap("select * from materiais where id=?", material);
        assertEquals(unidade, persisted.get("unidade_medida"));
        decimal(saldo, persisted.get("metragem_disponivel")); decimal(reserva, persisted.get("metragem_reservada"));
        decimal(custo, persisted.get("custo_medio"));
        var local = independente.queryForMap("select * from saldos_materiais_locais where material_id=? and local_estoque_id=?", material, localId);
        decimal(saldo, local.get("metragem_disponivel")); decimal(reserva, local.get("metragem_reservada"));
        decimal(new BigDecimal(saldo).subtract(new BigDecimal(reserva)).toPlainString(),
                ((BigDecimal) local.get("metragem_disponivel")).subtract((BigDecimal) local.get("metragem_reservada")));
    }

    @Test void unidadeRejeitaFracaoERastreaveisExigemFluxoProprio() throws Exception {
        long unidade = cadastrar("UNIDADE", "UNIDADE");
        for (String valor : List.of("0.125", "1.125")) {
            var body = entrada(unidade, "0"); body.put("quantidade", new BigDecimal(valor)); body.put("metragem", null);
            enviar(post("/api/estoque/entrada"), body, 400);
        }
        for (String tipo : List.of("BOBINA", "ROLO")) {
            long material = cadastrar(tipo, tipo);
            var response = enviar(post("/api/estoque/entrada"), entrada(material, "0.125"), 400);
            assertTrue(response.get("erro").toString().contains("individual"));
        }
        assertEquals(0, count("movimentacoes_estoque"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "2.0"})
    void unidadeAceitaValorInteiroSemAlterarContrato(String valor) throws Exception {
        long material = cadastrar("UNIDADE", "UNIDADE");
        var body = entrada(material, "0"); body.put("quantidade", new BigDecimal(valor)); body.put("metragem", null);
        enviar(post("/api/estoque/entrada"), body, 201);
        assertEquals(2, independente.queryForObject("select quantidade_disponivel from materiais where id=?", Integer.class, material));
        assertEquals(2, independente.queryForObject("select quantidade_disponivel from saldos_materiais_locais where material_id=?", Integer.class, material));
        assertEquals(1, count("movimentacoes_estoque"));
    }

    Map<String, Object> nf(long material, boolean erroUltimoItem) throws Exception {
        byte[] xml = ("<TESTE-NF-R01>" + UUID.randomUUID() + "</TESTE-NF-R01>").getBytes(StandardCharsets.UTF_8);
        var item = new LinkedHashMap<String, Object>(Map.of("importar", true, "materialId", material,
                "descricao", "TESTE SINTETICO R01", "quantidade", new BigDecimal("0.125"), "valorUnitario", new BigDecimal("1.2345")));
        var itens = new ArrayList<Map<String, Object>>(); itens.add(item);
        if (erroUltimoItem) { var invalido = new LinkedHashMap<>(item); invalido.put("descricao", " "); itens.add(invalido); }
        return Map.of("nomeArquivo", "TESTE-R01.xml", "contentType", "application/xml",
                "arquivoBase64", Base64.getEncoder().encodeToString(xml),
                "hashSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(xml)),
                "localEstoqueId", localId, "numero", "TESTE-R01", "itens", itens);
    }

    @Test void nfVinculadaFracionadaPersisteUmaEntradaDecimal() throws Exception {
        long material = cadastrar("FRACIONADO", "UNIDADE");
        enviar(post("/api/estoque/importacoes/notas-fiscais"), nf(material, false), 201);
        conferirEntrada(material, "UNIDADE", "0.125", "0", "1.2345");
        assertEquals(1, count("movimentacoes_estoque")); assertEquals(1, count("importacoes_notas_fiscais"));
        assertEquals(1, count("importacoes_notas_fiscais_itens"));
        var evento = independente.queryForMap("select * from movimentacoes_estoque");
        assertEquals("ENTRADA", evento.get("tipo")); decimal("0.125", evento.get("metragem"));
    }

    @Test void erroNoUltimoItemDaNfReverteTodasAsGravacoesDoBanco() throws Exception {
        long material = cadastrar("FRACIONADO", "UNIDADE");
        var antes = independente.queryForList("select * from materiais");
        var locais = independente.queryForList("select * from saldos_materiais_locais");
        long arquivosAntes;
        try (var files = Files.walk(uploads)) { arquivosAntes = files.filter(Files::isRegularFile).count(); }
        var response = enviar(post("/api/estoque/importacoes/notas-fiscais"), nf(material, true), 400);
        assertTrue(response.get("erro").toString().contains("descrição"));
        assertEquals(antes, independente.queryForList("select * from materiais"));
        assertEquals(locais, independente.queryForList("select * from saldos_materiais_locais"));
        assertEquals(0, count("movimentacoes_estoque")); assertEquals(0, count("importacoes_notas_fiscais"));
        assertEquals(0, count("importacoes_notas_fiscais_itens"));
        try (var files = Files.walk(uploads)) {
            System.out.println("R01 NF rollback: sem gravacao parcial no banco; variacao de arquivos temporarios="
                    + (files.filter(Files::isRegularFile).count() - arquivosAntes));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test void entradaExigeSessaoCsrfEReautenticacaoReal() throws Exception {
        long material = cadastrar("FRACIONADO", "UNIDADE");
        var body = entrada(material, "0.125");
        var request = mapper.writeValueAsString(body);
        assertEquals(401, mvc.perform(post("/api/estoque/entrada").cookie(csrfCookie).header("X-XSRF-TOKEN", csrf).contentType("application/json").content(request)).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(post("/api/estoque/entrada").cookie(sessao).contentType("application/json").content(request)).andReturn().getResponse().getStatus());
        SessionRepository repository = sessoes;
        String sessionId = new String(Base64.getDecoder().decode(sessao.getValue()), StandardCharsets.UTF_8);
        var persisted = repository.findById(sessionId); assertNotNull(persisted);
        persisted.setAttribute(SessaoAutenticacaoService.REAUTENTICADO_EM, Instant.now().minusSeconds(600)); repository.save(persisted);
        enviar(post("/api/estoque/entrada"), body, 428);
        assertEquals(0, count("movimentacoes_estoque"));
        enviar(post("/api/auth/reauth"), Map.of("senha", SENHA), 204);
        enviar(post("/api/estoque/entrada"), body, 201);
        assertEquals(1, count("movimentacoes_estoque"));
    }
}
