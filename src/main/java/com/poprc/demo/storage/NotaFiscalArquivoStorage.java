package com.poprc.demo.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Arquivo privado por tentativa. O vínculo só fica visível com o commit SQL. */
@Component
public class NotaFiscalArquivoStorage {
    private static final Logger log = LoggerFactory.getLogger(NotaFiscalArquivoStorage.class);
    private final Path raiz;

    public NotaFiscalArquivoStorage(@Value("${app.upload.dir:rc_uploads}") String uploadDir) {
        raiz = Path.of(uploadDir).toAbsolutePath().normalize().resolve(".notas-fiscais");
    }

    public String gravar(String hash, String tipo, byte[] bytes) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Arquivamento de NF exige transação ativa.");
        }
        if (!hash.matches("[a-f0-9]{64}") || !("XML".equals(tipo) || "PDF".equals(tipo))) {
            throw new IllegalArgumentException("Identificação de arquivo fiscal inválida.");
        }
        try {
            Files.createDirectories(raiz);
            // Criação exclusiva: não reusa hash, arquivo legado ou tentativa anterior.
            Path tentativa = Files.createTempDirectory(raiz, hash + "-");
            Path parcial = tentativa.resolve("arquivo.part");
            Path destino = tentativa.resolve("original." + tipo.toLowerCase(java.util.Locale.ROOT));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        try {
                            // Somente os dois nomes desta tentativa; nunca há varredura recursiva.
                            Files.deleteIfExists(parcial);
                            Files.deleteIfExists(destino);
                            Files.delete(tentativa);
                        } catch (IOException failure) {
                            log.error("NF: compensação incompleta; reter tentativa {} para recuperação", tentativa, failure);
                        }
                    } else if (status != STATUS_COMMITTED) {
                        log.error("NF: resultado transacional incerto; reter tentativa {}", tentativa);
                    }
                }
            });
            escrever(parcial, bytes);
            publicar(parcial, destino);
            return destino.toString();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Não foi possível arquivar a nota fiscal.", exception);
        }
    }

    protected void escrever(Path parcial, byte[] bytes) throws IOException {
        Files.write(parcial, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    protected void publicar(Path parcial, Path destino) throws IOException {
        // Sem fallback para cópia ou substituição. Falha de rename aborta a transação.
        Files.move(parcial, destino, StandardCopyOption.ATOMIC_MOVE);
    }
}
