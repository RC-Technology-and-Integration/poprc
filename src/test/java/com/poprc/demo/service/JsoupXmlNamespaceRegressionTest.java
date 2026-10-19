package com.poprc.demo.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Library compatibility fixtures only; these do not assert an application XML route exists. */
class JsoupXmlNamespaceRegressionTest {
    @Test
    void preservaNamespacesEmAninhamentoPequeno() {
        int profundidade = 32;
        StringBuilder xml = new StringBuilder("<root>");
        for (int i = 0; i < profundidade; i++) {
            xml.append("<p").append(i).append(":item xmlns:p").append(i)
                    .append("='urn:teste:").append(i).append("'>");
        }
        xml.append("Conexão &amp; revisão");
        for (int i = profundidade - 1; i >= 0; i--) {
            xml.append("</p").append(i).append(":item>");
        }
        xml.append("</root>");

        Document doc = Jsoup.parse(xml.toString(), "", Parser.xmlParser());

        assertEquals(profundidade + 1, doc.select("*").size() - 1); // exclude #root document
        for (int i = 0; i < profundidade; i++) {
            assertEquals("urn:teste:" + i, doc.getElementsByTag("p" + i + ":item").first().tag().namespace());
        }
        assertEquals("Conexão & revisão", doc.text());
    }

    @Test
    void restauraNamespaceDoPaiAposEscopoLocalInclusiveComFechamentoIncompleto() {
        String xml = "<root xmlns:p='urn:pai'><inner xmlns:p='urn:local'><p:item/>"
                + "</inner><p:item>Revisão";

        Document doc = Jsoup.parse(xml, "", Parser.xmlParser());

        var itens = doc.getElementsByTag("p:item");
        assertEquals(2, itens.size());
        assertEquals("urn:local", itens.get(0).tag().namespace());
        assertEquals("urn:pai", itens.get(1).tag().namespace());
        assertEquals("Revisão", itens.get(1).text());
    }
}
