package com.poprc.demo.controller;

import com.poprc.demo.model.Material;
import com.poprc.demo.service.EstoqueService;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses the actual POST body type, including before the exact-integer binding fix. */
class CadastroMaterialRequestTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Class<?> requestType = Arrays.stream(EstoqueController.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("cadastrarNovoMaterial"))
            .findFirst().orElseThrow().getParameterTypes()[0];

    @ParameterizedTest
    @ValueSource(strings = {"2", "2.0", "\"2\"", "\"2.0\"", "2e0", "0"})
    void inteirosNumericosOuTextuaisSaoConvertidosExatamente(String value) {
        Material request = (Material) mapper.readValue("{\"quantidadeDisponivel\":" + value + "}", requestType);
        assertEquals(value.equals("0") ? 0 : 2, request.getQuantidadeDisponivel());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"quantidadeDisponivel\":null}", "{\"quantidadeDisponivel\":\"\"}"})
    void nuloAusenteEVazioMantemContrato(String body) {
        assertNull(((Material) mapper.readValue(body, requestType)).getQuantidadeDisponivel());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.125", "1.125", "\"1.125\"", "2147483648", "\"abc\"", "true", "[]", "{}"})
    void fracaoOverflowEInvalidoNaoSofremTruncamento(String value) {
        assertThrows(JacksonException.class, () -> mapper.readValue("{\"quantidadeDisponivel\":" + value + "}", requestType));
    }

    @Test
    void copiaPreservaCamposLegitimosSemPersistirASubclasseDoRequest() {
        conferirCopia("""
                {"nome":"TESTE CAD", "partNumber":"TESTE-PN", "fabricante":"TESTE FAB",
                 "fornecedor":"TESTE FORN", "categoria":"FERRAMENTA", "descricao":"TESTE descricao",
                 "fotoProdutoUrl":"TESTE-foto", "tipoControle":"PECA_COM_COMPRIMENTO", "unidadeMedida":"PECA",
                 "dimensao":"TESTE dimensao", "comprimentoPorPeca":3.125, "quantidadeDisponivel":2,
                 "metragemDisponivel":6.250, "estoqueMinimo":0.125, "custoMedio":8.1234, "localizacao":"TESTE LOCAL"}
                """);
    }

    @Test
    void copiaNaoAmpliaCamposControladosJaAceitosPeloCorpoMaterialAnterior() {
        // Characterizes the existing surface, not an endorsement of client-controlled ID/reservations.
        conferirCopia("""
                {"id":42, "version":7, "quantidadeReservada":2, "metragemReservada":0.375,
                 "ativo":false, "removidoPor":"TESTE", "restauradoPor":"TESTE"}
                """);
    }

    private void conferirCopia(String json) {
        Material contratoAnterior = mapper.readValue(json, Material.class);
        var request = mapper.readValue(json, EstoqueController.CadastroMaterialRequest.class);
        var service = mock(EstoqueService.class);
        when(service.cadastrarMaterial(any(Material.class))).thenAnswer(call -> call.getArgument(0));
        var controller = new EstoqueController(service, null, null, null, null, null, null, null);
        var response = controller.cadastrarNovoMaterial(request);
        var recebido = ArgumentCaptor.forClass(Material.class);
        verify(service).cadastrarMaterial(recebido.capture());
        assertEquals(Material.class, recebido.getValue().getClass());
        assertNotSame(request, recebido.getValue());
        assertEquals(contratoAnterior, recebido.getValue());
        assertSame(recebido.getValue(), response.getBody());
    }
}
