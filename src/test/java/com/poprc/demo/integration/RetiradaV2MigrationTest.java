package com.poprc.demo.integration;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Instalação vazia e expansão V40; recusa destinos que não sejam loopback e banco explicitamente de teste. */
class RetiradaV2MigrationTest {
 @Test void vaziaEV40PreservamOrsAssinaturasEDocumentos()throws Exception{
  String base=Objects.requireNonNull(System.getenv("TEST_DB_URL"));String user=System.getenv("TEST_DB_USERNAME"),senha=System.getenv("TEST_DB_PASSWORD");
  assertThat(base).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[A-Za-z0-9_]+_test");
  String prefix=base.substring(0,base.lastIndexOf('/')+1);String sufixo=UUID.randomUUID().toString().replace("-","").substring(0,10);
  String fresh="r2_fresh_"+sufixo+"_test",legacy="r2_legacy_"+sufixo+"_test";
  try(var c=DriverManager.getConnection(base,user,senha);var st=c.createStatement()){st.execute("create database "+fresh);st.execute("create database "+legacy);}
  var vazio=Flyway.configure().dataSource(prefix+fresh,user,senha).locations("classpath:db/migration").load();assertThat(vazio.migrate().migrationsExecuted).isEqualTo(43);
  Flyway.configure().dataSource(prefix+legacy,user,senha).locations("classpath:db/migration").target("40").load().migrate();
  try(var c=DriverManager.getConnection(prefix+legacy,user,senha);var st=c.createStatement()){
   for(String status:List.of("GERADA","RETIRADA","DEVOLVIDA")){
    st.execute("insert into ordens_retirada(numero_or,status,assinatura_conferente_base64,assinatura_retirante_base64,assinatura_recebimento_base64) values('LEGADO-"+status+"','"+status+"','sintetica-conferente','sintetica-retirante','sintetica-retorno')");
    st.execute("insert into ordens_retirada_documentos(ordem_retirada_id,fase,status_or,pdf_path,pdf_hash,gerado_em) select id,'GERACAO',status,'fixture-sem-arquivo-real.pdf',repeat('a',64),timestamp '2026-10-30 00:00:00' from ordens_retirada where numero_or='LEGADO-"+status+"'");
   }
   String antes=snapshot(st);Flyway.configure().dataSource(prefix+legacy,user,senha).locations("classpath:db/migration").load().migrate();assertThat(snapshot(st)).isEqualTo(antes);
   try(var rs=st.executeQuery("select count(*) from ordens_retirada where novo_fluxo=false")){rs.next();assertThat(rs.getInt(1)).isEqualTo(3);}
   try(var rs=st.executeQuery("select count(*) from retiradas_v2")){rs.next();assertThat(rs.getInt(1)).isZero();}
  }
  System.out.println("MIGRATIONS_V2_BANCOS_DESCARTAVEIS_PRESERVADOS="+fresh+","+legacy);
 }
 String snapshot(Statement st)throws SQLException{var out=new StringBuilder();try(var rs=st.executeQuery("select o.numero_or,o.status,o.assinatura_conferente_base64,o.assinatura_retirante_base64,o.assinatura_recebimento_base64,d.pdf_path,d.pdf_hash,d.gerado_em from ordens_retirada o join ordens_retirada_documentos d on d.ordem_retirada_id=o.id order by o.id")){while(rs.next())for(int i=1;i<=8;i++)out.append(rs.getString(i)).append('|');}return out.toString();}

 @Test void v41RecebeVersaoDoHashSemReescreverIntencoes()throws Exception{
  String base=Objects.requireNonNull(System.getenv("TEST_DB_URL"));assertThat(base).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[A-Za-z0-9_]+_test");String user=System.getenv("TEST_DB_USERNAME"),senha=System.getenv("TEST_DB_PASSWORD");String nome="r2_hash_"+UUID.randomUUID().toString().replace("-","")+"_test",url=base.substring(0,base.lastIndexOf('/')+1)+nome;
  try(var c=DriverManager.getConnection(base,user,senha);var st=c.createStatement()){st.execute("create database "+nome);}
  Flyway.configure().dataSource(url,user,senha).locations("classpath:db/migration").target("41").load().migrate();String antes;
  try(var c=DriverManager.getConnection(url,user,senha);var st=c.createStatement()){
   st.execute("insert into ordens_retirada(numero_or,status) values('TESTE-HASH-LEGADO','RASCUNHO_V2')");st.execute("insert into retirada_v2_operacoes(escopo,chave,hash,or_id,resultado) select '1:ANEXAR','11111111-1111-4111-8111-111111111111',repeat('a',64),id,'{\"id\":1}' from ordens_retirada where numero_or='TESTE-HASH-LEGADO'");
   try(var r=st.executeQuery("select row(escopo,chave,hash,or_id,resultado)::text from retirada_v2_operacoes")){r.next();antes=r.getString(1);}
  }
  assertThat(Flyway.configure().dataSource(url,user,senha).locations("classpath:db/migration").load().migrate().migrationsExecuted).isEqualTo(2);
  try(var c=DriverManager.getConnection(url,user,senha);var st=c.createStatement();var r=st.executeQuery("select row(escopo,chave,hash,or_id,resultado)::text,algoritmo from retirada_v2_operacoes")){r.next();assertThat(r.getString(1)).isEqualTo(antes);assertThat(r.getString(2)).isEqualTo("JSON_V1");}
  System.out.println("F02_V41_V42_PRESERVADO="+url);
 }
}
