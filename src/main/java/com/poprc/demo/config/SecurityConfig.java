package com.poprc.demo.config;

import com.poprc.demo.model.Funcionario;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.security.UsuarioAutenticado;
import com.poprc.demo.security.ValidacaoSessaoFilter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private static final String ZOHO_PROFILE_URL = "https://profile.zoho.com/api/v1/user/self/profile";
    private final FuncionarioRepository funcionarioRepository;
    private final ValidacaoSessaoFilter validacaoSessaoFilter;
    private final boolean securityEnabled;
    private final boolean zohoEnabled;
    private final String frontendUrl;
    private final List<String> allowedOriginPatterns;

    public SecurityConfig(
            FuncionarioRepository funcionarioRepository,
            ValidacaoSessaoFilter validacaoSessaoFilter,
            @Value("${app.security.enabled:true}") boolean securityEnabled,
            @Value("${app.security.zoho-enabled:false}") boolean zohoEnabled,
            @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl,
            @Value("${app.cors.allowed-origin-patterns:http://localhost:[*],http://127.0.0.1:[*],http://192.168.*:[*],http://10.*:[*]}")
            List<String> allowedOriginPatterns) {
        this.funcionarioRepository = funcionarioRepository;
        this.validacaoSessaoFilter = validacaoSessaoFilter;
        this.securityEnabled = securityEnabled;
        this.zohoEnabled = zohoEnabled;
        this.frontendUrl = frontendUrl;
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/auth/dev-login", "/api/auth/login"))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    authorize.requestMatchers("/error", "/actuator/health",
                            "/api/auth/csrf", "/api/auth/dev-login", "/api/auth/dev-usuarios").permitAll();
                    authorize.requestMatchers("/api/auth/config", "/api/auth/login", "/api/auth/bootstrap").permitAll();
                    if (zohoEnabled) {
                        authorize.requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll();
                    }

                    if (!securityEnabled) {
                        authorize.anyRequest().permitAll();
                        return;
                    }

                    authorize.requestMatchers("/api/auth/me", "/api/auth/reauth",
                            "/api/auth/alterar-senha", "/api/auth/logout").authenticated();
                    authorize.requestMatchers("/uploads/financeiro/**").hasRole("ADMIN");
                    authorize.requestMatchers("/uploads/evidencias/**", "/uploads/comarcas/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "AUDITOR");
                    authorize.requestMatchers("/uploads/documentos/ordens-retirada/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "ESTOQUE", "AUDITOR");
                    authorize.requestMatchers("/uploads/documentos/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "AUDITOR");
                    authorize.requestMatchers("/uploads/**").authenticated();
                    authorize.requestMatchers(HttpMethod.GET, "/api/funcionarios/auditoria-acessos")
                            .hasRole("ADMIN");
                    authorize.requestMatchers(HttpMethod.GET, "/api/funcionarios/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "ESTOQUE");
                    authorize.requestMatchers("/api/funcionarios/**").hasRole("ADMIN");
                    authorize.requestMatchers(HttpMethod.GET, "/api/alertas/notificacoes")
                            .authenticated();
                    authorize.requestMatchers(HttpMethod.PATCH, "/api/alertas/notificacoes/*/lida")
                            .authenticated();
                    authorize.requestMatchers("/api/dashboard/**", "/api/alertas/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO");
                    authorize.requestMatchers("/api/faturamentos/**", "/api/financeiro/**", "/api/relatorios/**")
                            .hasRole("ADMIN");
                    authorize.requestMatchers(HttpMethod.GET, "/api/contratos/opcoes-estoque")
                            .hasAnyRole("ADMIN", "ESTOQUE");
                    authorize.requestMatchers("/api/contratos/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO");
                    authorize.requestMatchers("/api/projetos/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO");
                    authorize.requestMatchers(HttpMethod.GET, "/api/ordens-servico/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "TECNICO");
                    authorize.requestMatchers(HttpMethod.PUT,
                            "/api/ordens-servico/*/status", "/api/ordens-servico/*/checklist")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "TECNICO");
                    authorize.requestMatchers("/api/ordens-servico/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO");
                    authorize.requestMatchers(HttpMethod.GET, "/api/atividades-padrao/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "TECNICO");
                    authorize.requestMatchers("/api/atividades-padrao/**").hasRole("ADMIN");
                    authorize.requestMatchers(HttpMethod.GET, "/api/ordens-retirada/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "TECNICO", "ESTOQUE", "AUDITOR");
                    authorize.requestMatchers(HttpMethod.GET, "/api/estoque/materiais")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "ESTOQUE");
                    authorize.requestMatchers("/api/estoque/**", "/api/ordens-retirada/**")
                            .hasAnyRole("ADMIN", "ESTOQUE");
                    authorize.requestMatchers("/api/as-built/**")
                            .hasAnyRole("ADMIN", "AUDITOR");
                    authorize.requestMatchers("/api/comarcas/*/as-built/**", "/api/comarcas/materiais/*/auditoria",
                            "/api/projetos/*/as-built/**")
                            .hasAnyRole("ADMIN", "AUDITOR");
                    authorize.requestMatchers(HttpMethod.GET,
                            "/api/comarcas/*/vistoria/foto", "/api/comarcas/*/virada-rede/prova")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "TECNICO", "AUDITOR");
                    authorize.requestMatchers(HttpMethod.GET, "/api/comarcas/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "TECNICO", "ESTOQUE", "AUDITOR");
                    authorize.requestMatchers("/api/comarcas/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "TECNICO");
                    authorize.requestMatchers("/api/campo/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "TECNICO");
                    authorize.requestMatchers(HttpMethod.GET, "/api/documentos-internos/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "TECNICO", "AUDITOR");
                    authorize.requestMatchers("/api/documentos-internos/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "TECNICO");
                    authorize.requestMatchers("/api/materiais/**", "/api/materiais-projeto/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "ESTOQUE");
                    authorize.requestMatchers("/api/pendencias-operacionais/**")
                            .hasAnyRole("ADMIN", "SUPERVISOR_TECNICO", "TECNICO", "ESTOQUE", "AUDITOR");
                    authorize.anyRequest().denyAll();
                })
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("SESSION", "JSESSIONID"))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"erro\":\"Autenticação necessária\"}");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            boolean csrfInvalido = exception instanceof MissingCsrfTokenException
                                    || exception instanceof InvalidCsrfTokenException;
                            String mensagem = csrfInvalido
                                    ? "Sessão de segurança expirada. Recarregue a página e tente novamente."
                                    : "Acesso não permitido para este perfil";
                            response.getWriter().write("{\"erro\":\"" + mensagem + "\"}");
                        }))
                .sessionManagement(session -> session
                        .sessionFixation(sessionFixation -> sessionFixation.migrateSession()))
                .addFilterAfter(validacaoSessaoFilter, SecurityContextHolderFilter.class);
        if (zohoEnabled) {
            http.oauth2Login(oauth2 -> oauth2
                    .successHandler((request, response, authentication) -> {
                        request.getSession().setAttribute(
                                com.poprc.demo.security.SessaoAutenticacaoService.METODO_AUTENTICACAO, "ZOHO");
                        request.getSession().setAttribute(
                                com.poprc.demo.security.SessaoAutenticacaoService.REAUTENTICADO_EM,
                                java.time.Instant.now());
                        response.sendRedirect(frontendUrl);
                    })
                    .failureHandler((request, response, exception) -> {
                        String codigo = exception instanceof OAuth2AuthenticationException oauthException
                                && "usuario_nao_autorizado".equals(oauthException.getError().getErrorCode())
                                ? "conta_nao_vinculada"
                                : "oauth";
                        log.warn("Falha no login Zoho ({}): {}", codigo, exception.getMessage());
                        response.sendRedirect(frontendUrl + "/login?error=" + codigo);
                    })
                    .userInfoEndpoint(userInfo -> userInfo.userService(zohoUserService())));
        }
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(allowedOriginPatterns);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of(
                "Content-Type", "Authorization", "X-Requested-With", "X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private OAuth2UserService<OAuth2UserRequest, OAuth2User> zohoUserService() {
        return request -> {
            Map<String, Object> dados;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> resposta = RestClient.create()
                        .get()
                        .uri(ZOHO_PROFILE_URL)
                        .header("Authorization", "Zoho-oauthtoken " + request.getAccessToken().getTokenValue())
                        .retrieve()
                        .body(Map.class);
                dados = resposta == null ? Map.of() : resposta;
            } catch (Exception exception) {
                log.warn("A API de perfil da Zoho recusou a consulta: {}", exception.getMessage());
                throw falhaOAuth("Não foi possível consultar o perfil autenticado na Zoho.");
            }

            String email = localizarPrimeiroTexto(
                    dados, "email", "Email", "email_id", "Email_ID", "emailAddress");
            if (email == null) {
                throw falhaOAuth("A Zoho não retornou o e-mail do usuário.");
            }
            Funcionario funcionario = funcionarioRepository.findByEmailIgnoreCase(email.trim())
                    .filter(item -> Boolean.TRUE.equals(item.getAtivo()))
                    .orElseThrow(() -> falhaOAuth("Usuário sem vínculo ativo no sistema."));
            return UsuarioAutenticado.de(funcionario);
        };
    }

    private String localizarPrimeiroTexto(Object valorAtual, String... chaves) {
        if (valorAtual instanceof Map<?, ?> mapa) {
            for (String chave : chaves) {
                Object valor = mapa.get(chave);
                if (valor != null && !valor.toString().isBlank()) {
                    return valor.toString().trim().toLowerCase(Locale.ROOT);
                }
            }
            for (Object valor : mapa.values()) {
                String encontrado = localizarPrimeiroTexto(valor, chaves);
                if (encontrado != null) return encontrado;
            }
        } else if (valorAtual instanceof List<?> lista) {
            for (Object valor : lista) {
                String encontrado = localizarPrimeiroTexto(valor, chaves);
                if (encontrado != null) return encontrado;
            }
        }
        return null;
    }

    private OAuth2AuthenticationException falhaOAuth(String mensagem) {
        return new OAuth2AuthenticationException(new OAuth2Error("usuario_nao_autorizado"), mensagem);
    }
}
