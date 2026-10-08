package com.poprc.demo.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SimulacaoMigrationTest {
    @Test void v40PreservaDocumentoAnteriorEImpedeConversao() throws Exception {
        String url = System.getenv("TEST_DB_URL");
        assertNotNull(url, "Informe explicitamente o banco descartável");
        assertTrue(url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-z0-9_]+_test"));
        String database = "simulacao_v40_" + UUID.randomUUID().toString().replace("-", "") + "_test";
        String adminUrl = url.substring(0, url.lastIndexOf('/') + 1) + "postgres";
        String migrationUrl = url.substring(0, url.lastIndexOf('/') + 1) + database;
        String user = System.getenv("TEST_DB_USERNAME"), password = System.getenv("TEST_DB_PASSWORD");
        try (var admin = DriverManager.getConnection(adminUrl, user, password)) {
            try (var sql = admin.createStatement()) { sql.execute("CREATE DATABASE " + database); }
            try (var connection = DriverManager.getConnection(migrationUrl, user, password)) {
                Flyway.configure().dataSource(migrationUrl, user, password)
                        .locations("classpath:db/migration").target("39").load().migrate();
                try (var sql = connection.createStatement()) {
                    sql.execute("INSERT INTO ordens_servico (id, numero_os) VALUES (10, 'TESTE LEGADO 10'), (11, 'TESTE LEGADO 11')");
                    sql.execute("INSERT INTO documentos_internos (tipo, status, conteudo_json, hash_registro, assinatura_tecnico_base64, pdf_hash, pdf_path) VALUES ('ENCERRAMENTO_OS','REGISTRADO','{\"texto\":\"TESTE LEGADO\"}','hash-legado-preservado','TESTE-ASSINATURA-LEGADA','hash-pdf-legado','TESTE/pdf-legado.pdf')");
                }
                assertEquals(1, Flyway.configure().dataSource(migrationUrl, user, password)
                        .locations("classpath:db/migration").load().migrate().migrationsExecuted, "A base V39 deve receber somente V40");
                try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT simulacao, conteudo_json, hash_registro, assinatura_tecnico_base64, pdf_hash, pdf_path FROM documentos_internos")) {
                    assertTrue(rows.next()); assertFalse(rows.getBoolean(1));
                    assertEquals("{\"texto\":\"TESTE LEGADO\"}", rows.getString(2));
                    assertEquals("hash-legado-preservado", rows.getString(3));
                    assertEquals("TESTE-ASSINATURA-LEGADA", rows.getString(4));
                    assertEquals("hash-pdf-legado", rows.getString(5));
                    assertEquals("TESTE/pdf-legado.pdf", rows.getString(6));
                }
                try (var sql = connection.createStatement()) {
                    assertThrows(java.sql.SQLException.class, () -> sql.execute("UPDATE documentos_internos SET simulacao = true"));
                }
                try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT id, simulacao FROM ordens_servico ORDER BY id")) {
                    assertTrue(rows.next()); assertEquals(10, rows.getInt(1)); assertFalse(rows.getBoolean(2));
                    assertTrue(rows.next()); assertEquals(11, rows.getInt(1)); assertFalse(rows.getBoolean(2));
                    assertFalse(rows.next());
                }
                try (var sql = connection.createStatement()) {
                    sql.execute("INSERT INTO ordens_servico (numero_os, simulacao) VALUES ('TESTE NOVA OS FICTICIA', true)");
                    try (var rows = sql.executeQuery("SELECT simulacao FROM ordens_servico WHERE numero_os='TESTE NOVA OS FICTICIA'")) {
                        assertTrue(rows.next()); assertTrue(rows.getBoolean(1));
                    }
                    assertThrows(java.sql.SQLException.class, () -> sql.execute("UPDATE ordens_servico SET simulacao=false WHERE numero_os='TESTE NOVA OS FICTICIA'"));
                }

            } finally {
                try (var sql = admin.createStatement()) { sql.execute("DROP DATABASE " + database + " WITH (FORCE)"); }
            }
        }
    }
}
