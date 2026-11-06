package com.poprc.demo.service;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CalendarioRetiradaTest {
 @Test void excluiDiaDoEventoEFechaTerceiroDiaUtilNoFuso(){
  var c=new CalendarioRetirada("");
  assertThat(c.prazo(Instant.parse("2026-10-30T20:00:00Z"))).isEqualTo(Instant.parse("2026-11-05T02:59:59.999999Z"));
  assertThat(c.prazo(Instant.parse("2026-10-31T01:00:00Z"))).isEqualTo(c.prazo(Instant.parse("2026-10-30T20:00:00Z")));
 }
 @Test void feriadoSoEntraQuandoConfigurado(){
  assertThat(new CalendarioRetirada("2026-11-02").prazo(Instant.parse("2026-10-30T20:00:00Z"))).isEqualTo(Instant.parse("2026-11-06T02:59:59.999999Z"));
 }
 @Test void outroPrazoContaAPartirDaAnexacao(){
  assertThat(new CalendarioRetirada("").prazo(Instant.parse("2026-11-04T23:00:00Z"))).isEqualTo(Instant.parse("2026-11-10T02:59:59.999999Z"));
 }
}
