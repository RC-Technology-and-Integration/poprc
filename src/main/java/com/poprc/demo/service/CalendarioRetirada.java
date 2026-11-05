package com.poprc.demo.service;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class CalendarioRetirada {
 public static final ZoneId ZONA=ZoneId.of("America/Sao_Paulo");
 private final Set<LocalDate> feriados;
 public CalendarioRetirada(@Value("${app.retiradas-v2.feriados:}") String configurados) {
  feriados=new TreeSet<>(); for(String data:configurados.split(",")) if(!data.isBlank()) feriados.add(LocalDate.parse(data.trim()));
 }
 public Instant prazo(Instant evento) {
  LocalDate d=evento.atZone(ZONA).toLocalDate();int restantes=3;
  while(restantes>0){d=d.plusDays(1);if(d.getDayOfWeek()!=DayOfWeek.SATURDAY&&d.getDayOfWeek()!=DayOfWeek.SUNDAY&&!feriados.contains(d))restantes--;}
  return d.plusDays(1).atStartOfDay(ZONA).toInstant().minusNanos(1000);
 }
 public Map<String,Object> descricao(){return Map.of("fuso",ZONA.toString(),"diasUteis",3,"feriados",feriados.stream().map(Object::toString).toList(),"aviso",feriados.isEmpty()?"Calendário considera apenas segunda a sexta; feriados operacionais não configurados.":"Feriados conforme configuração explícita do backend; não equivale à aprovação de calendário operacional.");}
}
