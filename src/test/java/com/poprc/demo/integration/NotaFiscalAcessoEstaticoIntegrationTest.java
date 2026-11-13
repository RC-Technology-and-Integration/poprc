package com.poprc.demo.integration;

import com.poprc.demo.support.BancoAberturaDescartavel;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;

/** A privacidade fiscal independe de login/modo dev; outros uploads preservam o contrato. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.import=","app.security.enabled=false","server.address=127.0.0.1"})
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class NotaFiscalAcessoEstaticoIntegrationTest {
    static final BancoAberturaDescartavel banco=new BancoAberturaDescartavel();
    static final String uploadAnterior=System.getProperty("app.upload.dir");
    @TempDir static Path uploads;
    @LocalServerPort int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        banco.create();
        System.setProperty("app.upload.dir",uploads.toString());
        r.add("spring.datasource.url",banco::url);r.add("spring.datasource.username",banco::user);
        r.add("spring.datasource.password",banco::password);r.add("app.upload.dir",()->uploads.toString());
    }
    @AfterAll static void close() throws Exception {
        try {banco.close();} finally {
            if(uploadAnterior==null)System.clearProperty("app.upload.dir");else System.setProperty("app.upload.dir",uploadAnterior);
        }
    }
    @Test void nfNaoTemAliasEstaticoMesmoEmModoDev() throws Exception {
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            for(String pasta:List.of("notas-fiscais",".notas-fiscais")) {
                Path dir=Files.createDirectories(uploads.resolve(pasta));
                Files.writeString(dir.resolve("original.xml"),"NF SINTETICA");
                Files.writeString(dir.resolve("arquivo.part"),"PARCIAL SINTETICO");
                for(String caminho:List.of(pasta+"/original.xml",pasta+"/arquivo.part",pasta.toUpperCase()+"/ORIGINAL.XML",pasta+"./original.xml")) {
                    var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/uploads/"+caminho)).timeout(Duration.ofSeconds(10)).build();
                    var response=client.send(req,HttpResponse.BodyHandlers.ofString());
                    assertTrue(response.statusCode()==401||response.statusCode()==403||response.statusCode()==404,caminho+" "+response.statusCode());
                    assertFalse(response.body().contains("SINTETIC"));
                }
            }
            Files.writeString(uploads.resolve("controle.txt"),"CONTROLE SINTETICO");
            var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/uploads/controle.txt")).timeout(Duration.ofSeconds(10)).build();
            var response=client.send(req,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertEquals("CONTROLE SINTETICO",response.body());
        }
    }
}
