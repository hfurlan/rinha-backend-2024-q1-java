package com.github.hfurlan.rinha;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JSonUtilTest {

    @Test
    public void parseRequest_payloadValido_retornaTransacao() {
        String json = "{\"valor\": 1000, \"tipo\" : \"c\", \"descricao\" : \"teste\"}";
        Transacao transacao = JSonUtil.parseRequest(json.getBytes());
        assertEquals( 1000, transacao.valor);
        assertEquals( 'c', transacao.tipo);
        assertEquals( "teste", transacao.descricao);
    }

//    @Test(expected = IllegalArgumentException.class)
//    public void parseRequest_descricaoSemAspas_retornaException() {
//        String json = "{\"valor\": 1000, \"tipo\" : \"c\", \"descricao\" : teste}";
//        JSonUtil.parseRequest(json.getBytes());
//    }
}