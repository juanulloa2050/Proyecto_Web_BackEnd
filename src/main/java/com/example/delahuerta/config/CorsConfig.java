package com.example.delahuerta.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Permitir tu dominio de GitHub Pages
        configuration.setAllowedOrigins(Arrays.asList(
            "https://juanulloa2050.github.io",  // Tu GitHub Pages
            "http://localhost:5500",             // Para desarrollo local
            "http://127.0.0.1:5500",            // Para desarrollo local
            "http://localhost:8080",            // Para pruebas locales
            "http://127.0.0.1:8080"             // Para pruebas locales
        ));
        
        // También puedes usar allowedOriginPatterns para dominios dinámicos
        // configuration.setAllowedOriginPatterns(Arrays.asList("https://*.github.io"));
        
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L); // Cache preflight por 1 hora
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        
        return source;
    }
}