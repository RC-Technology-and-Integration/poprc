package com.poprc.demo.service;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.poprc.demo.model.Material;
import com.poprc.demo.model.TipoControleEstoque;
import com.poprc.demo.model.UnidadeMedida;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Component @RequiredArgsConstructor
public class ReservaRetiradaV2Guard {
 private final JdbcTemplate jdbc;
 private final MeterRegistry metricas;
 @Value("${app.estoque.guard.lock-timeout-ms:2000}") private int esperaMs;
 @Value("${app.retiradas-v2.enabled:false}") private boolean enabled;
 @Value("${app.retiradas-v2.os-ids:}") private String osIds;
 public boolean enabled(){return enabled;}
 public java.util.Set<Long> osHabilitadas(){var ids=new java.util.TreeSet<Long>();if(osIds!=null)for(String s:osIds.split(","))if(!s.isBlank())ids.add(Long.parseLong(s.trim()));return ids;}
 public boolean todasOsHabilitadas(){return enabled&&osHabilitadas().isEmpty();}
 public boolean osHabilitada(Long id){return enabled&&(todasOsHabilitadas()||osHabilitadas().contains(id));}
 public boolean possuiRegime(Long os){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from retiradas_v2 v join ordens_retirada o on o.id=v.or_id where o.ordem_servico_id=? and v.fisico not in ('RASCUNHO','CANCELADA'))",Boolean.class,os));}
 public boolean osNaoSerializada(Long os){
  return !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from comarca_materiais i join comarcas c on c.id=i.comarca_id left join materiais m on m.id=i.material_id where c.ordem_servico_id=? and (m.id is null or m.tipo_controle in ('BOBINA','ROLO')))",Boolean.class,os));
 }
 public void validarPrevisaoNoRegime(Long os, Material material) {
  if (material != null) validarTipoNoRegime(os, material.getTipoControle());
 }
 private void validarTipoNoRegime(Long os, TipoControleEstoque tipo) {
  if (os != null && serializado(tipo) && possuiRegime(os))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"OS já tem regime V2 emitido: não pode receber previsão serializada e virar regime misto. Cancele emissões sem entrega antes de mudar o regime; entregas históricas são preservadas.");
 }
 private boolean serializado(TipoControleEstoque tipo) {
  return tipo == TipoControleEstoque.BOBINA || tipo == TipoControleEstoque.ROLO;
 }
 public void validarVinculoComarca(Long comarca, Long os) {
  boolean divergente = Boolean.TRUE.equals(jdbc.queryForObject("""
          select exists(select 1 from retiradas_v2 v join ordens_retirada o on o.id=v.or_id
            where o.comarca_id=? and o.ordem_servico_id is distinct from ?
              and v.fisico not in ('RASCUNHO','CANCELADA'))
          """, Boolean.class, comarca, os));
  if (divergente) {
   throw new ResponseStatusException(HttpStatus.CONFLICT, "A comarca tem OR V2 emitida vinculada a outra OS.");
  }
 }
 // Lock transacional compartilhado antes dos locks de OS/OR/material, inclusive legado.
 public void lock() {
  if (esperaMs <= 0 || !TransactionSynchronizationManager.isActualTransactionActive()
          || !TransactionSynchronizationManager.isSynchronizationActive()) {
   throw new IllegalStateException("O guard de estoque exige transação e limite positivo de espera.");
  }

  long inicio = System.nanoTime();
  String resultado = "erro";
  try {
   jdbc.execute((ConnectionCallback<Void>) conexao -> {
    int anterior;
    try (var comando = conexao.createStatement();
         var valor = comando.executeQuery("select setting from pg_settings where name='lock_timeout'")) {
     if (!valor.next()) throw new SQLException("lock_timeout indisponível");
     anterior = valor.getInt(1);
    }
    int limite = anterior == 0 ? esperaMs : Math.min(anterior, esperaMs);
    try (var ajuste = conexao.prepareStatement("select set_config('lock_timeout', ?, true)");
         var lock = conexao.createStatement()) {
     ajuste.setString(1, limite + "ms");
     ajuste.execute();
     try {
      lock.execute("select pg_advisory_xact_lock(7316041)");
     } catch (SQLException e) {
      // O PostgreSQL já abortou a transação. A exceção sai até a fronteira de rollback.
      if ("55P03".equals(e.getSQLState())) throw new EsperaEstoqueExcedidaException(e);
      throw e;
     }
     ajuste.setString(1, anterior + "ms");
     ajuste.execute();
    }
    return null;
   });
   resultado = "adquirido";
   if (TransactionSynchronizationManager.getSynchronizations().stream().noneMatch(DuracaoGuard.class::isInstance)) {
    TransactionSynchronizationManager.registerSynchronization(new DuracaoGuard(metricas, System.nanoTime()));
   }
  } catch (EsperaEstoqueExcedidaException e) {
   metricas.counter("poprc.estoque.guard.rejeicoes").increment();
   resultado = "timeout";
   throw e;
  } finally {
   metricas.timer("poprc.estoque.guard.espera", "resultado", resultado)
           .record(System.nanoTime() - inicio, TimeUnit.NANOSECONDS);
  }
 }

 private record DuracaoGuard(MeterRegistry metricas, long adquiridoEm) implements TransactionSynchronization {
  @Override public void afterCompletion(int status) {
   String resultado = switch (status) {
    case STATUS_COMMITTED -> "commit";
    case STATUS_ROLLED_BACK -> "rollback";
    default -> "indefinido";
   };
   Timer.builder("poprc.estoque.guard.duracao").tag("resultado", resultado)
           .register(metricas).record(System.nanoTime() - adquiridoEm, TimeUnit.NANOSECONDS);
  }
 }
 public BigDecimal fixa(Long material,Long local){return jdbc.queryForObject("select coalesce(sum(quantidade),0) from retirada_v2_reservas where material_id=? and local_id=?",BigDecimal.class,material,local);}
 public BigDecimal entregueItem(Long id){return jdbc.queryForObject("select coalesce(quantidade_retirada_v2,0) from comarca_materiais where id=?",BigDecimal.class,id);}
 public void semReservaItem(Long id){if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from retirada_v2_itens i join retirada_v2_reservas r using(or_id,material_id) where i.material_item_id=?)",Boolean.class,id)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Item vinculado a reserva de OR V2; cancele/revise a OR antes de alterar previsão.");}
 public void protegerCadastro(Material atual, TipoControleEstoque controleAtual,
                               TipoControleEstoque novoControle, UnidadeMedida novaUnidade) {
  boolean mudou = controleAtual != novoControle || atual.getUnidadeMedida() != novaUnidade;
  if (mudou && serializado(novoControle)) {
   for (Long os : jdbc.queryForList("""
           select distinct c.ordem_servico_id from comarca_materiais i join comarcas c on c.id=i.comarca_id
           where i.material_id=? and c.ordem_servico_id is not null
           """, Long.class, atual.getId())) {
    validarTipoNoRegime(os, novoControle);
   }
  }
  if(mudou&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from retirada_v2_itens i join retiradas_v2 v on v.or_id=i.or_id where i.material_id=? and (v.fisico='RESERVADA' or (v.entrega_id is not null and v.devolvida=false)))",Boolean.class,atual.getId())))throw new ResponseStatusException(HttpStatus.CONFLICT,"Unidade/tipo pertencem a uma OR V2 com obrigação física aberta; não podem ser convertidos.");
 }
}
