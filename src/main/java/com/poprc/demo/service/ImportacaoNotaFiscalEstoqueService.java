package com.poprc.demo.service;

import com.poprc.demo.dto.NotaFiscalEstoqueDTO;
import com.poprc.demo.model.Funcionario;
import com.poprc.demo.model.ImportacaoNotaFiscal;
import com.poprc.demo.model.ImportacaoNotaFiscalItem;
import com.poprc.demo.model.LocalEstoque;
import com.poprc.demo.model.Material;
import com.poprc.demo.model.TipoControleEstoque;
import com.poprc.demo.model.UnidadeMedida;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.repository.ImportacaoNotaFiscalItemRepository;
import com.poprc.demo.repository.ImportacaoNotaFiscalRepository;
import com.poprc.demo.repository.LocalEstoqueRepository;
import com.poprc.demo.repository.MaterialRepository;
import com.poprc.demo.security.UsuarioAutenticado;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Service
@RequiredArgsConstructor
public class ImportacaoNotaFiscalEstoqueService {
    private static final int LIMITE_ARQUIVO = 10 * 1024 * 1024;
    private static final Pattern LINHA_DANFE = Pattern.compile(
            "^\\s*(\\S+)\\s+(.+?)\\s+(\\d{8})\\s+\\d{3,4}\\s+(\\d{4})\\s+([A-Z]{1,6})\\s+([\\d.,]+)\\s+([\\d.,]+)\\s+([\\d.,]+)(?:\\s|$).*");
    private static final Pattern CHAVE_OU_RESIDUO_FISCAL = Pattern.compile("\\s+\\d{30,}.*$");

    private final ImportacaoNotaFiscalRepository importacaoRepository;
    private final ImportacaoNotaFiscalItemRepository itemRepository;
    private final MaterialRepository materialRepository;
    private final FuncionarioRepository funcionarioRepository;
    private final LocalEstoqueRepository localEstoqueRepository;
    private final EstoqueService estoqueService;

    @Value("${app.upload.dir:rc_uploads}")
    private String uploadDir;

    public NotaFiscalEstoqueDTO.Preview analisar(NotaFiscalEstoqueDTO.ArquivoRequest request) {
        Arquivo arquivo = validarArquivo(request.nomeArquivo(), request.contentType(), request.arquivoBase64());
        NotaLida nota = "XML".equals(arquivo.tipo()) ? lerXml(arquivo.bytes()) : lerPdf(arquivo.bytes());
        List<NotaFiscalEstoqueDTO.ItemPreview> itens = nota.itens().stream()
                .map(this::enriquecerItem)
                .toList();
        if (itens.isEmpty()) {
            nota.avisos().add("Nenhum item foi reconhecido automaticamente. Adicione e revise as linhas antes de confirmar.");
        }
        return new NotaFiscalEstoqueDTO.Preview(
                arquivo.nome(), arquivo.tipo(), arquivo.hash(), nota.chaveAcesso(), nota.numero(), nota.serie(),
                nota.emitenteNome(), nota.emitenteCnpj(), nota.dataEmissao(), nota.valorTotal(),
                List.copyOf(nota.avisos()), itens);
    }

