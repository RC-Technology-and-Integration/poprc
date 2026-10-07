package com.poprc.demo.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.ambiente=homologacao", "app.documentos.simulacao-enabled=false", "app.security.enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SimulacaoDesabilitadaIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired com.poprc.demo.repository.FuncionarioRepository funcionarios;
    @org.springframework.transaction.annotation.Transactional
    @Test void backendBloqueiaMesmoQueClienteEnvieFlag() throws Exception {
        mvc.perform(post("/api/ordens-servico").with(user("TESTE").roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{\"simulacao\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.erro").value(org.hamcrest.Matchers.containsString("Simulação")));
        // Usa identidade de sessão real para passar pela autorização de comarca.
        var funcionario = new com.poprc.demo.model.Funcionario();
        funcionario.setNome("TESTE FICTÍCIO");
        funcionario.setPerfilAcesso(com.poprc.demo.model.PerfilAcesso.ADMIN);
        funcionario = funcionarios.save(funcionario);
        var principal = new com.poprc.demo.security.UsuarioAutenticado(funcionario.getId(), "TESTE", "teste@example.invalid", "ADMIN");
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        mvc.perform(post("/api/documentos-internos/vistoria").with(authentication(auth)).with(csrf())
                .contentType("application/json").content("{\"comarcaId\":1,\"simulacao\":true,\"conteudoJson\":\"{}\"}"))
                .andExpect(status().isConflict());
    }
}
