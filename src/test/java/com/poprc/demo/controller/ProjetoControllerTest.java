package com.poprc.demo.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.poprc.demo.model.Contrato;
import com.poprc.demo.model.Projeto;
import com.poprc.demo.repository.ContratoRepository;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.repository.ProjetoRepository;
import com.poprc.demo.service.ArquivamentoService;
import com.poprc.demo.service.ComarcaService;
import com.poprc.demo.service.ProjetoEquipeService;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ProjetoControllerTest {

    private ProjetoController controller;

    @BeforeEach
    void setUp() {
        ProjetoRepository projetoRepository = mock(ProjetoRepository.class);
        ContratoRepository contratoRepository = mock(ContratoRepository.class);
        ComarcaService comarcaService = mock(ComarcaService.class);
        FuncionarioRepository funcionarioRepository = mock(FuncionarioRepository.class);
        ArquivamentoService arquivamentoService = mock(ArquivamentoService.class);
        ProjetoEquipeService projetoEquipeService = mock(ProjetoEquipeService.class);

        Contrato contrato = new Contrato();
        contrato.setId(1L);
        contrato.setArquivado(false);
        when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
        when(projetoRepository.save(any(Projeto.class))).thenAnswer(invocacao -> {
            Projeto projeto = invocacao.getArgument(0);
            projeto.setId(17L);
            return projeto;
        });

        controller = new ProjetoController(
                projetoRepository,
                mock(ComarcaRepository.class),
                contratoRepository,
                comarcaService,
                funcionarioRepository,
                arquivamentoService,
                projetoEquipeService);
    }

    @Test
    void criaProjetoAtivoQuandoArquivadoNaoFoiInformado() {
        Projeto projeto = new Projeto();
        Contrato contratoInformado = new Contrato();
        contratoInformado.setId(1L);
        projeto.setContrato(contratoInformado);
        projeto.setDataInicio(LocalDate.of(2026, 9, 1));
        projeto.setArquivado(null);

        ResponseEntity<Map<String, Object>> resposta = controller.salvarProjeto(projeto);

        assertEquals(HttpStatus.CREATED, resposta.getStatusCode());
        Projeto salvo = (Projeto) resposta.getBody().get("projeto");
        assertFalse(salvo.getArquivado());
        assertEquals("PENDENTE", salvo.getAsBuiltStatus());
    }

    @Test
    void rejeitaProjetoSemDataDeInicio() {
        Projeto projeto = new Projeto();
        Contrato contratoInformado = new Contrato();
        contratoInformado.setId(1L);
        projeto.setContrato(contratoInformado);

        ResponseEntity<Map<String, Object>> resposta = controller.salvarProjeto(projeto);

        assertEquals(HttpStatus.BAD_REQUEST, resposta.getStatusCode());
        assertEquals("Data de início é obrigatória", resposta.getBody().get("erro"));
    }
}
