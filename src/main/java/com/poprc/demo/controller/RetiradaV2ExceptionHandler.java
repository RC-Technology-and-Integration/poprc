package com.poprc.demo.controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestControllerAdvice(assignableTypes=RetiradaV2Controller.class)
public class RetiradaV2ExceptionHandler {
 @ExceptionHandler(ResponseStatusException.class)
 public ResponseEntity<Map<String,Object>> tratar(ResponseStatusException e){
  return ResponseEntity.status(e.getStatusCode()).body(Map.of("status",e.getStatusCode().value(),"erro",e.getReason()==null?"Operação indisponível.":e.getReason()));
 }
}
