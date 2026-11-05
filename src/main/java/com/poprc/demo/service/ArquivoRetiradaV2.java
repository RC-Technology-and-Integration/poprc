package com.poprc.demo.service;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.poprc.demo.storage.UploadStorage;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.List;
import java.io.*;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@Component
public class ArquivoRetiradaV2 {
 @org.springframework.beans.factory.annotation.Value("${app.ambiente:nao-definido}") private String ambiente;
 public record Arquivo(String caminho,String hash,String mime){}
 public String hash(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException(e);}}
 public Path raiz(){return UploadStorage.directory(".retiradas-v2");}
 public Arquivo guardar(byte[] b,String mime){try{Path dir=raiz();Files.createDirectories(dir);String nome=UUID.randomUUID().toString();Path alvo=dir.resolve(nome);try(var canal=java.nio.channels.FileChannel.open(alvo,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(b);while(buffer.hasRemaining())canal.write(buffer);canal.force(true);}if(!hash(b).equals(hash(Files.readAllBytes(alvo))))throw new IOException("Integridade do arquivo escrito");return new Arquivo(nome,hash(b),mime);}catch(IOException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Não foi possível armazenar arquivo; operação não concluída.",e);}}
 public byte[] ler(String nome,String hash){try{if(!nome.matches("[0-9a-f-]{36}"))throw new IOException();byte[] b=Files.readAllBytes(raiz().resolve(nome));if(!hash(b).equals(hash))throw new IOException();return b;}catch(IOException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Arquivo ausente ou sem integridade; não será regenerado.");}}
 public String validar(byte[] b){
  if(b.length==0||b.length>10*1024*1024)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Arquivo deve ter até 10 MiB.");
  if(b.length>4&&b[0]=='%'&&b[1]=='P'&&b[2]=='D'&&b[3]=='F'){
   try{PdfReader r=new PdfReader(b);try{if(r.isEncrypted()||r.getNumberOfPages()>20||r.getXrefSize()>10000)throw new IllegalArgumentException();
    for(int n=0;n<r.getXrefSize();n++){PdfObject o=r.getPdfObject(n);if(o instanceof PdfDictionary d){for(PdfName key:d.getKeys())if(Set.of("/JS","/JavaScript","/AA","/OpenAction","/Launch","/EmbeddedFiles","/RichMediaContent").contains(key.toString()))throw new IllegalArgumentException();}}
    return "application/pdf";
   }finally{r.close();}}catch(Exception e){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"PDF inválido, criptografado, ativo ou acima dos limites.");}
  }
  boolean png=b.length>8&&b[0]==(byte)137&&b[1]==80&&b[2]==78&&b[3]==71;
  boolean jpeg=b.length>3&&b[0]==(byte)255&&b[1]==(byte)216;
  if(png||jpeg){try(var stream=javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(b))){var readers=javax.imageio.ImageIO.getImageReaders(stream);if(readers.hasNext()){var reader=readers.next();try{reader.setInput(stream);int width=reader.getWidth(0),height=reader.getHeight(0);if(width>0&&height>0&&width<=6000&&height<=6000&&(long)width*height<=16000000&&reader.read(0)!=null)return png?"image/png":"image/jpeg";}finally{reader.dispose();}}}catch(IOException|RuntimeException ignored){}}
  throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Envie PDF, JPEG ou PNG válido; nome/extensão não bastam.");
 }
 public byte[] pdf(String numero,int versao,String destino,String deposito,String os,List<Map<String,Object>> itens,String observacoes,String tipo) {
  try {
   var azul=new java.awt.Color(23,80,144);var cinza=new java.awt.Color(215,224,234);
   var texto=FontFactory.getFont(FontFactory.HELVETICA,9);
   var pequeno=FontFactory.getFont(FontFactory.HELVETICA,8);
   var forte=FontFactory.getFont(FontFactory.HELVETICA_BOLD,10,azul);
   ByteArrayOutputStream out=new ByteArrayOutputStream();Document d=new Document(PageSize.A4,36,36,72,60);PdfWriter w=PdfWriter.getInstance(d,out);
   var fonteCabecalho=BaseFont.createFont(BaseFont.HELVETICA_BOLD,BaseFont.WINANSI,false);
   var fonteRodape=BaseFont.createFont(BaseFont.HELVETICA,BaseFont.WINANSI,false);
   Image marca=null;try(var in=ArquivoRetiradaV2.class.getResourceAsStream("/static/images/rclogo.jpg")){if(in!=null){marca=Image.getInstance(in.readAllBytes());marca.scaleToFit(26,26);marca.setAbsolutePosition(36,790);}}
   final Image logo=marca;
   w.setPageEvent(new PdfPageEventHelper(){
    @Override public void onEndPage(PdfWriter writer,Document doc){
     var canvas=writer.getDirectContent();canvas.saveState();
     try{
      if(logo!=null)canvas.addImage(logo);
      canvas.beginText();canvas.setFontAndSize(fonteCabecalho,10);canvas.setColorFill(azul);canvas.setCharacterSpacing(0);canvas.setWordSpacing(0);canvas.setTextRenderingMode(PdfContentByte.TEXT_RENDER_MODE_FILL);
      canvas.showTextAligned(Element.ALIGN_LEFT,"POP RC | "+numero+" | v"+versao+" | "+tipo,logo==null?36:72,804,0);
      canvas.setFontAndSize(fonteRodape,8);canvas.setColorFill(java.awt.Color.BLACK);
      String rodape="teste-local".equals(ambiente)?"TESTE — SEM VALIDADE OPERACIONAL":"Emitido e comprovante assinado são registros distintos";
      canvas.showTextAligned(Element.ALIGN_LEFT,rodape,36,28,0);canvas.showTextAligned(Element.ALIGN_RIGHT,"Página "+writer.getPageNumber(),559,28,0);canvas.endText();
     }catch(Exception e){throw new IllegalStateException("Não foi possível finalizar cabeçalho/rodapé da OR.",e);}finally{canvas.restoreState();}
    }
   });
   d.open();d.add(new Paragraph("ORDEM DE RETIRADA",FontFactory.getFont(FontFactory.HELVETICA_BOLD,18,azul)));
   d.add(new Paragraph(numero+" / versão "+versao+" / "+tipo,forte));
   d.add(new Paragraph("OS / contrato: "+os, texto));d.add(new Paragraph("Destino / obra: "+destino,texto));d.add(new Paragraph("Depósito: "+deposito,texto));
   d.add(new Paragraph("Emitido em "+java.time.ZonedDateTime.now(CalendarioRetirada.ZONA).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))+" (America/Sao_Paulo)",pequeno));
   PdfPTable t=new PdfPTable(new float[]{1.2f,5.6f,1.5f,1.7f});t.setWidthPercentage(100);t.setSpacingBefore(15);t.setSpacingAfter(12);t.setHeaderRows(1);t.setSplitLate(true);
   for(String h:List.of("Código","Material / descrição","Unidade","Quantidade")){
    var c=new PdfPCell(new Phrase(h,FontFactory.getFont(FontFactory.HELVETICA_BOLD,9,java.awt.Color.WHITE)));c.setBackgroundColor(azul);c.setPadding(6);c.setBorderColor(cinza);t.addCell(c);
   }
   for(Map<String,Object> i:itens)for(String k:List.of("materialId","nome","unidade","quantidade")){
    Object v=i.get(k);String exibido=v instanceof java.math.BigDecimal n?n.stripTrailingZeros().toPlainString().replace('.',','):String.valueOf(v);
    var c=new PdfPCell(new Phrase(exibido,texto));c.setPadding(3.5f);c.setBorderColor(cinza);if(k.equals("quantidade"))c.setHorizontalAlignment(Element.ALIGN_RIGHT);t.addCell(c);
   }
   d.add(t);d.add(new Paragraph("Observações / cronograma",forte));d.add(new Paragraph(observacoes==null||observacoes.isBlank()?"—":observacoes,texto));
   PdfPTable assinaturas=new PdfPTable(2);assinaturas.setWidthPercentage(100);assinaturas.setSpacingBefore(20);assinaturas.setKeepTogether(true);
   for(String papel:List.of("Quem entrega / confere","Quem retira")){var c=new PdfPCell();c.setBorderColor(cinza);c.setPadding(10);c.setMinimumHeight(95);c.addElement(new Paragraph(papel,forte));c.addElement(new Paragraph("Nome: ______________________________",texto));c.addElement(new Paragraph("Assinatura física: _____________________",texto));c.addElement(new Paragraph("Data: ____/____/________",texto));assinaturas.addCell(c);}
   d.add(assinaturas);var nota=new Paragraph("Emissão/impressão não confirma entrega ou conferência documental. Comprovante assinado é arquivo separado. Quantidade efetiva e eventual divergência são registradas na aplicação.",pequeno);nota.setLeading(10);d.add(nota);d.close();return out.toByteArray();
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Falha na geração do PDF; repetir a mesma intenção.",e);}
 }
 public byte[] juntar(byte[] primeiro,byte[] segundo,String numero,int versao){
  try{
   var out=new ByteArrayOutputStream();var doc=new Document();var copy=new PdfCopy(doc,out);doc.open();
   for(byte[] b:List.of(primeiro,segundo)){var reader=new PdfReader(b);for(int i=1;i<=reader.getNumberOfPages();i++)copy.addPage(copy.getImportedPage(reader,i));copy.freeReader(reader);reader.close();}doc.close();
   var finalOut=new ByteArrayOutputStream();var reader=new PdfReader(out.toByteArray());var stamp=new PdfStamper(reader,finalOut);
   for(int p=1;p<=reader.getNumberOfPages();p++){
    var canvas=stamp.getOverContent(p);canvas.saveState();canvas.setColorFill(java.awt.Color.WHITE);canvas.rectangle(0,0,reader.getPageSize(p).getWidth(),36);canvas.fill();canvas.restoreState();
    String marca="teste-local".equals(ambiente)?"TESTE — SEM VALIDADE OPERACIONAL":"POP RC — DEVOLUÇÃO";
    ColumnText.showTextAligned(canvas,Element.ALIGN_LEFT,new Phrase(marca+" | "+numero+" v"+versao,FontFactory.getFont(FontFactory.HELVETICA,7)),36,18,0);
    ColumnText.showTextAligned(canvas,Element.ALIGN_RIGHT,new Phrase("Página "+p+" / "+reader.getNumberOfPages(),FontFactory.getFont(FontFactory.HELVETICA,7)),559,18,0);
   }
   stamp.close();reader.close();return finalOut.toByteArray();
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Não foi possível preservar o termo de devolução.",e);}
 }
}
