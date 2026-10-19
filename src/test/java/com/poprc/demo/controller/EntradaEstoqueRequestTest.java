package com.poprc.demo.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** Binding only: positivity and the required quantity axis remain service rules. */
class EntradaEstoqueRequestTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"2", "2.0", "2e0", "\"2\"", "\"2.0\"", "2147483647", "-2147483648", "0", "-1"})
    void preservaInteirosSemCoercaoComPerda(String jsonValue) {
        var body = "{\"quantidade\":" + jsonValue + "}";
        var request = mapper.readValue(body, EstoqueController.EntradaRequest.class);
        int expected = new BigDecimal(mapper.readTree(body).path("quantidade").asText()).intValueExact();
        assertEquals(expected, request.getQuantidade());
        assertTrue(mapper.readTree(mapper.writeValueAsString(request)).path("quantidade").isIntegralNumber());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"quantidade\":null}", "{\"quantidade\":\"\"}", "{\"quantidade\":\" \"}"})
    void preservaAusenteNuloOuVazioParaValidacaoDoEixoNoServico(String body) {
        assertNull(mapper.readValue(body, EstoqueController.EntradaRequest.class).getQuantidade());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.125", "1.125", "\"1.125\"", "2147483648", "-2147483649", "\"abc\"", "true", "[]", "{}"})
    void rejeitaFracaoOverflowETiposInvalidos(String jsonValue) {
        assertThrows(JacksonException.class, () -> mapper.readValue(
                "{\"quantidade\":" + jsonValue + "}", EstoqueController.EntradaRequest.class));
    }
}
