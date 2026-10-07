package com.poprc.demo.controller;

import com.poprc.demo.model.Comarca;
import com.poprc.demo.model.DocumentoInterno;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.DocumentoAssinaturaLogRepository;
import com.poprc.demo.repository.DocumentoInternoRepository;
import com.poprc.demo.security.UsuarioAutenticado;
import com.poprc.demo.service.DocumentoPdfService;
import com.poprc.demo.service.AcessoOperacionalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentoInternoControllerTest {

    @Test
    void simulacaoDevePreservarPngCapturadoPeloFluxoNormal() {
        documento.setSimulacao(true);
        controller = new DocumentoInternoController(documentoRepository, comarcaRepository,
                logRepository, mock(DocumentoPdfService.class), mock(AcessoOperacionalService.class),
                new com.poprc.demo.service.SimulacaoDocumentoService(true, "homologacao"));
        var request = assinaturaPngValida();
        String capturada = request.getAssinaturaBase64();
        var salvo = controller.assinarDocumentoPorPapel(1L, "TECNICO", request, autenticacao("Sistema")).getBody();
        assertEquals(capturada, salvo.getAssinaturaTecnicoBase64(),
                "A simulação deve preservar o PNG capturado, sem trocar por assinatura automática.");
        assertTrue(salvo.getTecnicoAssinadoPor().startsWith("TESTE"));
    }

    @Test
    void simulacaoExplicitaDeveSerRecusadaPorPadrao() {
        var request = new DocumentoInternoController.DocumentoVistoriaRequest() {
            public boolean isSimulacao() { return true; }
        };
        request.setComarcaId(1L);
        request.setConteudoJson("{}");
        when(comarcaRepository.findById(1L)).thenReturn(Optional.of(new Comarca()));
        assertThrows(IllegalStateException.class,
                () -> controller.gerarDocumentoVistoria(request, autenticacao("Sistema")));
    }

    private DocumentoInternoRepository documentoRepository;
    private ComarcaRepository comarcaRepository;
    private DocumentoAssinaturaLogRepository logRepository;
    private DocumentoInternoController controller;
    private DocumentoInterno documento;

    @BeforeEach
    void setUp() {
        documentoRepository = mock(DocumentoInternoRepository.class);
        comarcaRepository = mock(ComarcaRepository.class);
        logRepository = mock(DocumentoAssinaturaLogRepository.class);
        controller = new DocumentoInternoController(
                documentoRepository,
                comarcaRepository,
                logRepository,
                mock(DocumentoPdfService.class),
                mock(AcessoOperacionalService.class),
                new com.poprc.demo.service.SimulacaoDocumentoService(false, "homologacao"));

        documento = new DocumentoInterno();
        documento.setId(1L);
        documento.setTipo("VISTORIA_INICIAL_OS");
        documento.setStatus("PENDENTE_ASSINATURA");
        documento.setConteudoJson("{}");
        documento.setCriadoPor("Sistema");
        when(documentoRepository.findById(1L)).thenReturn(Optional.of(documento));
        when(documentoRepository.save(documento)).thenReturn(documento);
        when(documentoRepository.save(org.mockito.ArgumentMatchers.any(DocumentoInterno.class)))
                .thenAnswer(invocacao -> invocacao.getArgument(0));
    }

    @Test
    void deveImpedirSubstituicaoDaAssinaturaDoMesmoPapel() {
        DocumentoInternoController.AssinaturaPapelRequest request = assinaturaPngValida();

        controller.assinarDocumentoPorPapel(1L, "TECNICO", request, autenticacao("Sistema"));

        IllegalStateException erro = assertThrows(IllegalStateException.class,
                () -> controller.assinarDocumentoPorPapel(1L, "TECNICO", request, autenticacao("Sistema")));
        assertTrue(erro.getMessage().contains("não pode ser substituída"));
        verify(logRepository, times(1)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deveRejeitarAssinaturaQueNaoSejaPngReal() {
        DocumentoInternoController.AssinaturaPapelRequest request = new DocumentoInternoController.AssinaturaPapelRequest();
        request.setNomeAssinante("Tecnico Teste");
        request.setAssinaturaBase64("data:image/png;base64,bmFvLWUtaW1hZ2Vt");

        assertThrows(IllegalArgumentException.class,
                () -> controller.assinarDocumentoPorPapel(1L, "TECNICO", request, autenticacao("Sistema")));
    }

    @Test
    void deveRejeitarCabecalhoPngComConteudoCorrompido() {
        DocumentoInternoController.AssinaturaPapelRequest request =
                new DocumentoInternoController.AssinaturaPapelRequest();
        request.setNomeAssinante("Tecnico Teste");
        request.setAssinaturaBase64("data:image/png;base64,iVBORw0KGgo=");

        assertThrows(IllegalArgumentException.class,
                () -> controller.assinarDocumentoPorPapel(1L, "TECNICO", request, autenticacao("Sistema")));
    }

    @Test
    void deveConfirmarIntegridadeDepoisDaAssinatura() {
        controller.assinarDocumentoPorPapel(1L, "TECNICO", assinaturaPngValida(), autenticacao("Sistema"));

        ResponseEntity<Map<String, Object>> response = controller.verificarIntegridade(1L, autenticacao("Sistema"));

        assertEquals(Boolean.TRUE, response.getBody().get("integro"));
        assertEquals(Boolean.TRUE, response.getBody().get("verificavel"));
        assertEquals("INTEGRO", response.getBody().get("situacao"));
    }

    @Test
    void deveIdentificarDocumentoLegadoSemHashComoNaoVerificavel() {
        documento.setHashRegistro(null);

        ResponseEntity<Map<String, Object>> response = controller.verificarIntegridade(1L, autenticacao("Sistema"));

        assertEquals(Boolean.FALSE, response.getBody().get("integro"));
        assertEquals(Boolean.FALSE, response.getBody().get("verificavel"));
        assertEquals("SEM_HASH_LEGADO", response.getBody().get("situacao"));
    }

    @Test
    void deveAtualizarConteudoEnquantoDocumentoEstiverPendente() {
        DocumentoInternoController.DocumentoVistoriaRequest request = new DocumentoInternoController.DocumentoVistoriaRequest();
        request.setConteudoJson("{\"descricaoServicos\":\"Passagem de cabos\"}");
        request.setRecebidoPor("Responsavel Local");

        ResponseEntity<DocumentoInterno> response = controller.atualizarConteudoDocumento(
                1L, request, autenticacao("Sistema"));

        assertEquals(request.getConteudoJson(), response.getBody().getConteudoJson());
        assertEquals("Responsavel Local", response.getBody().getRecebidoPor());
        verify(documentoRepository).save(documento);
    }

    @Test
    void deveImpedirAlteracaoDoConteudoDepoisDeAssinado() {
        documento.setAssinaturaTecnicoBase64("data:image/png;base64,iVBORw0KGgo=");
        DocumentoInternoController.DocumentoVistoriaRequest request = new DocumentoInternoController.DocumentoVistoriaRequest();
        request.setConteudoJson("{}");

        IllegalStateException erro = assertThrows(IllegalStateException.class,
                () -> controller.atualizarConteudoDocumento(1L, request, autenticacao("Sistema")));

        assertTrue(erro.getMessage().contains("não pode ser alterado"));
    }

    @Test
    void deveGerarDocumentosInicialEFinalComoVersoesIndependentes() {
        Comarca comarca = new Comarca();
        comarca.setId(10L);
        comarca.setNomeComarca("Comarca Teste");
        when(comarcaRepository.findById(10L)).thenReturn(Optional.of(comarca));

        DocumentoInterno inicial = controller.gerarDocumentoVistoria(
                documentoRequest(10L, "VISTORIA_INICIAL_OS"), autenticacao("Gestor Teste")).getBody();
        DocumentoInterno finalizacao = controller.gerarDocumentoVistoria(
                documentoRequest(10L, "ENCERRAMENTO_OS"), autenticacao("Gestor Teste")).getBody();

        assertEquals("VISTORIA_INICIAL_OS", inicial.getTipo());
        assertEquals("ENCERRAMENTO_OS", finalizacao.getTipo());
        assertEquals("PENDENTE_ASSINATURA", inicial.getStatus());
        assertEquals("PENDENTE_ASSINATURA", finalizacao.getStatus());
        assertEquals("Gestor Teste", inicial.getCriadoPor());
        verify(documentoRepository, times(2))
                .save(org.mockito.ArgumentMatchers.any(DocumentoInterno.class));
    }

    @Test
    void deveInvalidarDocumentoEPreservarOrigemNaNovaVersao() {
        documento.setStatus("REGISTRADO");
        documento.setAssinaturaTecnicoBase64("data:image/png;base64,iVBORw0KGgo=");
        DocumentoInternoController.InvalidacaoDocumentoRequest request =
                new DocumentoInternoController.InvalidacaoDocumentoRequest();
        request.setMotivo("Descrição técnica incorreta");

        DocumentoInterno novaVersao = controller
                .invalidarECriarNovaVersao(1L, request, autenticacao("Sistema"))
                .getBody();

        assertEquals("INVALIDADO", documento.getStatus());
        assertEquals("Descrição técnica incorreta", documento.getMotivoInvalidacao());
        assertEquals("PENDENTE_ASSINATURA", novaVersao.getStatus());
        assertEquals(documento, novaVersao.getDocumentoOrigem());
        assertEquals(documento.getConteudoJson(), novaVersao.getConteudoJson());
        assertEquals(null, novaVersao.getAssinaturaTecnicoBase64());
    }

    @Test
    void deveImpedirInvalidacaoDeDocumentoDeObraConcluida() {
        Comarca comarca = new Comarca();
        comarca.setSituacao("CONCLUIDA");
        documento.setComarca(comarca);
        DocumentoInternoController.InvalidacaoDocumentoRequest request =
                new DocumentoInternoController.InvalidacaoDocumentoRequest();
        request.setMotivo("Tentativa tardia");

        IllegalStateException erro = assertThrows(IllegalStateException.class,
                () -> controller.invalidarECriarNovaVersao(1L, request, autenticacao("Sistema")));

        assertTrue(erro.getMessage().contains("obra está concluída"));
    }

    @Test
    void deveRecusarDocumentoSemIdentidadeAutenticada() {
        Comarca comarca = new Comarca();
        comarca.setId(10L);
        when(comarcaRepository.findById(10L)).thenReturn(Optional.of(comarca));

        assertThrows(AccessDeniedException.class,
                () -> controller.gerarDocumentoVistoria(
                        documentoRequest(10L, "VISTORIA_INICIAL_OS"), null));
    }

    private DocumentoInternoController.AssinaturaPapelRequest assinaturaPngValida() {
        DocumentoInternoController.AssinaturaPapelRequest request = new DocumentoInternoController.AssinaturaPapelRequest();
        request.setNomeAssinante("Tecnico Teste");
        request.setAssinaturaBase64(gerarAssinaturaPng());
        return request;
    }

    private String gerarAssinaturaPng() {
        try {
            BufferedImage imagem = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(imagem, "png", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception ex) {
            throw new IllegalStateException("Não foi possível preparar a assinatura do teste.", ex);
        }
    }

    private DocumentoInternoController.DocumentoVistoriaRequest documentoRequest(Long comarcaId, String tipo) {
        DocumentoInternoController.DocumentoVistoriaRequest request =
                new DocumentoInternoController.DocumentoVistoriaRequest();
        request.setComarcaId(comarcaId);
        request.setTipo(tipo);
        request.setConteudoJson("{}");
        request.setRecebidoPor("Responsável Local");
        return request;
    }

    private Authentication autenticacao(String nome) {
        UsuarioAutenticado usuario = new UsuarioAutenticado(
                1L, nome, null, "ADMIN", "CPF_SENHA", false);
        return new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities());
    }
}