    @Transactional
    public NotaFiscalEstoqueDTO.Resultado confirmar(
            NotaFiscalEstoqueDTO.ConfirmarRequest request, UsuarioAutenticado usuario) {
        if (usuario == null) throw new IllegalArgumentException("Usuário autenticado não identificado.");
        Arquivo arquivo = validarArquivo(request.nomeArquivo(), request.contentType(), request.arquivoBase64());
        if (request.hashSha256() == null || !arquivo.hash().equalsIgnoreCase(request.hashSha256())) {
            throw new IllegalArgumentException("O arquivo não corresponde à pré-visualização analisada.");
        }
        if (importacaoRepository.existsByHashSha256(arquivo.hash())) {
            throw new IllegalArgumentException("Esta nota fiscal já foi importada.");
        }
        List<NotaFiscalEstoqueDTO.ItemConfirmacao> selecionados = request.itens() == null
                ? List.of()
                : request.itens().stream().filter(NotaFiscalEstoqueDTO.ItemConfirmacao::importar).toList();
        if (selecionados.isEmpty()) {
            throw new IllegalArgumentException("Selecione ao menos um item da nota fiscal.");
        }

        Funcionario funcionario = funcionarioRepository.findById(usuario.getFuncionarioId())
                .orElseThrow(() -> new IllegalArgumentException("Funcionário autenticado não encontrado."));
        LocalEstoque local = localEstoqueRepository.findById(request.localEstoqueId())
                .filter(item -> Boolean.TRUE.equals(item.getAtivo()))
                .orElseThrow(() -> new IllegalArgumentException("Depósito de destino não encontrado ou inativo."));

        ImportacaoNotaFiscal importacao = new ImportacaoNotaFiscal();
        importacao.setNomeArquivo(arquivo.nome());
        importacao.setArquivoPath(arquivar(arquivo));
        importacao.setHashSha256(arquivo.hash());
        importacao.setTipoArquivo(arquivo.tipo());
        importacao.setChaveAcesso(limitar(request.chaveAcesso(), 60));
        importacao.setNumero(limitar(request.numero(), 80));
        importacao.setSerie(limitar(request.serie(), 30));
        importacao.setEmitenteNome(limitar(request.emitenteNome(), 255));
        importacao.setEmitenteCnpj(limitar(digitos(request.emitenteCnpj()), 20));
        importacao.setDataEmissao(request.dataEmissao());
        importacao.setDataImportacao(LocalDateTime.now());
        importacao.setImportadoPor(funcionario.getNome());
        importacao.setFuncionario(funcionario);
        importacao.setLocalEstoque(local);
        importacao = importacaoRepository.save(importacao);

        int criados = 0;
        int existentes = 0;
        BigDecimal total = BigDecimal.ZERO;
        int indice = 0;
        for (NotaFiscalEstoqueDTO.ItemConfirmacao item : selecionados) {
            indice++;
            validarItem(item);
            Material material;
            String acao;
            if (item.materialId() != null) {
                material = materialRepository.findById(item.materialId())
                        .orElseThrow(() -> new IllegalArgumentException("Material vinculado não encontrado."));
                existentes++;
                acao = "VINCULADO_EXISTENTE";
            } else {
                material = criarMaterial(item, arquivo.hash(), indice);
                criados++;
                acao = "MATERIAL_CRIADO";
            }

            boolean metragem = TipoControleEstoque.METRAGEM.equals(material.getTipoControle())
                    || TipoControleEstoque.FRACIONADO.equals(material.getTipoControle());
            Integer quantidade = metragem ? null : inteiroExato(item.quantidade());
            BigDecimal metros = metragem ? item.quantidade() : null;
            estoqueService.registrarEntradaNotaFiscal(
                    material.getId(), quantidade, metros, item.valorUnitario(), funcionario.getId(), local.getId(),
                    request.numero());

            BigDecimal valorLinha = item.quantidade().multiply(item.valorUnitario())
                    .setScale(4, RoundingMode.HALF_UP);
            ImportacaoNotaFiscalItem linha = new ImportacaoNotaFiscalItem();
            linha.setImportacao(importacao);
            linha.setMaterial(material);
            linha.setCodigoProduto(limitar(item.codigoProduto(), 255));
            linha.setDescricao(item.descricao().trim());
            linha.setNcm(limitar(digitos(item.ncm()), 20));
            linha.setCfop(limitar(digitos(item.cfop()), 20));
            linha.setUnidadeFiscal(limitar(item.unidadeFiscal(), 20));
            linha.setQuantidade(item.quantidade());
            linha.setValorUnitario(item.valorUnitario());
            linha.setValorTotal(valorLinha);
            linha.setAcao(acao);
            itemRepository.save(linha);
            total = total.add(valorLinha);
        }

        importacao.setItensProcessados(selecionados.size());
        importacao.setMateriaisCriados(criados);
        importacao.setMateriaisExistentes(existentes);
        importacao.setValorTotal(total.setScale(4, RoundingMode.HALF_UP));
        importacaoRepository.save(importacao);
        return new NotaFiscalEstoqueDTO.Resultado(
                importacao.getId(), importacao.getNumero(), selecionados.size(), criados, existentes,
                importacao.getValorTotal());
    }

