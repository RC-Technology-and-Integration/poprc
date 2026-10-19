package com.poprc.demo.service;

import com.poprc.demo.dto.NotaFiscalEstoqueDTO;
import com.poprc.demo.model.ImportacaoNotaFiscal;
import com.poprc.demo.model.ImportacaoNotaFiscalItem;
import com.poprc.demo.model.LocalEstoque;
import com.poprc.demo.model.Material;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.repository.ImportacaoNotaFiscalItemRepository;
import com.poprc.demo.repository.ImportacaoNotaFiscalRepository;
import com.poprc.demo.repository.LocalEstoqueRepository;
import com.poprc.demo.repository.MaterialRepository;
import com.poprc.demo.security.UsuarioAutenticado;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.poprc.demo.model.Funcionario;
import com.poprc.demo.model.TipoControleEstoque;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

class ImportacaoNotaFiscalEstoqueServiceTest {
    private ImportacaoNotaFiscalRepository importacaoRepository;
    private ImportacaoNotaFiscalItemRepository itemRepository;
    private MaterialRepository materialRepository;
    private FuncionarioRepository funcionarioRepository;
    private LocalEstoqueRepository localRepository;
    private EstoqueService estoqueService;
    private ImportacaoNotaFiscalEstoqueService service;
    @TempDir Path uploads;

    @BeforeEach
    void setUp() {
        importacaoRepository = mock(ImportacaoNotaFiscalRepository.class);
        itemRepository = mock(ImportacaoNotaFiscalItemRepository.class);
        materialRepository = mock(MaterialRepository.class);
        funcionarioRepository = mock(FuncionarioRepository.class);
        localRepository = mock(LocalEstoqueRepository.class);
        estoqueService = mock(EstoqueService.class);
        service = new ImportacaoNotaFiscalEstoqueService(
                importacaoRepository, itemRepository, materialRepository,
                funcionarioRepository, localRepository, estoqueService);
        ReflectionTestUtils.setField(service, "uploadDir", uploads.toString());
    }

    @ParameterizedTest
    @EnumSource(value = TipoControleEstoque.class, names = {"FRACIONADO", "METRAGEM", "UNIDADE"})
    void deveConfirmarNotaVinculadaUsandoOEixoDeQuantidadeDoMaterial(TipoControleEstoque tipo) {
        var arquivo = arquivoXml();
        var preview = service.analisar(arquivo);
        var funcionario = new Funcionario(); funcionario.setId(1L); funcionario.setNome("TESTE NF");
        var local = new LocalEstoque(); local.setId(3L); local.setAtivo(true);
        var material = new Material(); material.setId(7L); material.setTipoControle(tipo);
        when(funcionarioRepository.findById(1L)).thenReturn(Optional.of(funcionario));
        when(localRepository.findById(3L)).thenReturn(Optional.of(local));
        when(materialRepository.findById(7L)).thenReturn(Optional.of(material));
        when(importacaoRepository.save(any())).thenAnswer(invocation -> {
            ImportacaoNotaFiscal nota = invocation.getArgument(0); nota.setId(9L); return nota;
        });
        boolean decimal = tipo != TipoControleEstoque.UNIDADE;
        BigDecimal quantidade = new BigDecimal(decimal ? "0.125" : "2");
        BigDecimal custo = new BigDecimal("1.2345");
        var item = new NotaFiscalEstoqueDTO.ItemConfirmacao(true, 7L, "TESTE", null, null,
                "TESTE SINTETICO", null, null, "UN", quantidade, custo, null, null, null);
        var request = new NotaFiscalEstoqueDTO.ConfirmarRequest(arquivo.nomeArquivo(), arquivo.contentType(),
                arquivo.arquivoBase64(), preview.hashSha256(), 3L, preview.chaveAcesso(), preview.numero(),
                preview.serie(), preview.emitenteNome(), preview.emitenteCnpj(), preview.dataEmissao(), List.of(item));

        var resultado = service.confirmar(request,
                new UsuarioAutenticado(1L, "TESTE NF", null, "ADMIN", "CPF_SENHA", false));

        assertEquals(1, resultado.itensProcessados());
        verify(estoqueService).registrarEntradaNotaFiscal(7L, decimal ? null : 2,
                decimal ? quantidade : null, custo, 1L, 3L, preview.numero());
    }

