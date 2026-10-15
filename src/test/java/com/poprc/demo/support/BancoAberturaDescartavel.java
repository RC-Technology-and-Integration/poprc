package com.poprc.demo.support;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Cria seu próprio banco, sem alterar o banco usado para localizar o servidor de teste. */
public final class BancoAberturaDescartavel implements AutoCloseable {
    private final String database = "poprc_abertura_" + UUID.randomUUID().toString().replace("-", "") + "_test";
    private String adminUrl;
    private String url;
    private String user;
    private String password;
    private boolean owned;

    public synchronized void create() {
        if (owned) return;
        String source = required("TEST_DB_URL");
        assertTrue(source.startsWith("jdbc:postgresql://"), "Exige URL PostgreSQL explícita.");
        URI uri = URI.create(source.substring(5));
        assertTrue(Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()), "Exige servidor de teste em loopback.");
        assertNull(uri.getUserInfo(), "Credenciais devem vir de variáveis separadas.");
        assertNull(uri.getQuery(), "Parâmetros de conexão não são aceitos pelo executor descartável.");
        assertNull(uri.getFragment());
        assertTrue(uri.getPath().matches("/[a-zA-Z0-9_]+_test"), "O destino de referência precisa terminar em _test.");
        user = required("TEST_DB_USERNAME");
        password = required("TEST_DB_PASSWORD");
        String server = source.substring(0, source.lastIndexOf('/') + 1);
        adminUrl = server + "postgres";
        url = server + database;
        try (var connection = DriverManager.getConnection(adminUrl, user, password);
             var statement = connection.createStatement()) {
            if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
                assertEquals(25, Runtime.version().feature(), "CI exige Java 25 real.");
                try (var result = statement.executeQuery("SHOW server_version_num")) {
                    assertTrue(result.next());
                    assertEquals(16, result.getInt(1) / 10000, "CI exige PostgreSQL 16 real.");
                }
            }
            // Sem IF NOT EXISTS: colisão nunca concede propriedade de um banco alheio.
            statement.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            owned = true;
        } catch (Exception exception) {
            throw new IllegalStateException("Falha ao criar banco exclusivo do ensaio.", exception);
        }
        System.out.println("Abertura: Java " + Runtime.version() + "; banco descartável " + database);
    }

    public void assertDestination(JdbcTemplate jdbc) {
        assertTrue(owned, "Nenhuma limpeza sem criação comprovada pelo próprio teste.");
        assertTrue(database.matches("poprc_abertura_[a-f0-9]{32}_test"));
        assertEquals(database, jdbc.queryForObject("select current_database()", String.class));
        assertEquals("public", jdbc.queryForObject("select current_schema()", String.class));
        assertEquals(user, jdbc.queryForObject("select current_user", String.class));
        String actualUrl = jdbc.execute((Connection connection) -> connection.getMetaData().getURL());
        assertEquals(url, actualUrl, "Conexão não corresponde ao banco criado por este recurso.");
    }

    @Override public synchronized void close() throws Exception {
        if (!owned) return;
        assertTrue(database.matches("poprc_abertura_[a-f0-9]{32}_test"));
        try (var connection = DriverManager.getConnection(adminUrl, user, password);
             var statement = connection.createStatement()) {
            // Somente o nome gerado e criado por esta instância, após o fim do contexto.
            statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            try (var query = connection.prepareStatement("select count(*) from pg_database where datname=?")) {
                query.setString(1, database);
                try (var result = query.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1), "Banco descartável não foi removido.");
                }
            }
            owned = false;
        }
        System.out.println("Abertura: banco descartável removido: " + database);
    }

    public String url() { return url; }
    public String user() { return user; }
    public String password() { return password; }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " obrigatório; nunca usa configuração local.");
        assertFalse(value.isBlank(), name + " vazio.");
        return value;
    }
}