    @Transactional(readOnly = true)
    public List<NotaFiscalEstoqueDTO.Historico> listarHistorico() {
        return importacaoRepository.findTop50ByOrderByDataImportacaoDesc().stream()
                .map(item -> new NotaFiscalEstoqueDTO.Historico(
                        item.getId(), item.getNomeArquivo(), item.getNumero(), item.getEmitenteNome(),
                        item.getDataEmissao(), item.getDataImportacao(), item.getImportadoPor(),
                        item.getLocalEstoque().getNome(), item.getItensProcessados(), item.getValorTotal()))
                .toList();
    }

    @Transactional(readOnly = true)
    public NotaFiscalEstoqueDTO.Detalhe detalhar(Long id) {
        ImportacaoNotaFiscal importacao = buscarImportacao(id);
        List<NotaFiscalEstoqueDTO.ItemDetalhe> itens = itemRepository
                .findByImportacaoIdOrderByIdAsc(id).stream()
                .map(item -> new NotaFiscalEstoqueDTO.ItemDetalhe(
                        item.getId(), item.getMaterial().getId(), item.getMaterial().getNome(),
                        item.getMaterial().getPartNumber(), item.getCodigoProduto(), item.getDescricao(),
                        item.getNcm(), item.getCfop(), item.getUnidadeFiscal(), item.getQuantidade(),
                        item.getValorUnitario(), item.getValorTotal(), item.getAcao()))
                .toList();
        return new NotaFiscalEstoqueDTO.Detalhe(
                importacao.getId(), importacao.getNomeArquivo(), importacao.getTipoArquivo(),
                importacao.getChaveAcesso(), importacao.getNumero(), importacao.getSerie(),
                importacao.getEmitenteNome(), importacao.getEmitenteCnpj(), importacao.getDataEmissao(),
                importacao.getValorTotal(), importacao.getDataImportacao(), importacao.getImportadoPor(),
                importacao.getLocalEstoque().getNome(), importacao.getMateriaisCriados(),
                importacao.getMateriaisExistentes(), itens);
    }

    @Transactional(readOnly = true)
    public ArquivoArmazenado carregarArquivo(Long id) {
        ImportacaoNotaFiscal importacao = buscarImportacao(id);
        Path diretorio = diretorioNotasFiscais();
        Path arquivo = Path.of(importacao.getArquivoPath()).toAbsolutePath().normalize();
        if (!arquivo.startsWith(diretorio) || !Files.isRegularFile(arquivo)) {
            throw new IllegalArgumentException("O arquivo original desta nota fiscal não está disponível.");
        }
        String contentType = "XML".equalsIgnoreCase(importacao.getTipoArquivo())
                ? "application/xml" : "application/pdf";
        return new ArquivoArmazenado(arquivo, importacao.getNomeArquivo(), contentType);
    }

    private ImportacaoNotaFiscal buscarImportacao(Long id) {
        return importacaoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Importação de nota fiscal não encontrada."));
    }

    private Material criarMaterial(NotaFiscalEstoqueDTO.ItemConfirmacao item, String hash, int indice) {
        String partNumber = item.partNumber() == null || item.partNumber().isBlank()
                ? "NF-" + hash.substring(0, 10).toUpperCase(Locale.ROOT) + "-" + indice
                : item.partNumber().trim();
        if (materialRepository.existsByPartNumberIgnoreCase(partNumber)) {
            throw new IllegalArgumentException("O Part Number " + partNumber + " já pertence a outro material.");
        }
        Material material = new Material();
        material.setNome(item.nome().trim());
        material.setPartNumber(partNumber);
        material.setDescricao(item.descricao().trim());
        material.setCategoria(item.categoria() == null || item.categoria().isBlank()
                ? "MATERIAL_CONSUMO" : item.categoria());
        material.setTipoControle(parseControle(item.tipoControle()));
        material.setUnidadeMedida(parseUnidade(item.unidadeMedida(), material.getTipoControle()));
        material.setQuantidadeDisponivel(0);
        material.setQuantidadeReservada(0);
        material.setCustoMedio(BigDecimal.ZERO);
        material.setMetragemDisponivel(BigDecimal.ZERO);
        material.setMetragemReservada(BigDecimal.ZERO);
        material.setEstoqueMinimo(BigDecimal.ZERO);
        return estoqueService.cadastrarMaterial(material);
    }

