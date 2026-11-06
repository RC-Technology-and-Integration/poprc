package com.poprc.demo.integration;

import com.poprc.demo.DemoApplication;
import com.poprc.demo.dto.RetiradaV2Request.Acao;
import com.poprc.demo.security.UsuarioAutenticado;
import java.nio.file.*;
import java.util.*;
import java.math.BigDecimal;
import org.springframework.boot.SpringApplication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

/** Processo real separado. Nunca aponta para o banco da aplicacao ou injeta ordem artificial em Map.of. */
public class RetiradaV2ProcessoProbe extends RetiradaV2IntegrationTest {
 public static void main(String[] args)throws Exception{
  var mapper=new ObjectMapper();var estado=Path.of(args[1]);boolean preparar=args[0].equals("preparar");Map<String,Object> s;
  if(preparar){banco.create();s=new LinkedHashMap<>();s.put("url",banco.url());s.put("user",banco.user());s.put("password",banco.password());}
  else{s=mapper.readValue(Files.readString(estado),Map.class);var acao=mapper.readValue((String)s.get("acao"),Acao.class);var serializado=mapper.writeValueAsString(Map.of("acao",acao,"hash",s.get("hashBytes")));
   System.out.println("ORDEM_JVM="+serializado);
   if(serializado.equals(s.get("serializado"))){System.out.println("MESMA_ORDEM_SEM_ABRIR_BANCO");System.exit(77);}
  }
  var app=new SpringApplication(DemoApplication.class);
  try(var ctx=app.run("--spring.profiles.active=test","--spring.config.import=","--spring.datasource.url="+s.get("url"),"--spring.datasource.username="+s.get("user"),"--spring.datasource.password="+s.get("password"),"--server.address=127.0.0.1","--server.port=0","--spring.datasource.hikari.maximum-pool-size=4","--app.retiradas-v2.enabled=true","--app.security.enabled=true","--app.ambiente=teste-local")){
   var h=new RetiradaV2ProcessoProbe();ctx.getAutowireCapableBeanFactory().autowireBean(h);mapper=ctx.getBean(ObjectMapper.class);
   System.out.println("PID_JVM="+ProcessHandle.current().pid()+" BANCO="+s.get("url"));
   if(preparar){h.fixture();var v=h.emitir(2);v=h.service.entregar(h.id(v),h.acao(v,new BigDecimal("2"),null),h.estoquista);var a=h.acao(v,null,null);byte[] bytes=h.png();var resultado=h.service.anexar(h.id(v),a,bytes,h.estoquista);
    s.put("id",h.id(v));s.put("ator",((UsuarioAutenticado)h.estoquista.getPrincipal()).getFuncionarioId());s.put("acao",mapper.writeValueAsString(a));s.put("bytes",Base64.getEncoder().encodeToString(bytes));s.put("hashBytes",h.arquivos.hash(bytes));s.put("serializado",mapper.writeValueAsString(Map.of("acao",a,"hash",h.arquivos.hash(bytes))));s.put("resultado",resultado);Files.writeString(estado,mapper.writeValueAsString(s));
    System.out.println("ANEXO_COMMITADO="+s.get("serializado"));
   }else{
    long id=((Number)s.get("id")).longValue();var f=h.funcionarios.findById(((Number)s.get("ator")).longValue()).orElseThrow();var u=UsuarioAutenticado.de(f,"LOCAL");var auth=new UsernamePasswordAuthenticationToken(u,null,u.getAuthorities());var a=mapper.readValue((String)s.get("acao"),Acao.class);byte[] bytes=Base64.getDecoder().decode((String)s.get("bytes"));
    var antes=h.jdbc.queryForList("select * from retirada_v2_operacoes where escopo=?",id+":ANEXAR");var resultado=h.service.anexar(id,a,bytes,auth);var original=(Map<String,Object>)s.get("resultado");
    assertThat(((Number)resultado.get("revisao")).longValue()).isEqualTo(((Number)original.get("revisao")).longValue());assertThat(resultado.get("comprovanteAtivo")).isEqualTo(original.get("comprovanteAtivo"));
    assertThat(h.jdbc.queryForList("select * from retirada_v2_operacoes where escopo=?",id+":ANEXAR")).isEqualTo(antes);
    assertThat(h.jdbc.queryForObject("select count(*) from retirada_v2_arquivos where or_id=?",Long.class,id)).isEqualTo(2L);
    var imagem=new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB);imagem.setRGB(0,0,0xffffff);var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(imagem,"png",out);
    assertThatThrownBy(()->h.service.anexar(id,a,out.toByteArray(),auth)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("conteúdo diferente");
    System.out.println("RETRY_APOS_REINICIO_OK arquivos=2 operacoesAnexar=1 divergente=409");
   }
  }
 }
}
