package com.poprc.demo.integration;

import com.poprc.demo.model.*;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.support.BancoAberturaDescartavel;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Creation regression. Real HTTP server/security; independent committed JDBC snapshots. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.import=", "server.address=127.0.0.1", "app.security.enabled=true",
    "app.security.dev-login-enabled=false", "spring.task.scheduling.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(CadastroMaterialEstadoIntegrationTest.Recursos.class)
class CadastroMaterialEstadoIntegrationTest {
    static final BancoAberturaDescartavel banco = new BancoAberturaDescartavel();
    @TempDir static Path uploads;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired FuncionarioRepository funcionarios;
    @Autowired PasswordEncoder encoder;
    @Autowired com.poprc.demo.service.EstoqueService estoque;
    @Autowired org.springframework.core.env.Environment environment;
    JdbcTemplate independente;
    HttpClient client;
    String session, csrfCookie, csrf;
    static final String SENHA = "SinteticoPostEstado123abc";
    static final String previousUploadRoot = System.getProperty("app.upload.dir");
    @AfterAll static void restoreUploadRoot() {
        if (previousUploadRoot == null) System.clearProperty("app.upload.dir");
        else System.setProperty("app.upload.dir", previousUploadRoot);
    }
    static class Recursos implements BeforeAllCallback {
        public void beforeAll(ExtensionContext c) {
            c.getStore(ExtensionContext.Namespace.create(Recursos.class))
                .put("banco", (ExtensionContext.Store.CloseableResource) banco::close);
        }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        assertNotNull(uploads);
        System.setProperty("app.upload.dir", uploads.toAbsolutePath().toString());
        banco.create();
        r.add("spring.datasource.url", banco::url);
        r.add("spring.datasource.username", banco::user);
        r.add("spring.datasource.password", banco::password);
        r.add("app.upload.dir", () -> uploads.toString());
    }
    @BeforeEach void setup() {
        banco.assertDestination(jdbc);
        independente = new JdbcTemplate(new DriverManagerDataSource(banco.url(), banco.user(), banco.password()));
        banco.assertDestination(independente);
        var tables = jdbc.queryForList("select tablename from pg_tables where schemaname='public' and tablename <> 'flyway_schema_history'", String.class);
        assertTrue(tables.stream().allMatch(n -> n.matches("[a-z0-9_]+")));
        jdbc.execute("TRUNCATE " + String.join(",", tables.stream().map(n -> "public.\"" + n + "\"").toList()) + " RESTART IDENTITY CASCADE");
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        session = csrfCookie = csrf = null;
    }
    HttpResponse<String> call(String method, String path, Object body, boolean withSession, boolean withCsrf) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (withSession && session != null) b.header("Cookie", session + (csrfCookie == null ? "" : "; " + csrfCookie));
        if (!withSession && withCsrf && csrfCookie != null) b.header("Cookie", csrfCookie);
        if (withCsrf && csrf != null) b.header("X-XSRF-TOKEN", csrf);
        b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    void login(String role, boolean reauth) throws Exception {
        var f = new Funcionario(); f.setNome("SINTETICO POST " + role); f.setCpf("11144477735");
        f.setPerfilAcesso(PerfilAcesso.valueOf(role)); f.setAtivo(true); f.setSenhaHash(encoder.encode(SENHA));
        funcionarios.saveAndFlush(f);
        var response = call("POST", "/api/auth/login", Map.of("cpf", f.getCpf(), "senha", SENHA), false, false);
        assertEquals(200, response.statusCode(), response.body());
        session = response.headers().allValues("set-cookie").stream().filter(s -> s.startsWith("SESSION=")).findFirst().orElseThrow().split(";", 2)[0];
        response = call("GET", "/api/auth/csrf", null, true, false);
        assertEquals(200, response.statusCode());
        csrf = mapper.readTree(response.body()).path("token").asText(); assertFalse(csrf.isBlank());
        csrfCookie = response.headers().allValues("set-cookie").stream().filter(s -> s.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow().split(";", 2)[0];
        if (reauth) assertEquals(204, call("POST", "/api/auth/reauth", Map.of("senha", SENHA), true, true).statusCode());
    }
    Map<String,Object> payload(String pn, boolean decimal, String saldo) {
        var p = new LinkedHashMap<String,Object>();
        p.put("nome", "SINTETICO " + pn); p.put("partNumber", pn);
        p.put("tipoControle", decimal ? "FRACIONADO" : "UNIDADE");
        p.put("unidadeMedida", decimal ? "PACOTE" : "UNIDADE");
        p.put("quantidadeDisponivel", decimal ? 0 : new BigDecimal(saldo));
        p.put("metragemDisponivel", decimal ? new BigDecimal(saldo) : BigDecimal.ZERO);
        p.put("custoMedio", new BigDecimal("3.25")); p.put("localizacao", "SINTETICO DEPOSITO A");
        return p;
    }
    Map<String,Object> snapshot() {
        banco.assertDestination(independente);
        var s = new LinkedHashMap<String,Object>();
        for (String table : List.of("materiais", "locais_estoque", "saldos_materiais_locais", "movimentacoes_estoque", "ordens_servico", "ordens_retirada"))
            s.put(table, independente.queryForList("select * from " + table + " order by id"));
        return s;
    }
    long create(Map<String,Object> p) throws Exception {
        var r = call("POST", "/api/estoque/materiais", p, true, true);
        assertEquals(201, r.statusCode(), r.body());
        return mapper.readTree(r.body()).path("id").asLong();
    }
    static List<org.junit.jupiter.params.provider.Arguments> cases() {
        var result = new ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for (String role : List.of("ADMIN", "ESTOQUE"))
            for (String c : List.of("id_ausente", "id_null", "id_atual", "id_sem_version", "id_desatualizada",
                    "id_inexistente_version", "id_inexistente_sem_version", "id_zero", "id_negativo",
                    "version_sem_id", "version_negativa_sem_id", "version_null", "id_vazio", "version_vazia",
                    "reserva_int_zero", "reserva_int_positiva", "reserva_int_acima", "reserva_int_negativa", "reserva_int_fracionaria",
                    "reserva_decimal_zero", "reserva_decimal_positiva", "reserva_decimal_acima", "reserva_decimal_negativa",
                    "reservas_null", "reserva_eixo_int_em_decimal", "reserva_eixo_decimal_em_int", "estado_servidor", "saldo_zero", "defaults"))
                result.add(org.junit.jupiter.params.provider.Arguments.of(role, c));
        return result;
    }
    @ParameterizedTest(name="{0}/{1}") @MethodSource("cases")
    void cadastroRespeitaContratoDeCriacao(String role, String c) throws Exception {
        login(role, true);
        long a = create(payload("BASE-A", false, "10"));
        var b = payload("BASE-B", true, "7.125"); b.put("localizacao", "SINTETICO DEPOSITO B"); create(b);
        jdbc.update("update materiais set version=version+1 where id=?", a);
        long version = independente.queryForObject("select version from materiais where id=?", Long.class, a);
        var before = snapshot();
        boolean decimal = c.contains("decimal") && !c.equals("reserva_eixo_decimal_em_int");
        boolean zero = c.equals("saldo_zero") || c.equals("defaults");
        var p = payload("PROBE", decimal, zero ? "0" : decimal ? "10.125" : "20");
        switch(c) {
            case "id_null" -> p.put("id", null);
            case "id_atual" -> { p.put("id", a); p.put("version", version); }
            case "id_sem_version" -> p.put("id", a);
            case "id_desatualizada" -> { p.put("id", a); p.put("version", version-1); }
            case "id_inexistente_version" -> { p.put("id", 999999); p.put("version", 0); }
            case "id_inexistente_sem_version" -> p.put("id", 999999);
            case "id_zero" -> p.put("id", 0);
            case "id_negativo" -> p.put("id", -1);
            case "version_sem_id" -> p.put("version", 0);
            case "version_negativa_sem_id" -> p.put("version", -1);
            case "version_null" -> p.put("version", null);
            case "id_vazio" -> p.put("id", "");
            case "version_vazia" -> p.put("version", "");
            case "reservas_null" -> { p.put("quantidadeReservada", null); p.put("metragemReservada", null); }
            case "reserva_eixo_int_em_decimal" -> p.put("quantidadeReservada", 2);
            case "reserva_eixo_decimal_em_int" -> p.put("metragemReservada", new BigDecimal("1.125"));
            case "estado_servidor" -> {
                p.put("ativo", false); p.put("removidoEm", "2020-01-01T00:00:00"); p.put("removidoPor", "CLIENTE");
                p.put("restauradoEm", "2020-01-02T00:00:00"); p.put("restauradoPor", "CLIENTE");
            }
            case "defaults" -> {
                for (String key : List.of("tipoControle", "unidadeMedida", "quantidadeDisponivel", "metragemDisponivel", "custoMedio")) p.remove(key);
            }
            default -> { }
        }
        if (c.startsWith("reserva_int_") || c.startsWith("reserva_decimal_"))
            p.put(decimal ? "metragemReservada" : "quantidadeReservada", new BigDecimal(
                c.endsWith("zero") ? "0.000" : c.endsWith("acima") ? "99" : c.endsWith("negativa") ? "-1.125"
                    : c.endsWith("fracionaria") ? "0.125" : decimal ? "1.125" : "2"));
        boolean valid = List.of("id_ausente", "id_null", "version_null", "reservas_null", "reserva_int_zero", "reserva_decimal_zero", "estado_servidor", "saldo_zero", "defaults").contains(c);
        var response = call("POST", "/api/estoque/materiais", p, true, true);
        var after = snapshot(); record(role, c, p, response, before, after);
        if (!valid) {
            assertEquals(400, response.statusCode(), response.body()); assertEquals(before, after);
            assertTrue(response.body().contains(c.startsWith("id_") || c.startsWith("version_") ? "ID e version" : "reservas iniciais"), response.body());
        } else {
            assertEquals(201, response.statusCode(), response.body());
            long returned = mapper.readTree(response.body()).path("id").asLong(); assertEquals(3L, returned);
            var global = independente.queryForMap("select * from materiais where id=?", returned);
            var local = independente.queryForMap("select * from saldos_materiais_locais where material_id=?", returned);
            assertEquals(0L, ((Number)global.get("version")).longValue()); assertEquals("PROBE", global.get("part_number"));
            assertEquals(Boolean.TRUE, global.get("ativo"));
            for (String column : List.of("removido_em", "removido_por", "restaurado_em", "restaurado_por")) assertNull(global.get(column));
            for (String column : List.of("quantidade_disponivel", "quantidade_reservada", "metragem_disponivel", "metragem_reservada")) assertEquals(global.get(column), local.get(column));
            assertEquals(0, global.get("quantidade_reservada")); number("0", global.get("metragem_reservada"));
            assertEquals(zero || decimal ? 0 : 20, global.get("quantidade_disponivel"));
            number(decimal ? "10.125" : "0", global.get("metragem_disponivel"));
            number(c.equals("defaults") ? "0" : "3.25", global.get("custo_medio"));
            assertEquals(decimal ? "PACOTE" : "UNIDADE", global.get("unidade_medida"));
            assertEquals("SINTETICO DEPOSITO A", global.get("localizacao"));
            assertEquals(3L, independente.queryForObject("select count(*) from materiais", Long.class));
            assertEquals(zero ? 2L : 3L, independente.queryForObject("select count(*) from movimentacoes_estoque", Long.class));
            if (!zero) {
                var event = independente.queryForMap("select * from movimentacoes_estoque where material_id=?", returned);
                assertEquals("ENTRADA", event.get("tipo")); number("0", event.get("saldo_anterior"));
                number(decimal ? "10.125" : "20", event.get("saldo_posterior"));
                number("3.25", event.get("custo_unitario"));
                number(decimal ? "32.9063" : "65", event.get("valor_total_movimentacao"));
                assertEquals(global.get("unidade_medida"), event.get("unidade_medida"));
                assertEquals("SINTETICO DEPOSITO A", event.get("estoque_destino"));
            }
            assertEquals(before.get("materiais"), ((List<?>)after.get("materiais")).subList(0,2));
            assertEquals(before.get("saldos_materiais_locais"), ((List<?>)after.get("saldos_materiais_locais")).subList(0,2));
        }
    }
    void number(String expected, Object actual) { assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString()))); }

    static List<String> internalCases() {
        return List.of("id_existente", "id_inexistente", "version_sem_id", "reserva_int_positiva", "reserva_int_negativa",
                "reserva_decimal_positiva", "reserva_decimal_negativa", "estado_servidor");
    }
    @ParameterizedTest @MethodSource("internalCases")
    void servicoProtegeChamadasInternasComPersistenciaIndependente(String c) throws Exception {
        login("ADMIN", true);
        long a = create(payload("BASE-A", false, "10"));
        var before = snapshot();
        var m = new Material(); m.setNome("SINTETICO INTERNO"); m.setPartNumber("INTERNO");
        m.setQuantidadeDisponivel(5); m.setCustoMedio(new BigDecimal("2.25")); m.setLocalizacao("SINTETICO DEPOSITO A");
        switch(c) {
            case "id_existente" -> { m.setId(a); m.setVersion(independente.queryForObject("select version from materiais where id=?", Long.class, a)); }
            case "id_inexistente" -> m.setId(999999L);
            case "version_sem_id" -> m.setVersion(0L);
            case "reserva_int_positiva" -> m.setQuantidadeReservada(1);
            case "reserva_int_negativa" -> m.setQuantidadeReservada(-1);
            case "reserva_decimal_positiva" -> m.setMetragemReservada(new BigDecimal("0.001"));
            case "reserva_decimal_negativa" -> m.setMetragemReservada(new BigDecimal("-0.001"));
            case "estado_servidor" -> {
                m.setQuantidadeReservada(null); m.setMetragemReservada(null); m.setAtivo(false);
                m.setRemovidoEm(java.time.LocalDateTime.now()); m.setRemovidoPor("CLIENTE");
                m.setRestauradoEm(java.time.LocalDateTime.now()); m.setRestauradoPor("CLIENTE");
            }
        }
        String result;
        if (!c.equals("estado_servidor")) {
            result = assertThrows(IllegalArgumentException.class, () -> estoque.cadastrarMaterial(m)).getMessage();
            assertEquals(before, snapshot());
        } else {
            var saved = estoque.cadastrarMaterial(m); result = "created:" + saved.getId();
            var persisted = independente.queryForMap("select * from materiais where id=?", saved.getId());
            assertEquals(2L, saved.getId()); assertEquals(Boolean.TRUE, persisted.get("ativo"));
            assertEquals(0, persisted.get("quantidade_reservada")); number("0", persisted.get("metragem_reservada"));
            for (String column : List.of("removido_em", "removido_por", "restaurado_em", "restaurado_por")) assertNull(persisted.get(column));
            assertEquals(5, persisted.get("quantidade_disponivel"));
            assertEquals(5, independente.queryForObject("select quantidade_disponivel from saldos_materiais_locais where material_id=?", Integer.class, saved.getId()));
            assertEquals(1L, independente.queryForObject("select count(*) from movimentacoes_estoque where material_id=?", Long.class, saved.getId()));
        }
        var e = new LinkedHashMap<String,Object>(); e.put("case",c); e.put("result",result); e.put("before",before); e.put("after",snapshot());
        Files.writeString(evidencePath().resolveSibling("SERVICO.jsonl"), mapper.writeValueAsString(e)+System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static List<String> guards() { return List.of("anonimo", "sem_csrf", "reauth_expirada", "perfil_auditor"); }
    @ParameterizedTest @MethodSource("guards") void security(String c) throws Exception {
        login(c.equals("perfil_auditor") ? "AUDITOR" : "ADMIN", !c.equals("reauth_expirada"));
        if (c.equals("reauth_expirada")) {
            // Login itself confirms the password. Age only the owned synthetic session fixture.
            var bytes = new java.io.ByteArrayOutputStream();
            try (var stream = new java.io.ObjectOutputStream(bytes)) { stream.writeObject(java.time.Instant.now().minusSeconds(600)); }
            assertEquals(1, jdbc.update("update spring_session_attributes set attribute_bytes=? where attribute_name='RC_REAUTENTICADO_EM'", bytes.toByteArray()));
        }
        var before = snapshot(); var p = payload("PROBE-GUARD", false, "20");
        var r = call("POST", "/api/estoque/materiais", p, !c.equals("anonimo"), !c.equals("sem_csrf"));
        var after = snapshot(); record("GUARD", c, p, r, before, after);
        assertEquals(c.equals("reauth_expirada") ? 428 : c.equals("anonimo") ? 401 : 403, r.statusCode(), r.body());
        assertEquals(before, after);
        if(c.equals("reauth_expirada")) {
            assertEquals(204, call("POST", "/api/auth/reauth", Map.of("senha", SENHA), true, true).statusCode());
            var restored = call("POST", "/api/estoque/materiais", p, true, true);
            record("GUARD", "reauth_renovada", p, restored, after, snapshot());
            assertEquals(201, restored.statusCode(), restored.body());
        }
    }
    void record(String role, String c, Object p, HttpResponse<String> r, Object before, Object after) throws Exception {
        var e = new LinkedHashMap<String,Object>();
        e.put("role",role); e.put("case",c); e.put("java",Runtime.version().toString());
        e.put("database",banco.url()); e.put("postgres",independente.queryForObject("show server_version",String.class));
        e.put("uploads",com.poprc.demo.storage.UploadStorage.root().toString()); e.put("payload",p);
        e.put("reserveRelatedTables", independente.queryForList("select tablename from pg_tables where schemaname='public' and tablename like '%reserv%' order by tablename", String.class));
        e.put("status",r.statusCode()); e.put("response",r.body()); e.put("before",before); e.put("after",after);
        Files.writeString(evidencePath(), mapper.writeValueAsString(e)+System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    Path evidencePath() throws Exception {
        Path path = Path.of(System.getProperty("evidence.path", "target/cadastro-post-estado-evidencias/CASOS.jsonl"));
        Files.createDirectories(path.toAbsolutePath().getParent());
        return path;
    }
}
