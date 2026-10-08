package com.poprc.demo.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Runs only in the dedicated, disposable PostgreSQL 16 CI service. */
@EnabledIfSystemProperty(named = "poprc.pg16.migration", matches = "true")
class Postgres16MigrationCiTest {

    private static final String SERVICE_URL = "jdbc:postgresql://localhost:5432/poprc_test";
    private static final String ADMIN_URL = "jdbc:postgresql://localhost:5432/postgres";

    @Test
    void atualizacaoV38AV40PreservaHomologacoesLegadas() throws Exception {
        validarServidorDescartavel();
        String user = requiredEnv("TEST_DB_USERNAME");
        String password = requiredEnv("TEST_DB_PASSWORD");

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String legacy = "poprc_ci_legacy_" + suffix + "_test";
        List<String> created = new ArrayList<>();
        try {
            createDatabase(legacy, user, password);
            created.add(legacy);

            Flyway legacyTo38 = flyway(legacy, user, password, "38");
            legacyTo38.migrate();
            assertMigrationCount(legacy, user, password, 38);
            seedLegacyRows(legacy, user, password);

            flyway(legacy, user, password, null).migrate();
            assertMigrationCount(legacy, user, password, 40);
            assertLegacyRowsPreserved(legacy, user, password);
        } finally {
            for (String database : created) {
                dropDatabase(database, user, password);
                assertFalse(databaseExists(database, user, password), "Banco temporário não foi removido: " + database);
            }
        }
    }

    @Test
    void bancoVazioRecebeV1AV40() throws Exception {
        validarServidorDescartavel();
        String user = requiredEnv("TEST_DB_USERNAME"), password = requiredEnv("TEST_DB_PASSWORD");
        String database = "poprc_ci_fresh_" + UUID.randomUUID().toString().replace("-", "") + "_test";
        createDatabase(database, user, password);
        try {
            assertEquals(40, flyway(database, user, password, null).migrate().migrationsExecuted);
            assertMigrationCount(database, user, password, 40);
        } finally {
            dropDatabase(database, user, password);
            assertFalse(databaseExists(database, user, password));
        }
    }

    private static void validarServidorDescartavel() throws Exception {
        assertEquals("true", System.getenv("GITHUB_ACTIONS"), "Este teste só pode criar bancos no runner do CI.");
        assertEquals(SERVICE_URL, requiredEnv("TEST_DB_URL"), "Destino inesperado para o PostgreSQL descartável.");
        String user = requiredEnv("TEST_DB_USERNAME");
        String password = requiredEnv("TEST_DB_PASSWORD");
        assertEquals("postgres", user, "Usuário inesperado para o serviço descartável do CI.");

        try (Connection admin = DriverManager.getConnection(ADMIN_URL, user, password)) {
            assertEquals(16, serverMajor(admin), "O teste exige PostgreSQL 16 real.");
            try (Statement statement = admin.createStatement();
                    ResultSet result = statement.executeQuery("SHOW server_version")) {
                assertTrue(result.next());
                System.out.println("PostgreSQL efetivo no CI: " + result.getString(1));
            }
        }

    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        assertFalse(value == null || value.isBlank(), name + " precisa estar definido explicitamente.");
        return value;
    }

    private static int serverMajor(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SHOW server_version_num")) {
            assertTrue(result.next());
            return result.getInt(1) / 10000;
        }
    }

    private static Flyway flyway(String database, String user, String password, String target) {
        var config = Flyway.configure()
                .dataSource(url(database), user, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static String url(String database) {
        return "jdbc:postgresql://localhost:5432/" + database;
    }

    private static void createDatabase(String database, String user, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, user, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
    }

    private static void dropDatabase(String database, String user, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, user, password);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static boolean databaseExists(String database, String user, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, user, password);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM pg_database WHERE datname = ?")) {
            statement.setString(1, database);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void assertMigrationCount(String database, String user, String password, int expected)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(url(database), user, password);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT count(*), max(version::integer), bool_and(success) "
                                + "FROM flyway_schema_history WHERE type = 'SQL'")) {
            assertTrue(result.next());
            assertEquals(expected, result.getInt(1));
            assertEquals(expected, result.getInt(2));
            assertTrue(result.getBoolean(3));
        }
    }

    private static void seedLegacyRows(String database, String user, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url(database), user, password);
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO comarcas (nome_comarca, as_built_status, situacao, quantidade_pontos) "
                                + "VALUES (?, ?, ?, ?)")) {
            String[][] rows = {
                    {"CI LEGADO DIVERGENTE", "HOMOLOGADO_COM_DIVERGENCIA", "AS_BUILT_HOMOLOGADO_COM_DIVERGENCIA", "11"},
                    {"CI LEGADO CONCILIADO", "HOMOLOGADO", "AS_BUILT_HOMOLOGADO", "12"},
                    {"CI AINDA PENDENTE", "PENDENTE", "EM_ANDAMENTO", "13"}
            };
            for (String[] row : rows) {
                insert.setString(1, row[0]);
                insert.setString(2, row[1]);
                insert.setString(3, row[2]);
                insert.setInt(4, Integer.parseInt(row[3]));
                assertEquals(1, insert.executeUpdate());
            }
        }
    }

    private static void assertLegacyRowsPreserved(String database, String user, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url(database), user, password);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT nome_comarca, as_built_status, situacao, quantidade_pontos, "
                                + "as_built_homologacao_legada_sem_justificativa "
                                + "FROM comarcas WHERE nome_comarca LIKE 'CI %' ORDER BY quantidade_pontos")) {
            String[][] expected = {
                    {"CI LEGADO DIVERGENTE", "HOMOLOGADO_COM_DIVERGENCIA", "AS_BUILT_HOMOLOGADO_COM_DIVERGENCIA"},
                    {"CI LEGADO CONCILIADO", "HOMOLOGADO", "AS_BUILT_HOMOLOGADO"},
                    {"CI AINDA PENDENTE", "PENDENTE", "EM_ANDAMENTO"}
            };
            for (int i = 0; i < expected.length; i++) {
                assertTrue(result.next());
                assertEquals(expected[i][0], result.getString(1));
                assertEquals(expected[i][1], result.getString(2));
                assertEquals(expected[i][2], result.getString(3));
                assertEquals(11 + i, result.getInt(4));
                assertFalse(result.getBoolean(5), "V39 não deve inventar marcação histórica.");
            }
            assertFalse(result.next());
        }

        try (Connection connection = DriverManager.getConnection(url(database), user, password);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM historico_homologacao_as_built")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1), "V39 não deve inventar justificativa ou histórico.");
        }

        try (Connection connection = DriverManager.getConnection(url(database), user, password);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT nome_comarca FROM comarcas c WHERE c.as_built_status = 'HOMOLOGADO_COM_DIVERGENCIA' "
                                + "AND NOT EXISTS (SELECT 1 FROM historico_homologacao_as_built h WHERE h.comarca_id = c.id)")) {
            assertTrue(result.next(), "A homologação divergente legada deve continuar identificável.");
            assertEquals("CI LEGADO DIVERGENTE", result.getString(1));
            assertFalse(result.next());
        }
    }
}