    @Test
    void deveLerXmlNfeEReconhecerItemPorMetragem() {
        NotaFiscalEstoqueDTO.Preview preview = service.analisar(arquivoXml());

        assertEquals("123", preview.numero());
        assertEquals("1", preview.serie());
        assertEquals("Fornecedor Teste", preview.emitenteNome());
        assertEquals("11222333000181", preview.emitenteCnpj());
        assertEquals("150.00", preview.valorTotal().toPlainString());
        assertEquals(1, preview.itens().size());
        assertEquals("Cabo de rede CAT6", preview.itens().get(0).descricao());
        assertEquals("50.0000", preview.itens().get(0).quantidade().toPlainString());
        assertEquals("METRAGEM", preview.itens().get(0).tipoControleSugerido());
        assertTrue(preview.hashSha256().matches("[a-f0-9]{64}"));
    }

    @Test
    void deveBloquearConfirmacaoDoMesmoArquivoDuasVezes() {
        NotaFiscalEstoqueDTO.ArquivoRequest arquivo = arquivoXml();
        NotaFiscalEstoqueDTO.Preview preview = service.analisar(arquivo);
        when(importacaoRepository.existsByHashSha256(preview.hashSha256())).thenReturn(true);
        NotaFiscalEstoqueDTO.ConfirmarRequest request = new NotaFiscalEstoqueDTO.ConfirmarRequest(
                arquivo.nomeArquivo(), arquivo.contentType(), arquivo.arquivoBase64(), preview.hashSha256(),
                1L, preview.chaveAcesso(), preview.numero(), preview.serie(), preview.emitenteNome(),
                preview.emitenteCnpj(), preview.dataEmissao(), List.of());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.confirmar(request,
                        new UsuarioAutenticado(1L, "Admin", null, "ADMIN", "CPF_SENHA", false)));

