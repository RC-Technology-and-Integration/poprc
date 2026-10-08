package com.poprc.demo.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.poprc.demo.model.*;
import com.poprc.demo.repository.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SimulacaoPdfTest {
    private static final Path OUTPUT = Path.of("target/simulacao-pdf-review");
    @Test void marcaTodasAsPaginasInclusiveAssinaturasERegeneracao() throws Exception {
        var service = new DocumentoPdfService(new ObjectMapper(), mock(DocumentoInternoRepository.class));
        Files.createDirectories(OUTPUT);
        for (String tipo : new String[] {"VISTORIA_INICIAL_OS", "ENCERRAMENTO_OS"}) {
            var doc = new DocumentoInterno();
            doc.setSimulacao(true);
            doc.setId(900L);
            doc.setTipo(tipo);
            doc.setConteudoJson("{\"numeroOs\":\"TESTE OS\",\"descricaoServicos\":\"Cenário fictício TESTE\"}");
            doc.setAssinaturaTecnicoBase64(com.poprc.demo.support.AssinaturaTeste.assinaturaTeste());
            doc.setAssinaturaGestorBase64(com.poprc.demo.support.AssinaturaTeste.assinaturaTeste());
            doc.setAssinaturaGerenteBase64(com.poprc.demo.support.AssinaturaTeste.assinaturaTeste());
            for (int geracao = 1; geracao <= 2; geracao++) {
                byte[] bytes = service.gerarPdf(doc);
                conferir(bytes, 6);
                guardar(tipo + "-" + geracao, bytes);
            }
        }
    }
    @Test void marcaOrMultipaginaEAssinaturasSemInstituicaoReal() throws Exception {
        var service = new OrdemRetiradaPdfService(mock(OrdemRetiradaRepository.class), mock(OrdemRetiradaDocumentoRepository.class));
        var os = new OrdemServico();
        os.setSimulacao(true);
        os.setNumeroOs("TESTE OS");
        var or = new OrdemRetirada();
        or.setOrdemServico(os);
        or.setNumeroOr("TESTE OR");
        var itens = new ArrayList<OrdemRetiradaItem>();
        for (int i = 0; i < 90; i++) {
            var item = new OrdemRetiradaItem();
            item.setNomeMaterial("Material fictício TESTE " + i);
            item.setQuantidadeSolicitada(BigDecimal.ONE);
            itens.add(item);
        }
        or.setItens(itens);
        or.setAssinaturaConferenteBase64(com.poprc.demo.support.AssinaturaTeste.assinaturaTeste());
        or.setAssinaturaRetiranteBase64(com.poprc.demo.support.AssinaturaTeste.assinaturaTeste());
        or.setAssinaturaRecebimentoBase64(com.poprc.demo.support.AssinaturaTeste.assinaturaTeste());
        byte[] pdf = service.gerarPdf(or);
        conferir(pdf, 3);
        Files.createDirectories(OUTPUT);
        guardar("OR-multipagina", pdf);
    }
    private void guardar(String nome, byte[] bytes) throws Exception {
        Files.write(OUTPUT.resolve(nome + ".pdf"), bytes);
        try (var doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            var renderer = new org.apache.pdfbox.rendering.PDFRenderer(doc);
            int paginas = doc.getNumberOfPages();
            var contato = new java.awt.image.BufferedImage(900, ((paginas + 2) / 3) * 460, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = contato.createGraphics();
            graphics.setColor(java.awt.Color.GRAY); graphics.fillRect(0, 0, contato.getWidth(), contato.getHeight());
            for (int page = 0; page < paginas; page++) {
                var image = renderer.renderImageWithDPI(page, 100);
                javax.imageio.ImageIO.write(image, "png", OUTPUT.resolve(nome + "-pagina-" + (page + 1) + ".png").toFile());
                graphics.drawImage(image, (page % 3) * 300, (page / 3) * 460, 300, 425, null);
                graphics.setColor(java.awt.Color.WHITE); graphics.drawString("Página " + (page + 1), (page % 3) * 300 + 12, (page / 3) * 460 + 445);
            }
            graphics.dispose();
            javax.imageio.ImageIO.write(contato, "png", OUTPUT.resolve(nome + "-contato.png").toFile());
        }
    }
    private void conferir(byte[] bytes, int minimo) throws Exception {
        try (var reader = new PdfReader(bytes)) {
            assertTrue(reader.getNumberOfPages() >= minimo);
            var extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                String texto = extractor.getTextFromPage(page);
                assertTrue(texto.contains(SimulacaoDocumentoService.MARCA), "Marca ausente na página " + page);
                assertTrue(texto.contains(SimulacaoDocumentoService.EMPRESA));
                assertFalse(texto.contains("RC TECHNOLOGY AND INTEGRATION LTDA"));
                assertFalse(texto.contains("33.910.895"));
            }
        }
    }
}
