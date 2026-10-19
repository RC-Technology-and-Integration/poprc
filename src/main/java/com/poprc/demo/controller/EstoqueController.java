package com.poprc.demo.controller;

import com.poprc.demo.dto.ImportacaoEstoquePlanilhaRequest;
import com.poprc.demo.dto.AtualizacaoCustosPlanilhaRequest;
import com.poprc.demo.dto.ImportacaoEstoquePlanilhaDetalheDTO;
import com.poprc.demo.dto.ImportacaoEstoquePlanilhaResultadoDTO;
import com.poprc.demo.dto.NotaFiscalEstoqueDTO;
import com.poprc.demo.dto.SincronizacaoSaldosPlanilhaRequest;
import com.poprc.demo.dto.ReconciliacaoRetiradasPlanilhaDTO;
import com.poprc.demo.dto.ReconciliacaoRetiradasPlanilhaRequest;
import com.poprc.demo.dto.EdicaoRetiradaHistoricaRequest;
import com.poprc.demo.model.Material;
import com.poprc.demo.model.MovimentacaoEstoque;
import com.poprc.demo.repository.MaterialRepository;
import com.poprc.demo.repository.MovimentacaoEstoqueRepository; //  IMPORT DO REPOSITORY NOVO
import com.poprc.demo.service.EstoqueService;
import com.poprc.demo.service.UnidadeEstoqueRastreavelService;
import com.poprc.demo.model.UnidadeEstoqueRastreavel;
import com.poprc.demo.model.LocalEstoque;
import com.poprc.demo.model.SaldoMaterialLocal;
import com.poprc.demo.service.SaldoLocalService;
import com.poprc.demo.service.ImportacaoEstoquePlanilhaService;
import com.poprc.demo.service.ImportacaoNotaFiscalEstoqueService;
import com.poprc.demo.service.ReconciliacaoRetiradaPlanilhaService;
import com.poprc.demo.security.UsuarioAutenticado;
import lombok.RequiredArgsConstructor;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/estoque")
@RequiredArgsConstructor
public class EstoqueController {

