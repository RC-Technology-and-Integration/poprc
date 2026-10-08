package com.poprc.demo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Service
public class SimulacaoDocumentoService {
    public static final String MARCA = "TESTE — SEM VALIDADE OPERACIONAL";
    public static final String EMPRESA = "EMPRESA FICTÍCIA TESTE — HOMOLOGAÇÃO";
    private final boolean habilitada;

    public SimulacaoDocumentoService(
            @Value("${app.documentos.simulacao-enabled:false}") boolean solicitada,
            @Value("${app.ambiente:nao-definido}") String ambiente) {
        habilitada = solicitada && "homologacao".equals(ambiente);
    }

    public boolean isHabilitada() { return habilitada; }

    public void exigirHabilitada(boolean simulacao) {
        if (simulacao && !habilitada) {
            throw new IllegalStateException("Simulação documental exige habilitação explícita em homologação.");
        }
    }

    // Só altera a representação documental; vínculos e cadastros globais permanecem intactos.
    public static String conteudoFicticio(String json) {
        var mapper = new ObjectMapper();
        var node = mapper.readTree(json == null ? "{}" : json);
        if (!(node instanceof ObjectNode conteudo)) {
            throw new IllegalArgumentException("O conteúdo do documento deve ser um objeto JSON.");
        }
        conteudo.put("empresa", EMPRESA);
        conteudo.put("cnpj", "NÃO APLICÁVEL — EMPRESA FICTÍCIA");
        for (String campo : List.of("equipeResponsavel", "gestorRc", "gerenteForum", "tecnicoResponsavel",
                "gestorProjetoRc", "responsavelDesignadoNome", "gerenteDesignanteNome", "recebidoPor",
                "cargoGerente", "responsavelDesignadoCargo", "carimboGerente")) {
            conteudo.put(campo, "TESTE FICTÍCIO");
        }
        conteudo.put("cpfTecnico", "NÃO APLICÁVEL — IDENTIDADE FICTÍCIA");
        for (String campo : List.of("contrato", "projeto", "comarcaForum", "endereco")) {
            conteudo.put(campo, "TESTE FICTÍCIO");
        }
        conteudo.put("modelo", "SIMULAÇÃO DOCUMENTAL — " + MARCA);
        return mapper.writeValueAsString(conteudo);
    }

}