        assertEquals("Esta nota fiscal já foi importada.", exception.getMessage());
        verifyNoInteractions(funcionarioRepository, localRepository, estoqueService);
    }

    @Test
    void deveUnirDescricaoEmDuasLinhasEPreservarCodigoDoProdutoNoPdf() throws Exception {
        NotaFiscalEstoqueDTO.Preview preview = service.analisar(arquivoPdfDuasLinhas());

        assertEquals(2, preview.itens().size());
        assertEquals("760237040", preview.itens().get(0).codigoProduto());
        assertEquals("PATCH PANEL 24 PORTAS C/ GUIA TRASEIRO", preview.itens().get(0).descricao());
        assertEquals("884061804/1", preview.itens().get(1).codigoProduto());
        assertEquals("CABO U/UTP CAT.6A C/ 305MTS COMMSCOPE", preview.itens().get(1).descricao());
        assertEquals("8147", preview.numero());
        assertEquals("001", preview.serie());
        assertEquals("32260604326382000280550010000081471899352770", preview.chaveAcesso());
        assertEquals("FORNECEDOR TESTE LTDA", preview.emitenteNome());
        assertEquals("04.326.382/0002-80", preview.emitenteCnpj());
        assertEquals("2026-06-12T00:00", preview.dataEmissao().toString());
    }

    @Test
    void deveDetalharNotaFiscalComMaterialEVinculoFiscal() {
        LocalEstoque local = new LocalEstoque();
        local.setId(3L);
        local.setNome("Depósito Central");
        ImportacaoNotaFiscal importacao = new ImportacaoNotaFiscal();
        importacao.setId(9L);
        importacao.setNomeArquivo("danfe-8147.pdf");
        importacao.setTipoArquivo("PDF");
        importacao.setNumero("8147");
        importacao.setSerie("001");
        importacao.setEmitenteNome("Fornecedor Teste");
        importacao.setEmitenteCnpj("04.326.382/0002-80");
        importacao.setDataEmissao(LocalDateTime.of(2026, 6, 12, 0, 0));
        importacao.setDataImportacao(LocalDateTime.of(2026, 8, 4, 14, 0));
        importacao.setImportadoPor("Administrador");
        importacao.setValorTotal(new BigDecimal("3542.89"));
        importacao.setMateriaisCriados(1);
        importacao.setMateriaisExistentes(0);
        importacao.setLocalEstoque(local);

        Material material = new Material();
        material.setId(21L);
        material.setNome("Patch panel 24 portas");
        material.setPartNumber("760237040");
        ImportacaoNotaFiscalItem item = new ImportacaoNotaFiscalItem();
        item.setId(33L);
        item.setImportacao(importacao);
        item.setMaterial(material);
        item.setCodigoProduto("760237040");
        item.setDescricao("PATCH PANEL 24 PORTAS C/ GUIA TRASEIRO");
        item.setNcm("85177900");
        item.setCfop("6102");
        item.setUnidadeFiscal("PC");
        item.setQuantidade(new BigDecimal("13"));
        item.setValorUnitario(new BigDecimal("272.53"));
        item.setValorTotal(new BigDecimal("3542.89"));
        item.setAcao("MATERIAL_CRIADO");
        when(importacaoRepository.findById(9L)).thenReturn(Optional.of(importacao));
        when(itemRepository.findByImportacaoIdOrderByIdAsc(9L)).thenReturn(List.of(item));

        NotaFiscalEstoqueDTO.Detalhe detalhe = service.detalhar(9L);

        assertEquals("8147", detalhe.numero());
        assertEquals("Depósito Central", detalhe.localEstoque());
        assertEquals(1, detalhe.itens().size());
        assertEquals("760237040", detalhe.itens().get(0).codigoProduto());
        assertEquals("Patch panel 24 portas", detalhe.itens().get(0).material());
        assertEquals("3542.89", detalhe.itens().get(0).valorTotal().toPlainString());
    }

    private NotaFiscalEstoqueDTO.ArquivoRequest arquivoXml() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe">
                  <NFe><infNFe Id="NFe12345678901234567890123456789012345678901234">
                    <ide><nNF>123</nNF><serie>1</serie><dhEmi>2026-08-04T10:30:00-03:00</dhEmi></ide>
                    <emit><CNPJ>11222333000181</CNPJ><xNome>Fornecedor Teste</xNome></emit>
                    <det nItem="1"><prod><cProd>CABO-CAT6</cProd><xProd>Cabo de rede CAT6</xProd>
                      <NCM>85444900</NCM><CFOP>5102</CFOP><uCom>M</uCom><qCom>50.0000</qCom>
                      <vUnCom>3.0000</vUnCom><vProd>150.00</vProd></prod></det>
                    <total><ICMSTot><vNF>150.00</vNF></ICMSTot></total>
                  </infNFe></NFe>
                </nfeProc>
                """;
        return new NotaFiscalEstoqueDTO.ArquivoRequest(
                "nota.xml", "application/xml",
                Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private NotaFiscalEstoqueDTO.ArquivoRequest arquivoPdfDuasLinhas() throws Exception {
        List<String> linhas = List.of(
                "32260604326382000280550010000081471899352770",
                "FORNECEDOR TESTE LTDA Nº 8147",
                "SÉRIE 001",
                "04.326.382/0002-80",
                "12/06/2026",
                "DADOS DOS PRODUTOS / SERVICOS",
                "760237040 PATCH PANEL 24 PORTAS C/ 85177900 200 6102 PC 13 272,530000000 3.542,89 3.542,89 12345678901234567890123456789012345678901234",
                "GUIA TRASEIRO 12345678901234567890123456789012345678901234",
                "12",
                "884061804/1 CABO U/UTP CAT.6A C/ 85444900 200 6102 CX 25 2.196,40000000 54.910,00 54.910,00 12345678901234567890123456789012345678901234",
                "0 305MTS COMMSCOPE 12345678901234567890123456789012345678901234",
                "DADOS ADICIONAIS");
        try (PDDocument documento = new PDDocument();
                ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            PDPage pagina = new PDPage();
            documento.addPage(pagina);
            try (PDPageContentStream conteudo = new PDPageContentStream(documento, pagina)) {
                conteudo.beginText();
                conteudo.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 7);
                conteudo.newLineAtOffset(20, 760);
                for (String linha : linhas) {
                    conteudo.showText(linha);
                    conteudo.newLineAtOffset(0, -12);
                }
                conteudo.endText();
            }
            documento.save(saida);
            return new NotaFiscalEstoqueDTO.ArquivoRequest(
                    "danfe.pdf", "application/pdf",
                    Base64.getEncoder().encodeToString(saida.toByteArray()));
        }
    }
}
