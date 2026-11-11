package com.poprc.demo.service;

import java.math.BigDecimal;

final class PrecisaoEstoque {
    private PrecisaoEstoque() {
    }

    static void metragem(BigDecimal valor, String campo) {
        validar(valor, 3, 11, campo);
    }

    static void custo(BigDecimal valor) {
        validar(valor, 4, 11, "O custo unitário");
    }

    static void total(BigDecimal valor) {
        validar(valor, 4, 15, "O valor total da movimentação");
    }

    static void saldo(BigDecimal valor, boolean decimal, String campo) {
        if (decimal) {
            metragem(valor, campo);
        } else if (valor.signum() < 0 || valor.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0
                || valor.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(campo + " deve ser inteiro entre 0 e 2147483647.");
        }
    }

    private static void validar(BigDecimal valor, int escala, int digitosInteiros, String campo) {
        BigDecimal normalizado = valor.stripTrailingZeros();
        if (normalizado.signum() < 0 || normalizado.scale() > escala
                || (long) normalizado.precision() - normalizado.scale() > digitosInteiros) {
            throw new IllegalArgumentException(campo + " excede o limite de " + digitosInteiros
                    + " dígitos inteiros e " + escala + " casas decimais.");
        }
    }
}
