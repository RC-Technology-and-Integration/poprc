package com.poprc.demo.controller;

import com.poprc.demo.exception.ArquivoInvalidoException;
import com.poprc.demo.exception.SaldoInsuficienteException;
import com.poprc.demo.dto.ArquivamentoRequest;
import com.poprc.demo.model.Comarca;
import com.poprc.demo.model.MaterialItem;
import com.poprc.demo.service.ComarcaService;
import com.poprc.demo.service.AcessoOperacionalService;
import com.poprc.demo.service.ArquivamentoService;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.security.UsuarioAutenticado;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/comarcas")
@RequiredArgsConstructor
public class ComarcaController {

    private final ComarcaService comarcaService;
    private final ComarcaRepository comarcaRepository;
    private final ArquivamentoService arquivamentoService;
    private final AcessoOperacionalService acessoOperacionalService;

    @GetMapping
    public ResponseEntity<List<Comarca>> listarTodas(
            @RequestParam(defaultValue = "false") boolean incluirArquivados,
            Authentication authentication) {
        List<Comarca> comarcas = comarcaRepository.findAll().stream()
                .filter(item -> incluirArquivados || !Boolean.TRUE.equals(item.getArquivado()))
                .toList();
        return ResponseEntity.ok(acessoOperacionalService.filtrarComarcasPermitidas(
                comarcas, authentication));
    }

    @PatchMapping("/{id}/arquivar")
    public ResponseEntity<Comarca> arquivar(@PathVariable Long id, @RequestBody ArquivamentoRequest request) {
        return ResponseEntity.ok(arquivamentoService.arquivarComarca(id, request.getUsuario(), request.getMotivo()));
    }

