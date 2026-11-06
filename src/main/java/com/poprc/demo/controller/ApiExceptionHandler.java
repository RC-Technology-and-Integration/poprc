package com.poprc.demo.controller;

import com.poprc.demo.service.EsperaEstoqueExcedidaException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(EsperaEstoqueExcedidaException.class)
    public ResponseEntity<Map<String, Object>> tratarEstoqueOcupado(EsperaEstoqueExcedidaException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", 503, "codigo", "ESTOQUE_OCUPADO", "erro", exception.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> tratarCredenciaisInvalidas(BadCredentialsException exception) {
        return resposta(HttpStatus.UNAUTHORIZED, exception.getMessage());
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<Map<String, Object>> tratarAcessoBloqueado(LockedException exception) {
        return resposta(HttpStatus.LOCKED, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> tratarArgumentoInvalido(IllegalArgumentException exception) {
        return resposta(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> tratarEstadoInvalido(IllegalStateException exception) {
        return resposta(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> tratarCorpoInvalido() {
        return resposta(HttpStatus.BAD_REQUEST, "Os dados enviados estão incompletos ou possuem formato inválido.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> tratarConflitoDeDados(
            DataIntegrityViolationException exception) {
        String detalhes = mensagensDasCausas(exception).toLowerCase();
        if (detalhes.contains("materiais_tipo_controle_check")) {
            return resposta(HttpStatus.CONFLICT,
                    "O banco ainda não aceita o tipo de controle deste material. "
                            + "Reinicie o backend para aplicar as migrações pendentes e tente novamente.");
        }
        return resposta(HttpStatus.CONFLICT,
                "A operação conflita com um registro existente ou com um vínculo obrigatório.");
    }

    private String mensagensDasCausas(Throwable exception) {
        StringBuilder mensagens = new StringBuilder();
        Throwable atual = exception;
        while (atual != null) {
            if (atual.getMessage() != null) {
                mensagens.append(' ').append(atual.getMessage());
            }
            atual = atual.getCause();
        }
        return mensagens.toString();
    }

    private ResponseEntity<Map<String, Object>> resposta(HttpStatus status, String mensagem) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("status", status.value());
        corpo.put("erro", mensagem == null || mensagem.isBlank() ? "Não foi possível concluir a operação." : mensagem);
        corpo.put("timestamp", LocalDateTime.now());
        return ResponseEntity.status(status).body(corpo);
    }
}
