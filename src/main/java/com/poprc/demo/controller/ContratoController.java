package com.poprc.demo.controller;

import com.poprc.demo.dto.ArquivamentoRequest;
import com.poprc.demo.model.Contrato;
import com.poprc.demo.repository.ContratoRepository;
import com.poprc.demo.service.ArquivamentoService;
import com.poprc.demo.specification.ContratoSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/contratos")
@RequiredArgsConstructor
public class ContratoController {

    private final ContratoRepository contratoRepository;
    private final ArquivamentoService arquivamentoService;

    public record OpcaoContratoEstoque(Long id, String cliente, String contrato, Boolean arquivado) {
    }

    @GetMapping("/opcoes-estoque")
    public ResponseEntity<List<OpcaoContratoEstoque>> listarOpcoesEstoque() {
        List<OpcaoContratoEstoque> opcoes = contratoRepository.findAll().stream()
                .filter(item -> !Boolean.TRUE.equals(item.getArquivado()))
                .map(item -> new OpcaoContratoEstoque(
                        item.getId(), item.getCliente(), item.getContrato(), item.getArquivado()))
                .toList();
        return ResponseEntity.ok(opcoes);
    }

    /**
     * POST: Salvar novo contrato recebendo JSON do React
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> salvarContrato(@RequestBody Contrato novoContrato) {
        Map<String, Object> response = new HashMap<>();

        if (novoContrato.getCliente() == null || novoContrato.getCliente().isBlank()) {
            response.put("erro", "O campo 'cliente' é obrigatório.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
        if (novoContrato.getContrato() == null || novoContrato.getContrato().isBlank()) {
            response.put("erro", "O campo 'contrato' (número) é obrigatório.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
        if (novoContrato.getTipoContratante() == null) {
            response.put("erro", "O tipo do contratante é obrigatório.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        if (novoContrato.getStatus() == null || novoContrato.getStatus().isBlank()) {
            novoContrato.setStatus("ATIVO");
        }
        if (novoContrato.getArquivado() == null) {
            novoContrato.setArquivado(false);
        }

        Contrato salvo = contratoRepository.save(novoContrato);
        response.put("contrato", salvo);
        response.put("mensagem", "Contrato salvo com sucesso");

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /{id}: Buscar contrato específico
     */
    @GetMapping("/{id}")
    public ResponseEntity<Contrato> buscarPorId(@PathVariable Long id) {
        return contratoRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * PUT /{id}: Atualizar contrato existente
     */
    @PutMapping("/{id}")
    public ResponseEntity<Contrato> atualizarContrato(
            @PathVariable Long id,
            @RequestBody Contrato dadosAtualizados) {

        return contratoRepository.findById(id)
                .map(contrato -> {
                    contrato.setCliente(dadosAtualizados.getCliente());
                    contrato.setContrato(dadosAtualizados.getContrato());
                    if (dadosAtualizados.getTipoContratante() == null) {
                        throw new IllegalArgumentException("O tipo do contratante é obrigatório.");
                    }
                    contrato.setTipoContratante(dadosAtualizados.getTipoContratante());
                    contrato.setVigenciaInicio(dadosAtualizados.getVigenciaInicio());
                    contrato.setVigenciaFim(dadosAtualizados.getVigenciaFim());
                    contrato.setValorGlobal(dadosAtualizados.getValorGlobal());
                    contrato.setEscopo(dadosAtualizados.getEscopo());
                    contrato.setStatus(dadosAtualizados.getStatus());
                    return ResponseEntity.ok(contratoRepository.save(contrato));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET: Listar contratos com suporte a filtros dinâmicos cruzados (Server-side)
     * 💥
     */
    /**
     * GET: Listar contratos com a arquitetura completa de filtros ativos
     * (Server-side)
     */
    @GetMapping
    public ResponseEntity<List<Contrato>> listarTodos(
            @RequestParam(required = false) List<String> cliente, // 💥 Virou lista para multi-select
            @RequestParam(required = false) String contrato,
            @RequestParam(required = false) List<String> status, // 💥 Virou lista para multi-select
            @RequestParam(required = false) String recorrencia, // 💥 Novo
            @RequestParam(required = false) String segmento, // 💥 Novo
            @RequestParam(required = false) String gestor, // 💥 Novo
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(required = false) BigDecimal valorMin,
            @RequestParam(required = false) BigDecimal valorMax,
            @RequestParam(defaultValue = "false") boolean incluirArquivados) {

        Specification<Contrato> spec = ContratoSpecification.filtrar(
                cliente, contrato, status, recorrencia, segmento, gestor, dataInicio, dataFim, valorMin, valorMax);
        List<Contrato> contratos = contratoRepository.findAll(spec).stream()
                .filter(item -> incluirArquivados || !Boolean.TRUE.equals(item.getArquivado()))
                .toList();
        return ResponseEntity.ok(contratos);
    }

    @PatchMapping("/{id}/arquivar")
    public ResponseEntity<Contrato> arquivar(@PathVariable Long id, @RequestBody ArquivamentoRequest request) {
        return ResponseEntity.ok(arquivamentoService.arquivarContrato(id, request.getUsuario(), request.getMotivo()));
    }

    @PatchMapping("/{id}/restaurar")
    public ResponseEntity<Contrato> restaurar(@PathVariable Long id) {
        return ResponseEntity.ok(arquivamentoService.restaurarContrato(id));
    }

    @ExceptionHandler({ IllegalArgumentException.class, IllegalStateException.class })
    public ResponseEntity<Map<String, String>> handleArquivamentoInvalido(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("erro", ex.getMessage()));
    }

    /**
     * GET /ativos: Contratos dentro da vigência atual
     */
    @GetMapping("/ativos")
    public ResponseEntity<List<Contrato>> listarAtivos() {
        return ResponseEntity.ok(contratoRepository.findContratoAtivos());
    }

    /**
     * GET /cliente?cliente=...: Buscar por nome do cliente
     */
    @GetMapping("/cliente")
    public ResponseEntity<List<Contrato>> buscarPorCliente(@RequestParam String cliente) {
        return ResponseEntity.ok(contratoRepository.findByCliente(cliente));
    }
}