    private NotaFiscalEstoqueDTO.ItemPreview enriquecerItem(ItemLido item) {
        Long material = null;
        if (item.codigoProduto() != null && !item.codigoProduto().isBlank()) {
            material = materialRepository.findByPartNumberIgnoreCase(item.codigoProduto())
                    .map(Material::getId).orElse(null);
        }
        boolean metros = item.unidadeFiscal() != null
                && List.of("M", "MT", "METRO", "METROS").contains(item.unidadeFiscal().toUpperCase(Locale.ROOT));
        return new NotaFiscalEstoqueDTO.ItemPreview(
                item.codigoProduto(), item.descricao(), item.ncm(), item.cfop(), item.unidadeFiscal(),
                item.quantidade(), item.valorUnitario(), item.valorTotal(), material,
                metros ? "METRAGEM" : "UNIDADE", metros ? "METRO" : "UNIDADE");
    }

    private NotaLida lerXml(byte[] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            Element infNfe = primeiro(document, "infNFe");
            Element ide = primeiro(infNfe, "ide");
            Element emit = primeiro(infNfe, "emit");
            Element total = primeiro(infNfe, "ICMSTot");
            List<ItemLido> itens = new ArrayList<>();
            NodeList detalhes = infNfe.getElementsByTagNameNS("*", "det");
            for (int i = 0; i < detalhes.getLength(); i++) {
                Element produto = primeiro((Element) detalhes.item(i), "prod");
                BigDecimal quantidade = decimal(texto(produto, "qCom"));
                BigDecimal unitario = decimal(texto(produto, "vUnCom"));
                BigDecimal valorTotal = decimal(texto(produto, "vProd"));
                itens.add(new ItemLido(texto(produto, "cProd"), texto(produto, "xProd"),
                        texto(produto, "NCM"), texto(produto, "CFOP"), texto(produto, "uCom"),
                        quantidade, unitario, valorTotal));
            }
            String id = infNfe.getAttribute("Id");
            return new NotaLida(
                    id == null ? null : id.replaceFirst("^NFe", ""), texto(ide, "nNF"), texto(ide, "serie"),
                    texto(emit, "xNome"), texto(emit, "CNPJ"), data(texto(ide, "dhEmi"), texto(ide, "dEmi")),
                    decimal(texto(total, "vNF")), new ArrayList<>(), itens);
        } catch (Exception exception) {
            throw new IllegalArgumentException("XML inválido ou fora do padrão NF-e: " + exception.getMessage());
        }
    }

    private NotaLida lerPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper extrator = new PDFTextStripper();
            extrator.setSortByPosition(true);
            String texto = extrator.getText(document);
            List<String> avisos = new ArrayList<>();
            if (texto == null || texto.isBlank()) {
                avisos.add("O PDF parece ser uma imagem digitalizada e não possui texto pesquisável.");
                return NotaLida.vazia(avisos);
            }
            List<ItemLido> itens = lerItensDanfe(texto);
            avisos.add("PDFs de fornecedores variam de layout. Confira todos os campos antes de importar.");
            return new NotaLida(
                    capturar(texto, "(?m)^\\s*(\\d{44})\\s*$"),
                    capturar(texto, "(?i)N[º°o]?\\s*(?:NF|NOTA)?\\s*[:.-]?\\s*(\\d{1,12})"),
                    capturar(texto, "(?i)S[ÉE]RIE\\s*[:.-]?\\s*(\\d{1,4})"),
                    normalizarEspacos(capturar(texto, "(?m)^(.+?)\\s+N[º°o]?\\s*\\d{3,}\\s*$")),
                    capturar(texto, "(?m)(\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2})"),
                    dataBrasileira(capturar(texto, "(?m)(\\d{2}/\\d{2}/\\d{4})")),
                    itens.stream().map(ItemLido::valorTotal).reduce(BigDecimal.ZERO, BigDecimal::add),
                    avisos, itens);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Não foi possível ler o PDF: " + exception.getMessage());
        }
    }

    private List<ItemLido> lerItensDanfe(String texto) {
        List<ItemLido> itens = new ArrayList<>();
        ItemLido atual = null;
        boolean tabelaEncontrada = false;
        for (String linhaBruta : texto.split("\\R")) {
            String linha = linhaBruta == null ? "" : linhaBruta.trim();
            String linhaNormalizada = linha.toUpperCase(Locale.ROOT);
            if (linhaNormalizada.contains("DADOS DOS PRODUTOS")
                    || linhaNormalizada.contains("DESCRIÇÃO DOS PRODUTOS")) {
                tabelaEncontrada = true;
                continue;
            }
            if (tabelaEncontrada && (linhaNormalizada.startsWith("DADOS ADICIONAIS")
                    || linhaNormalizada.startsWith("INFORMAÇÕES COMPLEMENTARES")
                    || linhaNormalizada.startsWith("CÁLCULO DO ISSQN"))) {
                break;
            }

            Matcher matcher = LINHA_DANFE.matcher(linha);
            if (matcher.matches()) {
                if (atual != null) itens.add(atual);
                atual = new ItemLido(matcher.group(1), normalizarEspacos(matcher.group(2)), matcher.group(3),
                        matcher.group(4), matcher.group(5), decimalBrasileiro(matcher.group(6)),
                        decimalBrasileiro(matcher.group(7)), decimalBrasileiro(matcher.group(8)));
                tabelaEncontrada = true;
                continue;
            }

            if (tabelaEncontrada && atual != null) {
                String continuacao = limparContinuacaoDescricao(linha);
                if (!continuacao.isBlank()) {
                    atual = new ItemLido(atual.codigoProduto(),
                            normalizarEspacos(atual.descricao() + " " + continuacao),
                            atual.ncm(), atual.cfop(), atual.unidadeFiscal(), atual.quantidade(),
                            atual.valorUnitario(), atual.valorTotal());
                }
            }
        }
        if (atual != null) itens.add(atual);
        return itens;
    }

    private String limparContinuacaoDescricao(String linha) {
        if (linha == null || linha.isBlank() || linha.matches("^[\\d.,]+$")) return "";
        String limpa = CHAVE_OU_RESIDUO_FISCAL.matcher(linha.trim()).replaceFirst("").trim();
        limpa = limpa.replaceFirst("^0\\s+(?=.*[A-Za-zÀ-ÿ])", "");
        String normalizada = limpa.toUpperCase(Locale.ROOT);
        if (normalizada.isBlank()
                || normalizada.startsWith("PRODUTO UNITÁRIO")
                || normalizada.startsWith("CÓDIGO DESCRIÇÃO")) {
            return "";
        }
        return limpa;
    }

    private Arquivo validarArquivo(String nome, String contentType, String base64) {
        if (nome == null || nome.isBlank() || base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("Selecione um arquivo XML ou PDF.");
        }
        String nomeSeguro = Path.of(nome).getFileName().toString();
        String extensao = nomeSeguro.toLowerCase(Locale.ROOT).endsWith(".xml") ? "XML"
                : nomeSeguro.toLowerCase(Locale.ROOT).endsWith(".pdf") ? "PDF" : null;
        if (extensao == null) throw new IllegalArgumentException("Apenas arquivos XML ou PDF são aceitos.");
        try {
            String conteudo = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
            byte[] bytes = Base64.getDecoder().decode(conteudo);
            if (bytes.length == 0 || bytes.length > LIMITE_ARQUIVO) {
                throw new IllegalArgumentException("O arquivo deve ter até 10 MB.");
            }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new Arquivo(nomeSeguro, contentType, extensao, bytes, hash);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Conteúdo do arquivo inválido.");
        }
    }

    private String arquivar(Arquivo arquivo) {
        try {
            Path diretorio = diretorioNotasFiscais();
            Files.createDirectories(diretorio);
            String extensao = arquivo.tipo().toLowerCase(Locale.ROOT);
            Path destino = diretorio.resolve(arquivo.hash() + "." + extensao).normalize();
            if (!destino.startsWith(diretorio)) throw new IllegalArgumentException("Destino de arquivo inválido.");
            Files.write(destino, arquivo.bytes());
            return destino.toString();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Não foi possível arquivar a nota fiscal: " + exception.getMessage());
        }
    }

    private Path diretorioNotasFiscais() {
        return Path.of(uploadDir).toAbsolutePath().normalize().resolve("notas-fiscais").normalize();
    }

    private void validarItem(NotaFiscalEstoqueDTO.ItemConfirmacao item) {
        if (item.descricao() == null || item.descricao().isBlank()) {
            throw new IllegalArgumentException("Todos os itens precisam de descrição.");
        }
        if (item.materialId() == null && (item.nome() == null || item.nome().isBlank())) {
            throw new IllegalArgumentException("Informe o nome dos materiais que serão criados.");
        }
        if (item.quantidade() == null || item.quantidade().signum() <= 0) {
            throw new IllegalArgumentException("Todas as quantidades importadas devem ser maiores que zero.");
        }
        if (item.valorUnitario() == null || item.valorUnitario().signum() < 0) {
            throw new IllegalArgumentException("O valor unitário não pode ser negativo.");
        }
    }

    private int inteiroExato(BigDecimal valor) {
        try {
            return valor.stripTrailingZeros().intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Quantidade fracionada exige que o material seja controlado por metragem.");
        }
    }

    private TipoControleEstoque parseControle(String valor) {
        try {
            TipoControleEstoque controle = valor == null ? TipoControleEstoque.UNIDADE
                    : TipoControleEstoque.valueOf(valor);
            if (TipoControleEstoque.BOBINA.equals(controle) || TipoControleEstoque.ROLO.equals(controle)) {
                throw new IllegalArgumentException("Bobinas e rolos devem ser cadastrados individualmente.");
            }
            return controle;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Tipo de controle inválido para o item fiscal.");
        }
    }

    private UnidadeMedida parseUnidade(String valor, TipoControleEstoque controle) {
        if (TipoControleEstoque.METRAGEM.equals(controle)) return UnidadeMedida.METRO;
        try {
            return valor == null ? UnidadeMedida.UNIDADE : UnidadeMedida.valueOf(valor);
        } catch (IllegalArgumentException exception) {
            return UnidadeMedida.UNIDADE;
        }
    }

    private Element primeiro(Node raiz, String nome) {
        if (raiz == null) throw new IllegalArgumentException("Campo XML ausente: " + nome);
        NodeList lista = raiz instanceof Document document
                ? document.getElementsByTagNameNS("*", nome)
                : ((Element) raiz).getElementsByTagNameNS("*", nome);
        if (lista.getLength() == 0) throw new IllegalArgumentException("Campo XML ausente: " + nome);
        return (Element) lista.item(0);
    }

    private String texto(Element raiz, String nome) {
        if (raiz == null) return null;
        NodeList lista = raiz.getElementsByTagNameNS("*", nome);
        return lista.getLength() == 0 ? null : lista.item(0).getTextContent().trim();
    }

    private BigDecimal decimal(String valor) {
        return valor == null || valor.isBlank() ? BigDecimal.ZERO : new BigDecimal(valor.trim());
    }

    private BigDecimal decimalBrasileiro(String valor) {
        if (valor == null || valor.isBlank()) return BigDecimal.ZERO;
        String normalizado = valor.trim().replace(".", "").replace(',', '.');
        return new BigDecimal(normalizado);
    }

    private LocalDateTime data(String dataHora, String somenteData) {
        try {
            if (dataHora != null) return OffsetDateTime.parse(dataHora).toLocalDateTime();
            if (somenteData != null) return java.time.LocalDate.parse(somenteData).atStartOfDay();
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private LocalDateTime dataBrasileira(String valor) {
        try {
            return valor == null ? null
                    : java.time.LocalDate.parse(valor,
                            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")).atStartOfDay();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String capturar(String texto, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(texto);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String digitos(String valor) {
        return valor == null ? null : valor.replaceAll("\\D", "");
    }

    private String limitar(String valor, int limite) {
        if (valor == null || valor.isBlank()) return null;
        String normalizado = valor.trim();
        return normalizado.substring(0, Math.min(normalizado.length(), limite));
    }

    private String normalizarEspacos(String valor) {
        return valor == null ? null : valor.replaceAll("\\s+", " ").trim();
    }

    private record Arquivo(String nome, String contentType, String tipo, byte[] bytes, String hash) { }

    public record ArquivoArmazenado(Path path, String nomeArquivo, String contentType) { }
    private record ItemLido(String codigoProduto, String descricao, String ncm, String cfop,
            String unidadeFiscal, BigDecimal quantidade, BigDecimal valorUnitario, BigDecimal valorTotal) { }
    private record NotaLida(String chaveAcesso, String numero, String serie, String emitenteNome,
            String emitenteCnpj, LocalDateTime dataEmissao, BigDecimal valorTotal,
            List<String> avisos, List<ItemLido> itens) {
        private static NotaLida vazia(List<String> avisos) {
            return new NotaLida(null, null, null, null, null, null, BigDecimal.ZERO, avisos, List.of());
        }
    }
}
