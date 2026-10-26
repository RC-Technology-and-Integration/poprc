package com.poprc.demo.service;

import com.poprc.demo.model.Material;
import com.poprc.demo.repository.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class CadastroMaterialEstadoServiceTest {
    final MaterialRepository materiais = mock(MaterialRepository.class);
    final MovimentacaoEstoqueRepository movimentos = mock(MovimentacaoEstoqueRepository.class);
    final FuncionarioRepository funcionarios = mock(FuncionarioRepository.class);
    final SaldoLocalService saldos = mock(SaldoLocalService.class);
    final EstoqueService service = new EstoqueService(materiais, movimentos, funcionarios, saldos);

    @ParameterizedTest
    @ValueSource(strings={"id", "idZero", "idNegativo", "version", "versionNegativa", "reservaInteira", "reservaInteiraNegativa", "reservaDecimal", "reservaDecimalNegativa"})
    void chamadasInternasInvalidasNaoAlcancamRepositoriosNemNormalizamObjeto(String campo) {
        var material = new Material(); material.setNome("SINTETICO"); material.setPartNumber("SINTETICO");
        material.setAtivo(false);
        switch(campo) {
            case "id" -> material.setId(1L);
            case "idZero" -> material.setId(0L);
            case "idNegativo" -> material.setId(-1L);
            case "version" -> material.setVersion(0L);
            case "versionNegativa" -> material.setVersion(-1L);
            case "reservaInteira" -> material.setQuantidadeReservada(1);
            case "reservaInteiraNegativa" -> material.setQuantidadeReservada(-1);
            case "reservaDecimal" -> material.setMetragemReservada(new BigDecimal("0.001"));
            case "reservaDecimalNegativa" -> material.setMetragemReservada(new BigDecimal("-0.001"));
        }
        assertThrows(IllegalArgumentException.class, () -> service.cadastrarMaterial(material));
        verifyNoInteractions(materiais, movimentos, funcionarios, saldos);
        assertFalse(material.getAtivo());
    }

    @Test void novoMaterialInternoRecebeEstadoDoServidor() {
        var m = new Material(); m.setNome("SINTETICO"); m.setPartNumber("SINTETICO");
        m.setAtivo(false); m.setQuantidadeReservada(null); m.setMetragemReservada(null);
        m.setRemovidoEm(LocalDateTime.now()); m.setRemovidoPor("CLIENTE");
        m.setRestauradoEm(LocalDateTime.now()); m.setRestauradoPor("CLIENTE");
        when(materiais.save(any())).thenAnswer(call -> call.getArgument(0));
        var result = service.cadastrarMaterial(m);
        assertTrue(result.getAtivo()); assertEquals(0, result.getQuantidadeReservada());
        assertEquals(BigDecimal.ZERO, result.getMetragemReservada());
        assertNull(result.getRemovidoEm()); assertNull(result.getRemovidoPor());
        assertNull(result.getRestauradoEm()); assertNull(result.getRestauradoPor());
        verify(materiais).save(m); verify(saldos).sincronizarSaldoInicial(m);
        verifyNoInteractions(movimentos);
    }
}
