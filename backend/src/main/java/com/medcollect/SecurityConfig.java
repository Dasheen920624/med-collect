package com.medcollect;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class SecurityConfig {
    @Bean
    BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain security(HttpSecurity http, UserAccounts users) throws Exception {
        return http
                .headers(headers -> headers.frameOptions(frame -> frame.disable()).addHeaderWriter((request, response) -> {
                    // The original-file controller opts in to same-origin framing for inline PDFs.
                    if (!response.containsHeader("X-Frame-Options")) response.setHeader("X-Frame-Options", "DENY");
                }))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.svg", "/api/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/register").permitAll()
                        .requestMatchers("/api/users", "/api/users/**", "/api/settings/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/sources/**", "/api/tasks/**", "/api/classifications",
                                "/api/business-catalog/refresh", "/api/business-literature/*/collect", "/api/plans/**", "/api/materials/upload", "/api/runs/*/capture").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/sources/**", "/api/tasks/**",
                                "/api/classifications/**", "/api/template-profiles/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .formLogin(login -> login.loginPage("/").loginProcessingUrl("/api/login")
                        .successHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .failureHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_UNAUTHORIZED))
                        .permitAll())
                .logout(logout -> logout.logoutUrl("/api/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (req, res, error) -> res.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
                .requestCache(cache -> cache.disable())
                .addFilterBefore(new AccountSessionFilter(users), AuthorizationFilter.class)
                .build();
    }

    private static class AccountSessionFilter extends OncePerRequestFilter {
        private final UserAccounts users;
        private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();

        AccountSessionFilter(UserAccounts users) { this.users = users; }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof UserAccounts.AccountPrincipal account
                    && !users.isCurrent(account)) {
                logout.logout(request, response, authentication);
            }
            chain.doFilter(request, response);
        }
    }
}
