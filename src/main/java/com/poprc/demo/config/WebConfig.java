package com.poprc.demo.config;

import com.poprc.demo.storage.UploadStorage;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final com.poprc.demo.security.OperacaoSensivelInterceptor operacaoSensivelInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(operacaoSensivelInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String diretorioAbsoluto = UploadStorage.root().toUri().toString();

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(diretorioAbsoluto)
                .resourceChain(false)
                .addResolver(new org.springframework.web.servlet.resource.PathResourceResolver() {
                    @Override protected org.springframework.core.io.Resource getResource(
                            String resourcePath, org.springframework.core.io.Resource location) throws java.io.IOException {
                        // Também bloqueia variações de caixa em filesystems como o Windows.
                        String primeiro = resourcePath.replace('\\', '/').split("/", 2)[0]
                                .toLowerCase(java.util.Locale.ROOT);
                        if (primeiro.equals(".notas-fiscais") || primeiro.equals("notas-fiscais")) return null;
                        var resource = super.getResource(resourcePath, location);
                        if (resource == null) return null;
                        var real = resource.getFile().toPath().toRealPath();
                        var raiz = location.getFile().toPath().toRealPath();
                        // Aliases do filesystem (inclusive ponto final no Windows) também são privados.
                        for (String pasta : java.util.List.of(".notas-fiscais", "notas-fiscais")) {
                            var fiscal = raiz.resolve(pasta);
                            if (java.nio.file.Files.exists(fiscal) && real.startsWith(fiscal.toRealPath())) return null;
                        }
                        return resource;
                    }
                });
    }
}
