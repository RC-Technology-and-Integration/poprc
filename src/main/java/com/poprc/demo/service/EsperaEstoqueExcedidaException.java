package com.poprc.demo.service;

/** Espera esgotada ao adquirir o guard transacional compartilhado. */
public class EsperaEstoqueExcedidaException extends RuntimeException {
    public EsperaEstoqueExcedidaException(Throwable cause) {
        super("O estoque está ocupado. Aguarde e tente novamente.", cause);
    }
}
