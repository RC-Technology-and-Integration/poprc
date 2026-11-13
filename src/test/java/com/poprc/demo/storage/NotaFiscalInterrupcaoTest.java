package com.poprc.demo.storage;

import java.io.IOException;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import com.poprc.demo.support.BancoAberturaDescartavel;
import static org.junit.jupiter.api.Assertions.*;

/** JVM filha termina sem finally/shutdown hooks; PostgreSQL e arquivos são descartáveis. */
class NotaFiscalInterrupcaoTest {
    static BancoAberturaDescartavel banco;
    @TempDir Path root;
    @BeforeAll static void database() {
        banco=new BancoAberturaDescartavel();banco.create();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(banco.url(),banco.user(),banco.password()));
        banco.assertDestination(jdbc);
        jdbc.execute("create table nf_crash_probe(etapa text primary key, arquivo text)");
    }
    @AfterAll static void close() throws Exception {banco.close();}

    @ParameterizedTest @ValueSource(strings={"PARCIAL","RENOMEADO","PRE_COMMIT","POS_COMMIT"})
    void interrupcaoAbruptaRetemSomenteTentativaPrivada(String etapa) throws Exception {
        var java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        var process=new ProcessBuilder(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                Worker.class.getName(),etapa,root.toString(),banco.url(),banco.user(),banco.password())
                .redirectErrorStream(true).redirectOutput(root.resolve("worker.log").toFile()).start();
        boolean terminou=process.waitFor(45,TimeUnit.SECONDS);
        if(!terminou){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}
        assertTrue(terminou,"JVM filha não atingiu o ponto de interrupção");
        assertEquals(71,process.exitValue(),Files.readString(root.resolve("worker.log")));
        try(var c=DriverManager.getConnection(banco.url(),banco.user(),banco.password());var st=c.createStatement();
            var r=st.executeQuery("select arquivo from nf_crash_probe where etapa='"+etapa+"'")) {
            assertEquals(etapa.equals("POS_COMMIT"),r.next());
            if(etapa.equals("POS_COMMIT"))assertTrue(Files.isRegularFile(Path.of(r.getString(1))));
        }
        try(var files=Files.walk(root.resolve(".notas-fiscais"))) {
            var residuos=files.filter(Files::isRegularFile).toList();
            assertEquals(1,residuos.size());
            assertEquals(etapa.equals("PARCIAL")?"arquivo.part":"original.xml",residuos.getFirst().getFileName().toString());
            assertArrayEquals(etapa.equals("PARCIAL")?new byte[]{1}:new byte[]{1,2,3},Files.readAllBytes(residuos.getFirst()));
            System.out.println("NF_CRASH "+etapa+" commit="+etapa.equals("POS_COMMIT")+" privado="+residuos.getFirst());
        }
    }

    public static class Worker {
        public static void main(String[] args) {
            String etapa=args[0];
            var ds=new DriverManagerDataSource(args[2],args[3],args[4]);
            var jdbc=new JdbcTemplate(ds);
            var storage=new NotaFiscalArquivoStorage(args[1]) {
                @Override protected void escrever(Path p,byte[] b)throws IOException {
                    if(etapa.equals("PARCIAL")){Files.write(p,new byte[]{1},StandardOpenOption.CREATE_NEW);Runtime.getRuntime().halt(71);}
                    super.escrever(p,b);
                }
                @Override protected void publicar(Path p,Path d)throws IOException {
                    super.publicar(p,d);
                    if(etapa.equals("RENOMEADO"))Runtime.getRuntime().halt(71);
                }
            };
            new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{
                String path=storage.gravar("b".repeat(64),"XML",new byte[]{1,2,3});
                jdbc.update("insert into nf_crash_probe values(?,?)",etapa,path);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public void beforeCommit(boolean readOnly){if(etapa.equals("PRE_COMMIT"))Runtime.getRuntime().halt(71);}
                    @Override public void afterCommit(){if(etapa.equals("POS_COMMIT"))Runtime.getRuntime().halt(71);}
                });
                return null;
            });
            throw new AssertionError("Ponto de interrupção não atingido");
        }
    }
}
