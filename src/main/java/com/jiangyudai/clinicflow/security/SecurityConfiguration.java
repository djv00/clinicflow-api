package com.jiangyudai.clinicflow.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(SecurityProperties properties, PasswordEncoder encoder,
            @Value("${clinicflow.security.viewer.username:viewer}") String viewerUsername,
            @Value("${clinicflow.security.viewer.password:}") String viewerPassword) {
        var account = properties.getUser();
        if (account.isPasswordGenerated()) {
            log.warn("Using generated development password for {}: {}. Configure SPRING_SECURITY_USER_PASSWORD before deployment.",
                    account.getName(), account.getPassword());
        }
        var users = new InMemoryUserDetailsManager(User.withUsername(account.getName())
                .password(encoder.encode(account.getPassword())).roles("OPERATOR").build());
        if (StringUtils.hasText(viewerPassword)) {
            Assert.hasText(viewerUsername, "Viewer username is required when a viewer password is configured");
            Assert.isTrue(!viewerUsername.equalsIgnoreCase(account.getName()),
                    "Viewer and operator usernames must be different");
            users.createUser(User.withUsername(viewerUsername)
                    .password(encoder.encode(viewerPassword)).roles("VIEWER").build());
        }
        return users;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper mapper) throws Exception {
        var loginEntryPoint = new LoginUrlAuthenticationEntryPoint("/login.html");
        var api = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        http.authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/login.html", "/login.js", "/session.js", "/patients.css",
                                "/api/auth/csrf", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole("VIEWER", "OPERATOR")
                        .requestMatchers(HttpMethod.HEAD, "/api/v1/**").hasAnyRole("VIEWER", "OPERATOR")
                        .requestMatchers("/api/v1/**").hasRole("OPERATOR")
                        .anyRequest().authenticated())
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .formLogin(form -> form.loginPage("/login.html").loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> response.setStatus(204))
                        .failureHandler((request, response, exception) -> writeProblem(mapper, response, 401,
                                "Sign-in failed", "Username or password is incorrect."))
                        .permitAll())
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            if (api.matches(request)) {
                                writeProblem(mapper, response, 401, "Authentication required", "Sign in to continue.");
                            } else {
                                loginEntryPoint.commence(request, response, exception);
                            }
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            if (exception instanceof CsrfException) {
                                writeProblem(mapper, response, 403, "Request not allowed",
                                        "Your session or security token is no longer valid. Reload the page and try again.");
                            } else {
                                writeProblem(mapper, response, 403, "Access denied",
                                        "Your account does not have permission to perform this action.");
                            }
                        }));
        return http.build();
    }

    private static void writeProblem(JsonMapper mapper, HttpServletResponse response, int status,
                                     String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        mapper.writeValue(response.getOutputStream(), Map.of(
                "type", "about:blank", "status", status, "title", title, "detail", detail));
    }
}
