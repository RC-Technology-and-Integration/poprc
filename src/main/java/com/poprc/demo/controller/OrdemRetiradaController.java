package com.poprc.demo.controller;

import com.poprc.demo.dto.DevolverOrdemRetiradaRequest;
import com.poprc.demo.dto.ExecutarOrdemRetiradaRequest;
import com.poprc.demo.model.OrdemRetirada;
import com.poprc.demo.model.OrdemRetiradaDocumento;
import com.poprc.demo.service.OrdemRetiradaPdfService;
import com.poprc.demo.service.OrdemRetiradaService;
import com.poprc.demo.service.AcessoOperacionalService;
import com.poprc.demo.service.EvidenciaMetragemOrService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ordens-retirada")
@RequiredArgsConstructor
public class OrdemRetiradaController {

    private final OrdemRetiradaService ordemRetiradaService;
    private final OrdemRetiradaPdfService ordemRetiradaPdfService;
    private final AcessoOperacionalService acessoOperacionalService;
    private final com.poprc.demo.service.RetiradaV2Service retiradaV2Service;

    @GetMapping
    public ResponseEntity<List<OrdemRetirada>> listarTodas(Authentication authentication) {
        return ResponseEntity.ok(acessoOperacionalService.filtrarOrdensRetiradaPermitidas(
                ordemRetiradaService.listarTodas(), authentication));
    }

    @GetMapping("/comarca/{comarcaId}")
    public ResponseEntity<List<OrdemRetirada>> listarPorComarca(
            @PathVariable Long comarcaId,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoComarca(comarcaId, authentication);
        return ResponseEntity.ok(ordemRetiradaService.listarPorComarca(comarcaId));
    }

    @GetMapping("/os/{ordemServicoId}")
    public ResponseEntity<List<OrdemRetirada>> listarPorOs(
            @PathVariable Long ordemServicoId,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoOrdem(ordemServicoId, authentication);
        return ResponseEntity.ok(ordemRetiradaService.listarPorOs(ordemServicoId));
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> abrirPdf(@PathVariable Long id, Authentication authentication) {
        acessoOperacionalService.garantirAcessoOrdemRetirada(id, authentication);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=ordem-retirada-" + id + ".pdf")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(retiradaV2Service.novoRegime(id) ? retiradaV2Service.emissaoAtual(id, authentication).bytes()
                        : ordemRetiradaPdfService.obterPdfArquivado(id));
    }

    @GetMapping("/{id}/documentos")
    public ResponseEntity<List<OrdemRetiradaDocumento>> listarDocumentos(
            @PathVariable Long id,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoOrdemRetirada(id, authentication);
        return ResponseEntity.ok(ordemRetiradaPdfService.listarDocumentos(id));
    }

    @GetMapping("/{id}/documentos/{documentoId}/pdf")
    public ResponseEntity<byte[]> abrirPdfArquivado(
            @PathVariable Long id,
            @PathVariable Long documentoId,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoOrdemRetirada(id, authentication);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=ordem-retirada-" + id + "-documento-" + documentoId + ".pdf")
                .body(ordemRetiradaPdfService.obterPdfArquivado(id, documentoId));
    }

    @GetMapping("/{id}/alocacoes/{alocacaoId}/evidencia/{fase}")
    public ResponseEntity<byte[]> abrirEvidenciaMetragem(
            @PathVariable Long id,
            @PathVariable Long alocacaoId,
            @PathVariable String fase,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoOrdemRetirada(id, authentication);
        EvidenciaMetragemOrService.ArquivoEvidencia arquivo =
                ordemRetiradaService.carregarEvidenciaMetragem(id, alocacaoId, fase);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(arquivo.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + arquivo.nomeArquivo().replace("\"", "") + "\"")
                .body(arquivo.conteudo());
    }

    @PostMapping("/os/{ordemServicoId}")
    public ResponseEntity<OrdemRetirada> gerarAdicionalPorOs(@PathVariable Long ordemServicoId) {
        return ResponseEntity.ok(ordemRetiradaService.criarAdicionalParaOs(ordemServicoId, "Sistema"));
    }

    @PatchMapping("/{id}/executar")
    public ResponseEntity<OrdemRetirada> executarRetirada(
            @PathVariable Long id,
            @RequestBody ExecutarOrdemRetiradaRequest request) {
        return ResponseEntity.ok(ordemRetiradaService.executarRetirada(id, request));
    }

    @PatchMapping("/{id}/devolver")
    public ResponseEntity<OrdemRetirada> devolver(
            @PathVariable Long id,
            @RequestBody DevolverOrdemRetiradaRequest request) {
        return ResponseEntity.ok(ordemRetiradaService.devolver(id, request));
    }
}
