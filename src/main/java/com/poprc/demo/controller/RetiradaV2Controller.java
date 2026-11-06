package com.poprc.demo.controller;

import com.poprc.demo.dto.RetiradaV2Request.*;
import com.poprc.demo.service.RetiradaV2Service;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.Map;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/ordens-retirada/v2")
public class RetiradaV2Controller {
    private final RetiradaV2Service service;
    @GetMapping("/config") public Map<String,Object> config(Authentication a){return service.configuracao(a);}
    @GetMapping("/contexto-os/{id}") public Map<String,Object> contexto(@PathVariable Long id,Authentication a){return service.contextoOs(id,a);}
    @GetMapping public Map<String,Object> listar(Authentication a,@RequestParam(defaultValue="retiradas") String fila,@RequestParam(defaultValue="0") int pagina){return service.listar(a,fila,pagina);}
    @GetMapping("/{id}") public Map<String,Object> detalhe(@PathVariable Long id,Authentication a){return service.detalhe(id,a);}
    @PostMapping("/rascunhos") public Map<String,Object> criar(@RequestBody Rascunho r,Authentication a){return service.criar(r,a);}
    @PatchMapping("/{id}/rascunho") public Map<String,Object> editar(@PathVariable Long id,@RequestBody Rascunho r,Authentication a){return service.editar(id,r,a);}
    @PostMapping("/{id}/autorizacoes/{tipo}") public Map<String,Object> autorizar(@PathVariable Long id,@PathVariable String tipo,@RequestBody Acao r,Authentication a){return service.autorizar(id,tipo,r,a);}
    @PostMapping("/{id}/emissoes") public Map<String,Object> emitir(@PathVariable Long id,@RequestBody Acao r,Authentication a){return service.emitir(id,r,a);}
    @PostMapping("/{id}/revisoes") public Map<String,Object> revisar(@PathVariable Long id,@RequestBody Acao r,Authentication a){return service.revisarOuCancelar(id,r,a,false);}
    @PostMapping("/{id}/cancelamento") public Map<String,Object> cancelar(@PathVariable Long id,@RequestBody Acao r,Authentication a){return service.revisarOuCancelar(id,r,a,true);}
    @PostMapping("/{id}/entrega") public Map<String,Object> entregar(@PathVariable Long id,@RequestBody Acao r,Authentication a){return service.entregar(id,r,a);}
    @PostMapping("/{id}/devolucao") public Map<String,Object> devolver(@PathVariable Long id,@RequestBody Acao r,Authentication a){return service.devolver(id,r,a);}
    @PostMapping(value="/{id}/comprovantes",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> anexar(@PathVariable Long id,@RequestPart("acao") Acao r,@RequestPart("arquivo") MultipartFile f,Authentication a)throws IOException{return service.anexar(id,r,f.getBytes(),a);}
    @PostMapping("/{id}/conferencia") public Map<String,Object> conferir(@PathVariable Long id,@RequestBody Acao r,Authentication a){return service.conferir(id,r,a);}
    @GetMapping("/{id}/arquivos/{arquivoId}") public ResponseEntity<byte[]> arquivo(@PathVariable Long id,@PathVariable Long arquivoId,Authentication a){
        var f=service.arquivo(id,arquivoId,a);return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.mime()))
                .header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=\""+f.nome()+"\"")
                .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .header("Content-Security-Policy","sandbox").body(f.bytes());
    }
}
