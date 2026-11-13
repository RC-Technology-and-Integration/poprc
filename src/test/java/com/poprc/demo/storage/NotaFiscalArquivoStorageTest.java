package com.poprc.demo.storage;

import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;

class NotaFiscalArquivoStorageTest {
    @TempDir Path root;
    static final String HASH = "a".repeat(64);
    @BeforeEach void transaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }
    void finish(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(status));
    }
    long files() throws IOException {try(var s=Files.walk(root)){return s.filter(Files::isRegularFile).count();}}

    @Test void rollbackNaoTocaArquivoAnteriorNemMesmoHash() throws Exception {
        var storage = new NotaFiscalArquivoStorage(root.toString());
        Path anterior = Path.of(storage.gravar(HASH,"XML",new byte[]{1}));
        finish(TransactionSynchronization.STATUS_COMMITTED);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        Path tentativa = Path.of(storage.gravar(HASH,"XML",new byte[]{2}));
        assertNotEquals(anterior,tentativa);
        finish(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertFalse(Files.exists(tentativa));
        assertArrayEquals(new byte[]{1},Files.readAllBytes(anterior));
    }
    @Test void resultadoIncertoRetemArquivoPrivado() throws Exception {
        var path=Path.of(new NotaFiscalArquivoStorage(root.toString()).gravar(HASH,"PDF",new byte[]{1}));
        finish(TransactionSynchronization.STATUS_UNKNOWN);
        assertTrue(path.startsWith(root.resolve(".notas-fiscais")));
        assertTrue(Files.exists(path));
    }
    @Test void escritaParcialFalhaESofreCompensacao() throws Exception {
        var storage=new NotaFiscalArquivoStorage(root.toString()) {
            @Override protected void escrever(Path path,byte[] bytes) throws IOException {
                Files.write(path,new byte[]{1},StandardOpenOption.CREATE_NEW);
                throw new IOException("falha sintética de disco");
            }
        };
        assertThrows(IllegalArgumentException.class,()->storage.gravar(HASH,"XML",new byte[]{1,2}));
        assertEquals(1,files());
        finish(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertEquals(0,files());
    }
    @Test void falhaPublicacaoNaoDeixaOriginal() throws Exception {
        var storage=new NotaFiscalArquivoStorage(root.toString()) {
            @Override protected void publicar(Path a,Path b) throws IOException {throw new IOException("rename sintético");}
        };
        assertThrows(IllegalArgumentException.class,()->storage.gravar(HASH,"XML",new byte[]{1,2}));
        finish(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertEquals(0,files());
    }
    @Test void semTransacaoNaoEscreve() throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class,()->new NotaFiscalArquivoStorage(root.toString()).gravar(HASH,"XML",new byte[]{1}));
        assertEquals(0,files());
    }
}