    private final EstoqueService estoqueService;
    private final MaterialRepository materialRepository;
    private final MovimentacaoEstoqueRepository movimentacaoEstoqueRepository; // INJEÇÃO DIRETA
    private final UnidadeEstoqueRastreavelService unidadeRastreavelService;
    private final SaldoLocalService saldoLocalService;
    private final ImportacaoEstoquePlanilhaService importacaoPlanilhaService;
    private final ImportacaoNotaFiscalEstoqueService importacaoNotaFiscalService;
    private final ReconciliacaoRetiradaPlanilhaService reconciliacaoRetiradaPlanilhaService;

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> tratarRequisicaoInvalida(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("erro", exception.getMessage()));
    }

    @GetMapping("/materiais")
    public ResponseEntity<List<Material>> listarMateriais() {
        List<Material> materiais = materialRepository.findByAtivoTrueOrderByNomeAsc();
        return ResponseEntity.ok(materiais);
    }

    @GetMapping("/materiais/removidos")
    public ResponseEntity<List<Material>> listarMateriaisRemovidos() {
        return ResponseEntity.ok(materialRepository.findByAtivoFalseOrderByRemovidoEmDesc());
    }

    @PostMapping("/materiais")
    public ResponseEntity<Material> cadastrarNovoMaterial(@RequestBody Material material) {
        Material salvo = estoqueService.cadastrarMaterial(material);
        return ResponseEntity.status(HttpStatus.CREATED).body(salvo);
    }

    @PutMapping("/materiais/{id}")
    public ResponseEntity<Material> atualizarMaterial(@PathVariable Long id, @RequestBody Material materialAtualizado) {
        return ResponseEntity.ok(estoqueService.atualizarMaterial(id, materialAtualizado));
    }

    @DeleteMapping("/materiais/{id}")
    public ResponseEntity<Void> removerMaterial(@PathVariable Long id, Authentication authentication) {
        String usuario = authentication != null ? authentication.getName() : null;
        if (authentication != null && authentication.getPrincipal() instanceof UsuarioAutenticado autenticado) {
            usuario = autenticado.getNome();
        }
        estoqueService.removerMaterial(id, usuario);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/materiais/{id}/restaurar")
    public ResponseEntity<Material> restaurarMaterial(@PathVariable Long id, Authentication authentication) {
        String usuario = authentication != null ? authentication.getName() : null;
        if (authentication != null && authentication.getPrincipal() instanceof UsuarioAutenticado autenticado) {
            usuario = autenticado.getNome();
        }
        return ResponseEntity.ok(estoqueService.restaurarMaterial(id, usuario));
    }

    // ROTA DO HISTÓRICO CORRIGIDA: Buscando direto do banco pelo repository
    @GetMapping("/historico")
    public ResponseEntity<List<MovimentacaoEstoque>> listarHistorico() {
        List<MovimentacaoEstoque> historico = movimentacaoEstoqueRepository.findAllByOrderByDataMovimentacaoDesc();
        return ResponseEntity.ok(historico);
    }

    @PostMapping("/entrada")
    public ResponseEntity<MovimentacaoEstoque> registrarEntrada(@RequestBody EntradaRequest request) {
        MovimentacaoEstoque movimentacao = estoqueService.registrarEntrada(
                request.getMaterialId(),
                request.getQuantidade(),
                request.getMetragem(),
                request.getCustoUnitarioEntrada(),
                request.getFuncionarioId(),
                request.getLocalEstoqueId());
        return ResponseEntity.status(HttpStatus.CREATED).body(movimentacao);
    }

    @PostMapping("/saida")
    public ResponseEntity<MovimentacaoEstoque> registrarSaida(@RequestBody SaidaRequest request) {
        MovimentacaoEstoque movimentacao = estoqueService.registrarSaida(
                request.getMaterialId(),
                request.getQuantidade(),
                request.getFuncionarioId(),
                request.getComarcaId());
        return ResponseEntity.status(HttpStatus.CREATED).body(movimentacao);
    }

    @PostMapping("/ajustes")
    public ResponseEntity<MovimentacaoEstoque> registrarAjuste(@RequestBody AjusteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(estoqueService.registrarAjuste(
                request.getMaterialId(), request.getLocalEstoqueId(), request.getValor(), request.getTipo(), request.getMotivo(),
                request.getLancadoPor(), request.getAutorizadoPor()));
    }

    @PostMapping("/transferencias")
    public ResponseEntity<MovimentacaoEstoque> transferir(@RequestBody TransferenciaRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(estoqueService.transferirLocalizacao(
                request.getMaterialId(), request.getOrigemId(), request.getDestinoId(), request.getValor(), request.getMotivo(),
                request.getLancadoPor(), request.getAutorizadoPor()));
    }

    @GetMapping("/locais")
    public ResponseEntity<List<LocalEstoque>> listarLocais() {
        return ResponseEntity.ok(saldoLocalService.listarLocais());
    }

    @PostMapping("/locais")
    public ResponseEntity<LocalEstoque> cadastrarLocal(@RequestBody LocalEstoqueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(saldoLocalService.cadastrarLocal(request.getNome(), request.getEndereco()));
    }

    @GetMapping("/saldos-locais")
    public ResponseEntity<List<SaldoMaterialLocal>> listarSaldosLocais(
            @RequestParam(required = false) Long materialId) {
        return ResponseEntity.ok(saldoLocalService.listarSaldos(materialId));
    }

    @PatchMapping("/saldos-locais/{id}/estoque-minimo")
    public ResponseEntity<SaldoMaterialLocal> atualizarEstoqueMinimoLocal(
            @PathVariable Long id, @RequestBody EstoqueMinimoLocalRequest request) {
        return ResponseEntity.ok(saldoLocalService.atualizarEstoqueMinimo(id, request.estoqueMinimo()));
    }

    public record EstoqueMinimoLocalRequest(BigDecimal estoqueMinimo) {
    }

    @PostMapping("/importacoes/planilha")
    public ResponseEntity<ImportacaoEstoquePlanilhaResultadoDTO> importarPlanilha(
            @RequestBody ImportacaoEstoquePlanilhaRequest request,
            Authentication authentication) {
        String usuario = authentication != null ? authentication.getName() : null;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(importacaoPlanilhaService.importar(request, usuario));
    }

    @PostMapping("/importacoes/custos")
    public ResponseEntity<ImportacaoEstoquePlanilhaResultadoDTO> atualizarCustosPlanilha(
            @RequestBody AtualizacaoCustosPlanilhaRequest request,
            Authentication authentication) {
        String usuario = authentication != null ? authentication.getName() : null;
        if (authentication != null && authentication.getPrincipal() instanceof UsuarioAutenticado autenticado) {
            usuario = autenticado.getNome();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(importacaoPlanilhaService.atualizarCustos(request, usuario));
    }

    @PostMapping("/importacoes/saldos")
    public ResponseEntity<ImportacaoEstoquePlanilhaResultadoDTO> sincronizarSaldosPlanilha(
            @RequestBody SincronizacaoSaldosPlanilhaRequest request,
            Authentication authentication) {
        String usuario = authentication != null ? authentication.getName() : null;
        if (authentication != null && authentication.getPrincipal() instanceof UsuarioAutenticado autenticado) {
            usuario = autenticado.getNome();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(importacaoPlanilhaService.sincronizarSaldos(request, usuario));
    }

    @GetMapping("/importacoes/planilha")
    public ResponseEntity<List<ImportacaoEstoquePlanilhaDetalheDTO>> listarImportacoesPlanilha() {
        return ResponseEntity.ok(importacaoPlanilhaService.listarHistorico());
    }

    @GetMapping("/importacoes/planilha/{id}")
    public ResponseEntity<ImportacaoEstoquePlanilhaDetalheDTO> detalharImportacaoPlanilha(
            @PathVariable Long id) {
        return ResponseEntity.ok(importacaoPlanilhaService.detalhar(id));
    }

    @GetMapping("/importacoes/planilha/retiradas")
    public ResponseEntity<List<ImportacaoEstoquePlanilhaDetalheDTO.Retirada>>
            listarRetiradasImportadas() {
        return ResponseEntity.ok(importacaoPlanilhaService.listarRetiradasImportadas());
    }

    @PostMapping("/importacoes/planilha/retiradas/reconciliar")
    public ResponseEntity<ReconciliacaoRetiradasPlanilhaDTO.Resultado> reconciliarRetiradas(
            @RequestBody ReconciliacaoRetiradasPlanilhaRequest request,
            Authentication authentication) {
        String usuario = authentication != null ? authentication.getName() : null;
        if (authentication != null && authentication.getPrincipal() instanceof UsuarioAutenticado autenticado) {
            usuario = autenticado.getNome();
        }
        return ResponseEntity.ok(reconciliacaoRetiradaPlanilhaService.reconciliar(request, usuario));
    }

    @GetMapping("/importacoes/planilha/retiradas/reconciliacoes")
    public ResponseEntity<List<ReconciliacaoRetiradasPlanilhaDTO.Evento>>
            listarReconciliacoesRetiradas() {
        return ResponseEntity.ok(reconciliacaoRetiradaPlanilhaService.listarHistorico());
    }

    @PatchMapping("/importacoes/planilha/retiradas/{id}")
    public ResponseEntity<ReconciliacaoRetiradasPlanilhaDTO.Evento> editarRetiradaHistorica(
            @PathVariable Long id,
            @RequestBody EdicaoRetiradaHistoricaRequest request,
            Authentication authentication) {
        String usuario = authentication != null ? authentication.getName() : null;
        if (authentication != null && authentication.getPrincipal() instanceof UsuarioAutenticado autenticado) {
            usuario = autenticado.getNome();
        }
        return ResponseEntity.ok(
                reconciliacaoRetiradaPlanilhaService.editarHistorico(id, request, usuario));
    }

    @PostMapping("/importacoes/notas-fiscais/analisar")
    public ResponseEntity<NotaFiscalEstoqueDTO.Preview> analisarNotaFiscal(
            @RequestBody NotaFiscalEstoqueDTO.ArquivoRequest request) {
        return ResponseEntity.ok(importacaoNotaFiscalService.analisar(request));
    }

    @PostMapping("/importacoes/notas-fiscais")
    public ResponseEntity<NotaFiscalEstoqueDTO.Resultado> importarNotaFiscal(
            @RequestBody NotaFiscalEstoqueDTO.ConfirmarRequest request,
            Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UsuarioAutenticado usuario)) {
            throw new IllegalArgumentException("Usuário autenticado não identificado.");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(importacaoNotaFiscalService.confirmar(request, usuario));
    }

    @GetMapping("/importacoes/notas-fiscais")
    public ResponseEntity<List<NotaFiscalEstoqueDTO.Historico>> listarImportacoesNotasFiscais() {
        return ResponseEntity.ok(importacaoNotaFiscalService.listarHistorico());
    }

    @GetMapping("/importacoes/notas-fiscais/{id}")
    public ResponseEntity<NotaFiscalEstoqueDTO.Detalhe> detalharImportacaoNotaFiscal(
            @PathVariable Long id) {
        return ResponseEntity.ok(importacaoNotaFiscalService.detalhar(id));
    }

    @GetMapping("/importacoes/notas-fiscais/{id}/arquivo")
    public ResponseEntity<Resource> baixarArquivoNotaFiscal(@PathVariable Long id) {
        ImportacaoNotaFiscalEstoqueService.ArquivoArmazenado arquivo =
                importacaoNotaFiscalService.carregarArquivo(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(arquivo.nomeArquivo(), java.nio.charset.StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(arquivo.contentType()))
                .body(new FileSystemResource(arquivo.path()));
    }

    @GetMapping("/unidades-rastreaveis")
    public ResponseEntity<List<UnidadeEstoqueRastreavel>> listarUnidadesRastreaveis(
            @RequestParam(required = false) Long materialId) {
        return ResponseEntity.ok(unidadeRastreavelService.listar(materialId));
    }

    @PostMapping("/unidades-rastreaveis")
    public ResponseEntity<UnidadeEstoqueRastreavel> cadastrarUnidadeRastreavel(
            @RequestBody UnidadeRastreavelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(unidadeRastreavelService.cadastrar(
                request.getMaterialId(), request.getCodigo(), request.getMetragemInicial(), request.getObservacao(),
                request.getLocalEstoqueId()));
    }

    @PatchMapping("/unidades-rastreaveis/{id}/transferir")
    public ResponseEntity<UnidadeEstoqueRastreavel> transferirUnidadeRastreavel(
            @PathVariable Long id, @RequestBody TransferenciaUnidadeRequest request) {
        return ResponseEntity.ok(unidadeRastreavelService.transferir(
                id, request.getDestinoId(), request.getMotivo(), request.getLancadoPor(), request.getAutorizadoPor()));
    }

    @Data
    public static class EntradaRequest {
        private Long materialId;
        private Integer quantidade;
        private BigDecimal metragem;
        private BigDecimal custoUnitarioEntrada;
        private Long funcionarioId;
        private Long localEstoqueId;

        // Preserve the integer contract without Jackson silently truncating a decimal JSON value.
        public void setQuantidade(BigDecimal quantidade) {
            try {
                this.quantidade = quantidade == null ? null : quantidade.intValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("A quantidade de entrada por unidade precisa ser inteira.");
            }
        }
    }

    @Data
    public static class SaidaRequest {
        private Long materialId;
        private Integer quantidade;
        private Long funcionarioId;
        private Long comarcaId;
    }

    @Data
    public static class UnidadeRastreavelRequest {
        private Long materialId;
        private String codigo;
        private BigDecimal metragemInicial;
        private String observacao;
        private Long localEstoqueId;
    }

    @Data
    public static class AjusteRequest {
        private Long materialId;
        private Long localEstoqueId;
        private BigDecimal valor;
        private com.poprc.demo.model.TipoMovimentacao tipo;
        private String motivo;
        private String lancadoPor;
        private String autorizadoPor;
    }

    @Data
    public static class TransferenciaRequest {
        private Long materialId;
        private Long origemId;
        private Long destinoId;
        private BigDecimal valor;
        private String motivo;
        private String lancadoPor;
        private String autorizadoPor;
    }

    @Data
    public static class LocalEstoqueRequest {
        private String nome;
        private String endereco;
    }

    @Data
    public static class TransferenciaUnidadeRequest {
        private Long destinoId;
        private String motivo;
        private String lancadoPor;
        private String autorizadoPor;
    }
}