    @PatchMapping("/{id}/restaurar")
    public ResponseEntity<Comarca> restaurar(@PathVariable Long id) {
        return ResponseEntity.ok(arquivamentoService.restaurarComarca(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Comarca> obterPorId(
            @PathVariable Long id,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoComarca(id, authentication);
        Optional<Comarca> comarca = comarcaService.obterPorId(id);
        return comarca.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/progresso")
    public ResponseEntity<Comarca> atualizarProgresso(
            @PathVariable Long id,
            @RequestBody ProgressoRequest request,
            Authentication authentication) {
        if (comarcaService.obterPorId(id).isEmpty()) return ResponseEntity.notFound().build();
        acessoOperacionalService.garantirAcessoComarca(id, authentication);
        Comarca comarca = comarcaService.atualizarProgresso(id, request.getPercentualConcluido(),
                request.getSituacao());
        if (comarca != null) {
            return ResponseEntity.ok(comarca);
        }
        return ResponseEntity.notFound().build();
    }

    @PatchMapping("/{id}/pendencias")
    public ResponseEntity<Comarca> atualizarPendencias(
            @PathVariable Long id,
            @RequestBody PendenciasRequest request,
            Authentication authentication) {
        if (comarcaService.obterPorId(id).isEmpty()) return ResponseEntity.notFound().build();
        acessoOperacionalService.garantirAcessoComarca(id, authentication);
        Comarca comarca = comarcaService.atualizarPendencias(id, request.getPendencias());
        if (comarca != null) {
            return ResponseEntity.ok(comarca);
        }
        return ResponseEntity.notFound().build();
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Comarca> atualizar(
            @PathVariable Long id,
            @RequestBody AtualizacaoComarcaRequest request,
            Authentication authentication) {
        if (comarcaService.obterPorId(id).isEmpty()) return ResponseEntity.notFound().build();
        acessoOperacionalService.garantirAcessoComarca(id, authentication);
        Comarca comarca = comarcaService.atualizarComarca(id, request.getPercentualConcluido(),
                request.getPendencias());
        if (comarca != null) {
            return ResponseEntity.ok(comarca);
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/vistoria/foto")
    public ResponseEntity<Comarca> salvarFotoVistoria(
            @PathVariable Long id,
            @RequestParam("foto") MultipartFile foto,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoComarca(id, authentication);
        Comarca comarca = comarcaService.salvarFotoVistoria(id, foto);
        return ResponseEntity.ok(comarca);
    }

    @GetMapping("/{id}/vistoria/foto")
    public ResponseEntity<byte[]> abrirFotoVistoria(
            @PathVariable Long id,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoComarca(id, authentication);
        return respostaArquivo(comarcaService.carregarFotoVistoria(id));
    }

    @PatchMapping("/{id}/vistoria/assinatura")
    public ResponseEntity<Comarca> salvarAssinaturaVistoria(
            @PathVariable Long id,
            @RequestBody AssinaturaVistoriaRequest request,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoComarca(id, authentication);
        Comarca comarca = comarcaService.salvarAssinaturaVistoria(id, request.getAssinaturaBase64());
        return ResponseEntity.ok(comarca);
    }

    @PostMapping("/{id}/virada-rede/prova")
    public ResponseEntity<Comarca> salvarProvaViradaRede(
            @PathVariable Long id,
            @RequestParam("foto") MultipartFile foto,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoComarca(id, authentication);
        return ResponseEntity.ok(comarcaService.salvarProvaViradaRede(id, foto));
    }

    @GetMapping("/{id}/virada-rede/prova")
    public ResponseEntity<byte[]> abrirProvaViradaRede(
            @PathVariable Long id,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoComarca(id, authentication);
        return respostaArquivo(comarcaService.carregarProvaViradaRede(id));
    }

    @DeleteMapping("/{id}/vistoria/foto")
    public ResponseEntity<Comarca> removerFotoVistoria(
            @PathVariable Long id,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoComarca(id, authentication);
        return ResponseEntity.ok(comarcaService.removerFotoVistoria(id));
    }

    @DeleteMapping("/{id}/vistoria/assinatura")
    public ResponseEntity<Comarca> removerAssinaturaVistoria(
            @PathVariable Long id,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoComarca(id, authentication);
        return ResponseEntity.ok(comarcaService.removerAssinaturaVistoria(id));
    }

    @DeleteMapping("/{id}/virada-rede/prova")
    public ResponseEntity<Comarca> removerProvaViradaRede(
            @PathVariable Long id,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoComarca(id, authentication);
        return ResponseEntity.ok(comarcaService.removerProvaViradaRede(id));
    }

    @PatchMapping("/{id}/avancar-etapa")
    public ResponseEntity<Comarca> avancarEtapa(
            @PathVariable Long id,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoComarca(id, authentication);
        Comarca comarca = comarcaService.avancarParaInfraestrutura(id);
        return ResponseEntity.ok(comarca);
    }

    @PatchMapping("/{id}/virada-rede")
    public ResponseEntity<Comarca> salvarViradaRede(
            @PathVariable Long id,
            @RequestBody ViradaRedeRequest request,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoComarca(id, authentication);
        return ResponseEntity.ok(comarcaService.salvarViradaRede(
                id,
                request.getProvasFuncionamento(),
                request.getChecklist(),
                request.getConcluida()));
    }

    @GetMapping("/{id}/auditoria")
    public ResponseEntity<Map<String, Object>> buscarAuditoria(@PathVariable Long id) {
        return ResponseEntity.ok(comarcaService.buscarAuditoriaPorComarca(id));
    }

    @GetMapping("/{id}/rastreabilidade-estoque")
    public ResponseEntity<Map<String, Object>> buscarRastreabilidadeEstoque(@PathVariable Long id) {
        return ResponseEntity.ok(comarcaService.buscarRastreabilidadePorComarca(id));
    }

    @GetMapping("/os/{numeroOs}/auditoria")
    public ResponseEntity<Map<String, Object>> buscarAuditoriaPorOs(@PathVariable String numeroOs) {
        return ResponseEntity.ok(comarcaService.buscarAuditoriaPorNumeroOs(numeroOs));
    }

    @PutMapping("/materiais/{materialId}/auditoria")
    public ResponseEntity<Map<String, Object>> atualizarQuantidadeAuditada(
            @PathVariable Long materialId,
            @RequestParam BigDecimal quantidadeAuditada) {
        return ResponseEntity.ok(comarcaService.atualizarQuantidadeAuditada(materialId, quantidadeAuditada));
    }

    @PatchMapping("/{id}/as-built/homologar")
    public ResponseEntity<Map<String, Object>> homologarAsBuilt(
            @PathVariable Long id, @RequestBody(required = false) HomologarAsBuiltRequest request,
            Authentication authentication) {
        String responsavel = authentication == null ? "Auditoria de Retirada/Devolução" : authentication.getName();
        if (authentication != null && authentication.getPrincipal() instanceof UsuarioAutenticado usuario) {
            responsavel = usuario.getNome();
        }
        return ResponseEntity.ok(comarcaService.homologarAsBuilt(
                id, request == null ? null : request.getJustificativa(), responsavel));
    }

    @PatchMapping("/{id}/concluir")
    public ResponseEntity<ComarcaService.EncerramentoObraResultado> concluirObra(
            @PathVariable Long id,
            @RequestBody ConcluirObraRequest request) {
        return ResponseEntity.ok(comarcaService.concluirObra(id, request.getConcluidaPor()));
    }

    @GetMapping("/{id}/encerramento")
    public ResponseEntity<ComarcaService.EncerramentoObraResultado> buscarEncerramento(
            @PathVariable Long id) {
        return ResponseEntity.ok(comarcaService.buscarEncerramento(id));
    }

    @PatchMapping("/{id}/as-built/reabrir")
    public ResponseEntity<Map<String, Object>> reabrirAsBuilt(@PathVariable Long id) {
        return ResponseEntity.ok(comarcaService.reabrirAsBuilt(id));
    }

    @GetMapping("/{id}/materiais-previstos")
    public ResponseEntity<List<MaterialItem>> listarMateriaisPrevistos(@PathVariable Long id) {
        return ResponseEntity.ok(comarcaService.listarMateriaisPrevistos(id));
    }

    @PostMapping("/{id}/materiais-previstos")
    public ResponseEntity<Comarca> adicionarMaterialPrevisto(
            @PathVariable Long id,
            @RequestBody MaterialPrevistoRequest request) {
        return ResponseEntity.ok(comarcaService.adicionarMaterialPrevisto(
                id,
                request.getMaterialId(),
                request.getNomeMaterial(),
                request.getQuantidadePrevista()));
    }

    @PostMapping("/{id}/itens-adicionais")
    public ResponseEntity<Comarca> adicionarItemAdicional(
            @PathVariable Long id,
            @RequestBody MaterialPrevistoRequest request) {
        return ResponseEntity.ok(comarcaService.adicionarItemAdicional(
                id,
                request.getMaterialId(),
                request.getNomeMaterial(),
                request.getQuantidadePrevista()));
    }

    @PatchMapping("/{id}/materiais-faltantes")
    public ResponseEntity<Comarca> atualizarMateriaisFaltantes(
            @PathVariable Long id,
            @RequestBody MateriaisFaltantesRequest request,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoComarca(id, authentication);
        return ResponseEntity.ok(comarcaService.atualizarMateriaisFaltantes(
                id,
                request.getFaltouMaterial(),
                request.getMaterialItemIds(),
                request.getDescricao()));
    }

    @PatchMapping("/materiais-previstos/{materialId}/timeline")
    public ResponseEntity<Comarca> atualizarTimelineMaterial(
            @PathVariable Long materialId,
            @RequestBody TimelineMaterialRequest request,
            Authentication authentication) {
        acessoOperacionalService.garantirAcessoMaterialPrevisto(materialId, authentication);
        return ResponseEntity.ok(comarcaService.atualizarTimelineMaterial(
                materialId,
                request.getDataHoraSolicitacao(),
                request.getDataHoraRetirada(),
                request.getDataHoraUso()));
    }

    @PutMapping("/materiais-previstos/{materialId}")
    public ResponseEntity<Comarca> atualizarMaterialPrevisto(
            @PathVariable Long materialId,
            @RequestBody MaterialPrevistoRequest request) {
        return ResponseEntity.ok(comarcaService.atualizarMaterialPrevisto(
                materialId,
                request.getMaterialId(),
                request.getNomeMaterial(),
                request.getQuantidadePrevista()));
    }

    @DeleteMapping("/materiais-previstos/{materialId}")
    public ResponseEntity<Comarca> removerMaterialPrevisto(@PathVariable Long materialId) {
        return ResponseEntity.ok(comarcaService.removerMaterialPrevisto(materialId));
    }

    public static class AtualizacaoComarcaRequest {
        private java.math.BigDecimal percentualConcluido;
        private String pendencias;

        public java.math.BigDecimal getPercentualConcluido() {
            return percentualConcluido;
        }

        public void setPercentualConcluido(java.math.BigDecimal percentualConcluido) {
            this.percentualConcluido = percentualConcluido;
        }

        public String getPendencias() {
            return pendencias;
        }

        public void setPendencias(String pendencias) {
            this.pendencias = pendencias;
        }
    }

    public static class ProgressoRequest {
        private java.math.BigDecimal percentualConcluido;
        private String situacao;

        public java.math.BigDecimal getPercentualConcluido() {
            return percentualConcluido;
        }

        public void setPercentualConcluido(java.math.BigDecimal percentualConcluido) {
            this.percentualConcluido = percentualConcluido;
        }

        public String getSituacao() {
            return situacao;
        }

        public void setSituacao(String situacao) {
            this.situacao = situacao;
        }
    }

    public static class PendenciasRequest {
        private String pendencias;

        public String getPendencias() {
            return pendencias;
        }

        public void setPendencias(String pendencias) {
            this.pendencias = pendencias;
        }
    }

    public static class ConcluirObraRequest {
        private String concluidaPor;

        public String getConcluidaPor() {
            return concluidaPor;
        }

        public void setConcluidaPor(String concluidaPor) {
            this.concluidaPor = concluidaPor;
        }
    }

    public static class AssinaturaVistoriaRequest {
        private String assinaturaBase64;

        public String getAssinaturaBase64() {
            return assinaturaBase64;
        }

        public void setAssinaturaBase64(String assinaturaBase64) {
            this.assinaturaBase64 = assinaturaBase64;
        }
    }

    public static class MaterialPrevistoRequest {
        private Long materialId;
        private String nomeMaterial;
        private BigDecimal quantidadePrevista;

        public Long getMaterialId() {
            return materialId;
        }

        public void setMaterialId(Long materialId) {
            this.materialId = materialId;
        }

        public String getNomeMaterial() {
            return nomeMaterial;
        }

        public void setNomeMaterial(String nomeMaterial) {
            this.nomeMaterial = nomeMaterial;
        }

        public BigDecimal getQuantidadePrevista() {
            return quantidadePrevista;
        }

        public void setQuantidadePrevista(BigDecimal quantidadePrevista) {
            this.quantidadePrevista = quantidadePrevista;
        }
    }

    public static class ViradaRedeRequest {
        private String provasFuncionamento;
        private String checklist;
        private Boolean concluida;

        public String getProvasFuncionamento() {
            return provasFuncionamento;
        }

        public void setProvasFuncionamento(String provasFuncionamento) {
            this.provasFuncionamento = provasFuncionamento;
        }

        public String getChecklist() {
            return checklist;
        }

        public void setChecklist(String checklist) {
            this.checklist = checklist;
        }

        public Boolean getConcluida() {
            return concluida;
        }

        public void setConcluida(Boolean concluida) {
            this.concluida = concluida;
        }
    }

    public static class HomologarAsBuiltRequest {
        private String justificativa;

        public String getJustificativa() {
            return justificativa;
        }

        public void setJustificativa(String justificativa) {
            this.justificativa = justificativa;
        }
    }

    public static class MateriaisFaltantesRequest {
        private Boolean faltouMaterial;
        private List<Long> materialItemIds;
        private String descricao;

        public Boolean getFaltouMaterial() {
            return faltouMaterial;
        }

        public void setFaltouMaterial(Boolean faltouMaterial) {
            this.faltouMaterial = faltouMaterial;
        }

        public List<Long> getMaterialItemIds() {
            return materialItemIds;
        }

        public void setMaterialItemIds(List<Long> materialItemIds) {
            this.materialItemIds = materialItemIds;
        }

        public String getDescricao() {
            return descricao;
        }

        public void setDescricao(String descricao) {
            this.descricao = descricao;
        }
    }

    public static class TimelineMaterialRequest {
        private LocalDateTime dataHoraSolicitacao;
        private LocalDateTime dataHoraRetirada;
        private LocalDateTime dataHoraUso;

        public LocalDateTime getDataHoraSolicitacao() {
            return dataHoraSolicitacao;
        }

        public void setDataHoraSolicitacao(LocalDateTime dataHoraSolicitacao) {
            this.dataHoraSolicitacao = dataHoraSolicitacao;
        }

        public LocalDateTime getDataHoraRetirada() {
            return dataHoraRetirada;
        }

        public void setDataHoraRetirada(LocalDateTime dataHoraRetirada) {
            this.dataHoraRetirada = dataHoraRetirada;
        }

        public LocalDateTime getDataHoraUso() {
            return dataHoraUso;
        }

        public void setDataHoraUso(LocalDateTime dataHoraUso) {
            this.dataHoraUso = dataHoraUso;
        }
    }

    private ResponseEntity<byte[]> respostaArquivo(ComarcaService.ArquivoObra arquivo) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(arquivo.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + arquivo.nomeArquivo() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .contentLength(arquivo.conteudo().length)
                .body(arquivo.conteudo());
    }

    @ExceptionHandler({ ArquivoInvalidoException.class, IllegalArgumentException.class,
            IllegalStateException.class, SaldoInsuficienteException.class })
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException ex) {
        Map<String, String> response = new HashMap<>();
        response.put("erro", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }
}
