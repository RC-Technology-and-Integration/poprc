package com.poprc.demo.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.poprc.demo.model.DocumentoInterno;
import com.poprc.demo.repository.DocumentoInternoRepository;
import com.poprc.demo.storage.UploadStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentoPdfService {

    private static final String DIRETORIO_DOCUMENTOS = "documentos";
    private static final Color AZUL = new Color(8, 57, 112);
    private static final Color AZUL_CLARO = new Color(229, 239, 250);
    private static final Color CINZA = new Color(71, 85, 105);
    private static final Color CINZA_CLARO = new Color(241, 245, 249);
    private static final Color VERMELHO = new Color(185, 28, 28);
    private static final Font TITULO = new Font(Font.HELVETICA, 15, Font.BOLD, AZUL);
    private static final Font SUBTITULO = new Font(Font.HELVETICA, 9, Font.BOLD, AZUL);
    private static final Font ROTULO = new Font(Font.HELVETICA, 7.5f, Font.BOLD, CINZA);
    private static final Font TEXTO = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, Color.BLACK);
    private static final Font TEXTO_PEQUENO = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, Color.BLACK);

    private static final List<String> OBJETO_SERVICO = List.of(
            "Instalação de cabeamento estruturado",
            "Instalação de eletrocalhas",
            "Instalação de canaletas",
            "Instalação de eletrodutos",
            "Instalação de patch panel e conectorização de pontos",
            "Conectorização de patch cords nos ativos",
            "Organização e identificação de racks",
            "Lançamento de cabos de rede CAT6A",
            "Lançamento de cabos de fibra",
            "Instalação de DIO e/ou Cassete",
            "Organização de equipamentos no rack");

    private static final List<String> ESTADO_INICIAL = List.of(
            "Foi realizado registro fotográfico completo do ambiente",
            "Foram registradas anomalias estruturais pré-existentes",
            "Eventuais irregularidades foram comunicadas formalmente à gestão do projeto",
            "Foram registradas as condições de funcionamento dos equipamentos",
            "Computadores, impressoras e telefones foram verificados");

    private static final List<String> RACK_NECESSIDADE = List.of(
            "HÁ necessidade de substituição do rack da comarca",
            "NÃO HÁ necessidade de substituição do rack da comarca");

    private static final List<String> RACK_NECESSIDADE_VALORES = List.of(
            "HA_NECESSIDADE",
            "NAO_HA_NECESSIDADE");

    private static final List<String> RACK_DISPONIBILIDADE = List.of(
            "O rack de substituição já está presente na comarca",
            "Não existe rack de substituição na comarca");

    private static final List<String> RACK_DISPONIBILIDADE_VALORES = List.of(
            "PRESENTE",
            "NAO_EXISTE");

    private static final List<String> ESTADO_FINAL = List.of(
            "O ambiente foi entregue limpo e organizado",
            "Não houve dano estrutural decorrente da execução",
            "O forro foi reinstalado adequadamente",
            "O telhado foi restabelecido ao estado original de acesso",
            "As canaletas e eletrocalhas/eletrodutos foram fixadas conforme padrão técnico",
            "Os cabos foram identificados conforme manual do fabricante",
            "O ambiente foi apresentado ao gerente do fórum",
            "Todos os equipamentos presentes nas salas estão no mesmo estado da entrada",
            "Foi realizado registro fotográfico final antes e depois");

    private final ObjectMapper objectMapper;
    private final DocumentoInternoRepository documentoRepository;

    public byte[] gerarPdf(DocumentoInterno documentoInterno) {
        try {
            JsonNode conteudo = objectMapper.readTree(documentoInterno.getConteudoJson() == null
                    ? "{}"
                    : documentoInterno.getConteudoJson());
            if (documentoInterno.isSimulacao()) {
                conteudo = objectMapper.readTree(SimulacaoDocumentoService.conteudoFicticio(documentoInterno.getConteudoJson()));
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Document pdf = new Document(PageSize.A4, 42, 42, 72, 48);
            PdfWriter writer = PdfWriter.getInstance(pdf, output);
            writer.setPageEvent(new CabecalhoRodape(6, documentoInterno.isSimulacao()));
            pdf.open();

            adicionarCapa(pdf, conteudo, documentoInterno);
            pdf.newPage();
            adicionarObjetoEInicio(pdf, conteudo, documentoInterno.isSimulacao());
            pdf.newPage();
            adicionarRackEInicioConclusao(pdf, conteudo, documentoInterno.isSimulacao());
            pdf.newPage();
            adicionarConclusaoEAceite(pdf, conteudo, documentoInterno.isSimulacao());
            pdf.newPage();
            adicionarSalvaguardaEAssinaturas(pdf, conteudo, documentoInterno);
            pdf.newPage();
            adicionarResponsavelDesignado(pdf, conteudo, documentoInterno);

            pdf.close();
            return output.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Não foi possível gerar o PDF do documento.", ex);
        }
    }

    private void adicionarCapa(Document pdf, JsonNode conteudo, DocumentoInterno documento) throws Exception {
        Paragraph titulo = new Paragraph("ORDEM DE SERVIÇO (OS)", TITULO);
        titulo.setAlignment(Element.ALIGN_CENTER);
        titulo.setSpacingAfter(3);
        pdf.add(titulo);

        Paragraph modelo = new Paragraph(texto(conteudo, "modelo", modeloPadrao(documento)), SUBTITULO);
        modelo.setAlignment(Element.ALIGN_CENTER);
        modelo.setSpacingAfter(15);
        pdf.add(modelo);

        PdfPTable identificacao = new PdfPTable(new float[] { 1.1f, 2.1f });
        identificacao.setWidthPercentage(100);
        adicionarCampo(identificacao, "Empresa contratada", texto(conteudo, "empresa", "RC TECHNOLOGY AND INTEGRATION LTDA"));
        adicionarCampo(identificacao, "CNPJ", texto(conteudo, "cnpj", "33.910.895/0001-50"));
        adicionarCampo(identificacao, "Contrato", texto(conteudo, "contrato", ""));
        adicionarCampo(identificacao, "Ordem de Serviço", texto(conteudo, "numeroOs", ""));
        adicionarCampo(identificacao, "Projeto", texto(conteudo, "projeto", ""));
        adicionarCampo(identificacao, "Comarca / Fórum", texto(conteudo, "comarcaForum", ""));
        adicionarCampo(identificacao, "Endereço", texto(conteudo, "endereco", ""));
        adicionarCampo(identificacao, "Data e hora de início", texto(conteudo, "dataInicio", ""));
        adicionarCampo(identificacao, "Data e hora de conclusão", texto(conteudo, "dataConclusao", ""));
        adicionarCampo(identificacao, "Equipe responsável", texto(conteudo, "equipeResponsavel", ""));
        adicionarCampo(identificacao, documento.isSimulacao() ? "Gestor fictício TESTE" : "Gestor RC", texto(conteudo, "gestorRc", ""));
        adicionarCampo(identificacao, "Gerente do Fórum", texto(conteudo, "gerenteForum", ""));
        pdf.add(identificacao);

        adicionarAviso(pdf, documento.isSimulacao() ? "Documento fictício de homologação" : "Documento gerado pelo sistema",
                documento.isSimulacao() ? "Exclusivo para simular abertura e encerramento da OS em homologação. Use somente identidades e marcas TESTE; não autoriza operação real."
                        : "Este documento deve acompanhar a abertura e o encerramento da OS. Quando impresso em branco, os campos podem ser preenchidos e assinados no local.");
        adicionarAuditoria(pdf, documento);
    }

    private String modeloPadrao(DocumentoInterno documento) {
        return "VISTORIA_INICIAL_OS".equals(documento.getTipo()) || "VISTORIA_OS".equals(documento.getTipo())
                ? "ABERTURA E VISTORIA TÉCNICA INICIAL"
                : "ENCERRAMENTO, ACEITE E CONFORMIDADE TÉCNICA";
    }

    private void adicionarObjetoEInicio(Document pdf, JsonNode conteudo, boolean simulacao) throws Exception {
        adicionarTituloSecao(pdf, "1. OBJETO DA ORDEM DE SERVIÇO");
        adicionarTexto(pdf, "Execução de serviços de infraestrutura tecnológica, incluindo (marcar conforme aplicável):");
        adicionarChecklist(pdf, conteudo, "objetoServicos", OBJETO_SERVICO);
        adicionarLinha(pdf, "Outros", texto(conteudo, "outrosObjeto", ""));
        adicionarCaixa(pdf, "Descrição dos serviços previstos ou executados", texto(conteudo, "descricaoServicos", ""), 70);

        adicionarTituloSecao(pdf, "2. REGISTRO DE CONDIÇÃO PREDIAL - ESTADO INICIAL");
        adicionarTexto(pdf, simulacao ? "No cenário fictício, simula-se que antes do início dos serviços:" : "Declara-se que, antes do início dos serviços:");
        adicionarChecklist(pdf, conteudo, "estadoInicial", ESTADO_INICIAL.subList(0, 4));
    }

    private void adicionarRackEInicioConclusao(Document pdf, JsonNode conteudo, boolean simulacao) throws Exception {
        adicionarChecklist(pdf, conteudo, "estadoInicial", ESTADO_INICIAL.subList(4, ESTADO_INICIAL.size()));
        adicionarCaixa(pdf, "Anomalias pré-existentes identificadas (se houver)", texto(conteudo, "anomaliasPreExistentes", ""), 72);
        adicionarLinha(pdf, "Protocolo de comunicação (se aplicável)", texto(conteudo, "protocoloComunicacao", ""));

        adicionarTituloSecao(pdf, "3. NECESSIDADE DE SUBSTITUIÇÃO DO RACK DA COMARCA");
        adicionarTexto(pdf, simulacao ? "No cenário fictício, a vistoria de TESTE simula que:" : "Após a realização da vistoria técnica, declaro que:");
        adicionarChecklistEscalar(pdf, conteudo, "rackNecessidade",
                RACK_NECESSIDADE_VALORES, RACK_NECESSIDADE);
        adicionarChecklistEscalar(pdf, conteudo, "rackDisponibilidade",
                RACK_DISPONIBILIDADE_VALORES, RACK_DISPONIBILIDADE);
        adicionarCaixa(pdf, "Observações / Justificativa (quando aplicável)",
                texto(conteudo, "rackObservacoes", ""), 45);
        adicionarLinha(pdf, "Local e data", texto(conteudo, "rackLocalData", ""));

        adicionarTituloSecao(pdf, "4. DECLARAÇÃO DE CONFORMIDADE TÉCNICA - ESTADO FINAL");
        adicionarChecklist(pdf, conteudo, "estadoFinal", ESTADO_FINAL.subList(0, 4));
    }

    private void adicionarConclusaoEAceite(Document pdf, JsonNode conteudo, boolean simulacao) throws Exception {
        adicionarChecklist(pdf, conteudo, "estadoFinal", ESTADO_FINAL.subList(4, ESTADO_FINAL.size()));
        adicionarCaixa(pdf, "Observações finais", texto(conteudo, "observacoesFinais", ""), 48);

        adicionarTituloSecao(pdf, "5. DECLARAÇÃO DE ACEITE E CIÊNCIA");
        adicionarTexto(pdf, simulacao ? "O gerente fictício simula ciência dos seguintes fatos de TESTE:" : "O Gerente do Fórum declara que:");
        adicionarTexto(pdf, "- Acompanhou ou tomou ciência da conclusão dos serviços;");
    }

    private void adicionarSalvaguardaEAssinaturas(Document pdf, JsonNode conteudo, DocumentoInterno documento) throws Exception {
        adicionarTexto(pdf, "- O ambiente foi vistoriado;");
        adicionarTexto(pdf, "- Os serviços foram executados conforme descrito;");
        adicionarTexto(pdf, "- Não há pendências aparentes no momento da vistoria.");
        adicionarCaixa(pdf, "Ressalvas (caso existam)", texto(conteudo, "ressalvas", ""), 62);

        adicionarTituloSecao(pdf, "6. CLÁUSULA DE RESGUARDO TÉCNICO");
        if (documento.isSimulacao()) {
            adicionarTexto(pdf, "Cláusula fictícia para exercitar o percurso documental. Nenhuma responsabilidade institucional, aceite real ou autorização operacional é emitida.");
            adicionarTexto(pdf, "As marcas TESTE registram exclusivamente uma simulação de ciência e assinatura em homologação.");
            adicionarTituloSecao(pdf, "7. ASSINATURAS DE TESTE");
            adicionarTexto(pdf, "Pela empresa fictícia TESTE:");
        } else {
        adicionarTexto(pdf, "A presente Ordem de Serviço e a vistoria prévia realizada conjuntamente têm como finalidade registrar as condições aparentes dos ambientes e equipamentos existentes antes da execução dos serviços, incluindo computadores, impressoras e telefones.");
        adicionarTexto(pdf, "Fica estabelecido que eventuais defeitos, falhas, vícios, desgastes naturais, irregularidades ou danos preexistentes não poderão ser imputados à equipe técnica da RC Technology, assim como qualquer dano futuro não poderá ser atribuído à execução dos serviços realizados, salvo mediante comprovação técnica de dolo ou culpa grave.");
        adicionarTexto(pdf, "A assinatura deste documento pelas partes envolvidas formaliza a ciência, concordância e validação das condições verificadas no ato da vistoria e da conclusão dos serviços executados.");

        adicionarTituloSecao(pdf, "7. ASSINATURAS");
        adicionarTexto(pdf, "Pela RC Technology:");
        }
        PdfPTable tabela = new PdfPTable(1);
        tabela.setWidthPercentage(100);
        adicionarAssinatura(tabela,
                "Técnico responsável - CPF: " + valorSimples(texto(conteudo, "cpfTecnico", "")),
                texto(conteudo, "tecnicoResponsavel", documento.getTecnicoAssinadoPor()),
                documento.getAssinaturaTecnicoBase64());
        pdf.add(tabela);
    }

    private void adicionarResponsavelDesignado(Document pdf, JsonNode conteudo, DocumentoInterno documento) throws Exception {
        adicionarTituloSecao(pdf, documento.isSimulacao() ? "7. ASSINATURAS DE TESTE (CONTINUAÇÃO)" : "7. ASSINATURAS (CONTINUAÇÃO)");
        PdfPTable tabela = new PdfPTable(2);
        tabela.setWidthPercentage(100);
        adicionarAssinatura(tabela, documento.isSimulacao() ? "Gestor fictício TESTE" : "Gestor do projeto RC Technology",
                texto(conteudo, "gestorProjetoRc", documento.getGestorAssinadoPor()),
                documento.getAssinaturaGestorBase64());
        adicionarAssinatura(tabela,
                "Gerente do Fórum / Unidade - " + texto(conteudo, "cargoGerente", ""),
                texto(conteudo, "gerenteForum", documento.getGerenteAssinadoPor()),
                documento.getAssinaturaGerenteBase64());
        pdf.add(tabela);
        adicionarLinha(pdf, "Data do aceite", texto(conteudo, "dataGerenteForum", ""));
        adicionarLinha(pdf, "Carimbo (se aplicável)", texto(conteudo, "carimboGerente", ""));

        adicionarTituloSecao(pdf, "RESPONSÁVEL DESIGNADO PARA ACOMPANHAMENTO DA VISTORIA");
        adicionarTexto(pdf, "(Preencher apenas caso a vistoria não seja acompanhada diretamente pelo(a) Gerente da Unidade)");
        adicionarLinha(pdf, "Nome", texto(conteudo, "responsavelDesignadoNome", ""));
        adicionarLinha(pdf, "Cargo / função", texto(conteudo, "responsavelDesignadoCargo", ""));
        adicionarLinha(pdf, "Assinatura", "");
        adicionarLinha(pdf, "Data", texto(conteudo, "responsavelDesignadoData", ""));

        adicionarTituloSecao(pdf, "DECLARAÇÃO DE DESIGNAÇÃO");
        String gerenteDesignante = texto(conteudo, "gerenteDesignanteNome",
                texto(conteudo, "gerenteForum", ""));
        if (documento.isSimulacao()) {
            adicionarTexto(pdf, "O gerente fictício TESTE simula a designação de um acompanhante fictício para a vistoria de TESTE. Esta declaração não representa qualquer instituição e não confere autorização real.");
        } else {
        adicionarTexto(pdf, "Eu, " + valorLinha(gerenteDesignante)
                + ", na condição de Gerente da Comarca/Unidade, declaro para os devidos fins que designo o(a) "
                + "servidor(a)/colaborador(a) acima identificado(a) para acompanhar a vistoria prévia e os "
                + "procedimentos relacionados à execução dos serviços, conferindo-lhe autorização para atuar "
                + "em minha representação durante todo o processo de inspeção inicial dos ambientes.");
        }
        adicionarLinha(pdf, "Assinatura do(a) Gerente", "");
        adicionarLinha(pdf, "Data", texto(conteudo, "declaracaoDesignacaoData", ""));
        adicionarAuditoria(pdf, documento);
    }

    private void adicionarTituloSecao(Document pdf, String titulo) throws Exception {
        PdfPTable faixa = new PdfPTable(1);
        faixa.setWidthPercentage(100);
        faixa.setSpacingBefore(7);
        faixa.setSpacingAfter(4);
        PdfPCell celula = new PdfPCell(new Phrase(titulo, SUBTITULO));
        celula.setBackgroundColor(AZUL_CLARO);
        celula.setBorderColor(AZUL);
        celula.setPadding(5);
        faixa.addCell(celula);
        pdf.add(faixa);
    }

    private void adicionarChecklist(Document pdf, JsonNode conteudo, String campo, List<String> opcoes) throws Exception {
        for (String opcao : opcoes) {
            PdfPTable linha = new PdfPTable(new float[] { 0.45f, 9.55f });
            linha.setWidthPercentage(100);
            linha.setKeepTogether(true);
            PdfPCell marcador = new PdfPCell(new Phrase(selecionado(conteudo, campo, opcao) ? "X" : " ", ROTULO));
            marcador.setHorizontalAlignment(Element.ALIGN_CENTER);
            marcador.setVerticalAlignment(Element.ALIGN_MIDDLE);
            marcador.setFixedHeight(14);
            marcador.setPadding(1);
            linha.addCell(marcador);
            PdfPCell descricao = new PdfPCell(new Phrase(opcao, TEXTO_PEQUENO));
            descricao.setBorder(Rectangle.NO_BORDER);
            descricao.setPaddingLeft(5);
            descricao.setVerticalAlignment(Element.ALIGN_MIDDLE);
            linha.addCell(descricao);
            pdf.add(linha);
        }
    }

    private void adicionarChecklistEscalar(
            Document pdf,
            JsonNode conteudo,
            String campo,
            List<String> valores,
            List<String> opcoes) throws Exception {
        String selecionado = texto(conteudo, campo, "");
        for (int indice = 0; indice < opcoes.size(); indice++) {
            PdfPTable linha = new PdfPTable(new float[] { 0.45f, 9.55f });
            linha.setWidthPercentage(100);
            linha.setKeepTogether(true);
            PdfPCell marcador = new PdfPCell(new Phrase(
                    valores.get(indice).equals(selecionado) ? "X" : " ",
                    ROTULO));
            marcador.setHorizontalAlignment(Element.ALIGN_CENTER);
            marcador.setVerticalAlignment(Element.ALIGN_MIDDLE);
            marcador.setFixedHeight(14);
            marcador.setPadding(1);
            linha.addCell(marcador);
            PdfPCell descricao = new PdfPCell(new Phrase(opcoes.get(indice), TEXTO_PEQUENO));
            descricao.setBorder(Rectangle.NO_BORDER);
            descricao.setPaddingLeft(5);
            descricao.setVerticalAlignment(Element.ALIGN_MIDDLE);
            linha.addCell(descricao);
            pdf.add(linha);
        }
    }

    private void adicionarLinha(Document pdf, String rotulo, String conteudo) throws Exception {
        PdfPTable tabela = new PdfPTable(new float[] { 1.8f, 4.2f });
        tabela.setWidthPercentage(100);
        tabela.setSpacingBefore(5);
        PdfPCell chave = new PdfPCell(new Phrase(rotulo, ROTULO));
        chave.setPadding(5);
        chave.setBackgroundColor(CINZA_CLARO);
        tabela.addCell(chave);
        PdfPCell valor = new PdfPCell(new Phrase(valorLinha(conteudo), TEXTO));
        valor.setPadding(5);
        tabela.addCell(valor);
        pdf.add(tabela);
    }

    private void adicionarCaixa(Document pdf, String rotulo, String conteudo, float altura) throws Exception {
        Paragraph label = new Paragraph(rotulo, ROTULO);
        label.setSpacingBefore(5);
        label.setSpacingAfter(2);
        pdf.add(label);
        PdfPTable tabela = new PdfPTable(1);
        tabela.setWidthPercentage(100);
        PdfPCell celula = new PdfPCell(new Phrase(valorCaixa(conteudo), TEXTO));
        celula.setMinimumHeight(altura);
        celula.setPadding(6);
        tabela.addCell(celula);
        pdf.add(tabela);
    }

    private void adicionarAviso(Document pdf, String titulo, String texto) throws Exception {
        PdfPTable tabela = new PdfPTable(1);
        tabela.setWidthPercentage(100);
        tabela.setSpacingBefore(14);
        PdfPCell celula = new PdfPCell();
        celula.setBackgroundColor(CINZA_CLARO);
        celula.setBorderColor(AZUL);
        celula.setPadding(8);
        celula.addElement(new Paragraph(titulo, SUBTITULO));
        celula.addElement(new Paragraph(texto, TEXTO_PEQUENO));
        tabela.addCell(celula);
        pdf.add(tabela);
    }

    private void adicionarTexto(Document pdf, String texto) throws Exception {
        Paragraph paragrafo = new Paragraph(texto, TEXTO);
        paragrafo.setLeading(12);
        paragrafo.setSpacingAfter(4);
        pdf.add(paragrafo);
    }

    private void adicionarCampo(PdfPTable tabela, String rotulo, String conteudo) {
        PdfPCell chave = new PdfPCell(new Phrase(rotulo, ROTULO));
        chave.setPadding(5);
        chave.setBackgroundColor(CINZA_CLARO);
        tabela.addCell(chave);
        PdfPCell valor = new PdfPCell(new Phrase(valorLinha(conteudo), TEXTO));
        valor.setPadding(5);
        tabela.addCell(valor);
    }

    private void adicionarAssinatura(PdfPTable tabela, String papel, String nome, String base64) throws Exception {
        PdfPCell celula = new PdfPCell();
        celula.setPadding(8);
        celula.setMinimumHeight(112);
        celula.addElement(new Paragraph(papel, ROTULO));
        Image assinatura = imagemAssinatura(base64);
        if (assinatura != null) {
            assinatura.scaleToFit(180, 55);
            assinatura.setAlignment(Element.ALIGN_CENTER);
            celula.addElement(assinatura);
        } else {
            celula.addElement(new Paragraph("\n\n\n________________________________________", TEXTO));
        }
        celula.addElement(new Paragraph(valorLinha(nome), TEXTO));
        tabela.addCell(celula);
    }

    private Image imagemAssinatura(String base64) throws Exception {
        if (base64 == null || !base64.contains(",")) {
            return null;
        }
        byte[] imagemBytes = Base64.getDecoder().decode(base64.substring(base64.indexOf(',') + 1));
        return Image.getInstance(imagemBytes);
    }

    private void adicionarAuditoria(Document pdf, DocumentoInterno documento) throws Exception {
        Paragraph auditoria = new Paragraph(
                "Registro eletrônico: documento #" + documento.getId()
                        + " | Status: " + valorSimples(documento.getStatus())
                        + " | Hash: " + valorSimples(documento.getHashRegistro()),
                new Font(Font.COURIER, 6.5f, Font.NORMAL, CINZA));
        auditoria.setSpacingBefore(12);
        pdf.add(auditoria);
    }

    @Transactional
    public DocumentoInterno arquivarPdf(DocumentoInterno documento) {
        try {
            byte[] bytes = gerarPdf(documento);
            String hash = sha256(bytes);
            Path pasta = UploadStorage.directory(DIRETORIO_DOCUMENTOS)
                    .toAbsolutePath().normalize();
            Files.createDirectories(pasta);
            String nome = "documento-" + documento.getId() + "-" + hash.substring(0, 16) + ".pdf";
            Path destino = pasta.resolve(nome).normalize();
            if (!destino.startsWith(pasta)) {
                throw new IllegalStateException("Caminho inválido para o PDF.");
            }
            Files.write(destino, bytes);
            documento.setPdfPath("/uploads/documentos/" + nome);
            documento.setPdfHash(hash);
            documento.setPdfGeradoEm(LocalDateTime.now());
            return documentoRepository.save(documento);
        } catch (Exception ex) {
            throw new IllegalStateException("Não foi possível arquivar o PDF do documento.", ex);
        }
    }

    public byte[] obterPdf(DocumentoInterno documento) {
        if (documento.getPdfPath() != null && !documento.getPdfPath().isBlank()) {
            try {
                String nome = Path.of(documento.getPdfPath()).getFileName().toString();
                Path arquivo = UploadStorage.directory(DIRETORIO_DOCUMENTOS).resolve(nome)
                        .toAbsolutePath().normalize();
                if (Files.exists(arquivo)) {
                    byte[] bytes = Files.readAllBytes(arquivo);
                    if (documento.getPdfHash() == null || documento.getPdfHash().equals(sha256(bytes))) {
                        return bytes;
                    }
                    throw new IllegalStateException("O PDF arquivado falhou na verificação de integridade.");
                }
            } catch (Exception ex) {
                throw new IllegalStateException("Não foi possível ler o PDF arquivado.", ex);
            }
        }
        return gerarPdf(documento);
    }

    private String texto(JsonNode node, String campo, String padrao) {
        JsonNode campoNode = node.get(campo);
        return campoNode == null || campoNode.isNull() ? padrao : campoNode.asText(padrao);
    }

    private boolean selecionado(JsonNode node, String campo, String opcao) {
        JsonNode valores = node.get(campo);
        if (valores == null || !valores.isArray()) {
            return false;
        }
        for (JsonNode valor : valores) {
            if (opcao.equals(valor.asText())) {
                return true;
            }
        }
        return false;
    }

    private String valorLinha(String texto) {
        return texto == null || texto.isBlank() ? "____________________________________________" : texto;
    }

    private String valorCaixa(String texto) {
        return texto == null || texto.isBlank() ? "\n\n____________________________________________\n____________________________________________" : texto;
    }

    private String valorSimples(String texto) {
        return texto == null || texto.isBlank() ? "não informado" : texto;
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static class CabecalhoRodape extends PdfPageEventHelper {
        private final int totalPaginas;
        private final boolean simulacao;

        private CabecalhoRodape(int totalPaginas, boolean simulacao) {
            this.totalPaginas = totalPaginas;
            this.simulacao = simulacao;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            try {
                PdfContentByte canvas = writer.getDirectContent();
                float largura = document.getPageSize().getWidth();
                float altura = document.getPageSize().getHeight();

                canvas.saveState();
                canvas.setColorFill(AZUL);
                canvas.rectangle(0, altura - 55, largura, 55);
                canvas.fill();
                canvas.setColorFill(VERMELHO);
                canvas.rectangle(0, 0, largura, 29);
                canvas.fill();
                canvas.restoreState();

                if (!simulacao) adicionarLogo(canvas, altura);
                canvas.beginText();
                canvas.setFontAndSize(com.lowagie.text.pdf.BaseFont.createFont(), 8);
                canvas.setColorFill(Color.WHITE);
                canvas.showTextAligned(Element.ALIGN_LEFT, simulacao ? SimulacaoDocumentoService.EMPRESA : "RC TECHNOLOGY AND INTEGRATION LTDA", 42, altura - 37, 0);
                canvas.showTextAligned(Element.ALIGN_LEFT, simulacao ? SimulacaoDocumentoService.MARCA : "Documento operacional", 42, 11, 0);
                canvas.showTextAligned(Element.ALIGN_RIGHT,
                        "Página " + writer.getPageNumber() + (simulacao ? "" : " de " + totalPaginas),
                        largura - 42, 11, 0);
                canvas.endText();
            } catch (Exception ex) {
                throw new IllegalStateException("Não foi possível finalizar a página do documento.", ex);
            }
        }

        private void adicionarLogo(PdfContentByte canvas, float altura) {
            try (InputStream input = DocumentoPdfService.class.getResourceAsStream("/static/images/rclogo.jpg")) {
                if (input == null) {
                    return;
                }
                Image logo = Image.getInstance(input.readAllBytes());
                logo.scaleToFit(31, 31);
                logo.setAbsolutePosition(8, altura - 43);
                canvas.addImage(logo);
            } catch (Exception ignored) {
                // O texto do cabeçalho mantém o documento identificável se a imagem não estiver disponível.
            }
        }
    }
}
