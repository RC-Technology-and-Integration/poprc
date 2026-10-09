package com.poprc.demo.controller;

import com.poprc.demo.dto.FaturamentoPainelDTO;
import com.poprc.demo.dto.FaturamentoMedicaoRequest;
import com.poprc.demo.model.Faturamento;
import com.poprc.demo.service.FaturamentoService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/faturamentos")
@RequiredArgsConstructor
public class FaturamentoController {

    private final FaturamentoService faturamentoService;

    @GetMapping
    public ResponseEntity<List<Faturamento>> listarTodos() {
        return ResponseEntity.ok(faturamentoService.listarTodos());
    }

    @GetMapping("/painel")
    public ResponseEntity<List<FaturamentoPainelDTO>> listarPainel() {
        return ResponseEntity.ok(faturamentoService.listarPainel());
    }

    @PostMapping
    public ResponseEntity<Faturamento> criarFaturamento(@RequestBody FaturamentoMedicaoRequest dados) {
        Long contratoId = dados.contratoId();
        Long projetoId = dados.projetoId();
        Long ordemServicoId = dados.ordemServicoId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(faturamentoService.registrarMedicao(
                        dados.dadosEditaveis(), contratoId, projetoId, ordemServicoId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Faturamento> atualizarFaturamento(
            @PathVariable Long id,
            @RequestBody FaturamentoMedicaoRequest dados) {
        return ResponseEntity.ok(faturamentoService.atualizarMedicao(
                id, dados.dadosEditaveis(), dados.contratoId(), dados.projetoId(), dados.ordemServicoId()));
    }

    @PutMapping("/{id}/emitir-nota")
    public ResponseEntity<Faturamento> emitirNotaFiscal(
            @PathVariable Long id,
            @RequestParam String numeroNotaFiscal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataEmissao,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataVencimento) {
        return ResponseEntity.ok(
                faturamentoService.emitirNotaFiscal(id, numeroNotaFiscal, dataEmissao, dataVencimento));
    }

    @PutMapping("/{id}/baixar-pagamento")
    public ResponseEntity<Faturamento> baixarPagamento(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataPagamento) {
        return ResponseEntity.ok(faturamentoService.darBaixaPagamento(id, dataPagamento));
    }

    @ExceptionHandler({ IllegalArgumentException.class, IllegalStateException.class })
    public ResponseEntity<Map<String, String>> tratarErroDeRegra(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("erro", ex.getMessage()));
    }

}
