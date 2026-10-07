package com.poprc.demo.support;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

// Imagem de fixture para testes de persistência/PDF; não prova captura no navegador.
public final class AssinaturaTeste {
    public static String assinaturaTeste() {
        try {
            var imagem = new BufferedImage(260, 70, BufferedImage.TYPE_INT_RGB);
            var g = imagem.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 260, 70);
            g.setColor(new Color(185, 28, 28));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
            g.drawString("TESTE FICTÍCIO", 15, 43);
            g.dispose();
            var output = new ByteArrayOutputStream();
            ImageIO.write(imagem, "png", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception ex) {
            throw new IllegalStateException("Não foi possível gerar a marca de assinatura TESTE.", ex);
        }
    }
}
