package kr.co.morymaker.api.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Resource Server 배선 — auth(인증 서버)가 발급한 JWT를 검증하고 `roles`/`authorities` 클레임을
 * Spring Security 권한으로 승격한다. `event_ids` 클레임은 여기서 권한으로 변환하지 않는다
 * (행사 스코프 검증은 별도 계층의 몫 — 이번 범위에는 포함하지 않는다).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfig(
    private val authenticationEntryPoint: RestAuthenticationEntryPoint,
    private val accessDeniedHandler: RestAccessDeniedHandler,
) {

    @Value("\${morymaker.auth.issuer}")
    private lateinit var issuer: String

    @Value("\${morymaker.auth.jwk-set-uri}")
    private lateinit var jwkSetUri: String

    @Value("\${morymaker.web.allowed-origins}")
    private lateinit var allowedOrigins: String

    // JWKS는 지연 조회이므로 이 빈 생성 자체는 auth 서버 가용성에 결합되지 않는다 —
    // 최초 JWT 검증 요청이 들어오는 시점에야 실제로 JWKS를 페치한다.
    @Bean
    fun jwtDecoder(): JwtDecoder {
        val decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
        decoder.setJwtValidator(issuerTimestampValidator(issuer))
        return decoder
    }

    companion object {
        /**
         * 프로덕션 JwtDecoder가 사용하는 validator 합성의 단일 정의.
         *
         * Boot 자동구성이 만드는 기본 decoder는 issuer 검증이 없어 커스텀 decoder가 불가피한데,
         * 커스텀 decoder를 직접 만들면 자동구성이 넣어주던 만료 시각(timestamp) 검증이
         * 함께 사라지는 회귀가 나기 쉽다 — 그래서 timestamp validator를 명시적으로 다시 합성한다.
         * 테스트가 이 함수를 직접 호출해 실 프로덕션 구성 경로를 검증한다.
         */
        fun issuerTimestampValidator(issuer: String): DelegatingOAuth2TokenValidator<Jwt> =
            DelegatingOAuth2TokenValidator(
                JwtTimestampValidator(),
                JwtIssuerValidator(issuer),
            )

        /**
         * JWT 클레임 → Spring Security 권한 변환의 단일 정의.
         *
         * `roles`는 `ROLE_` 접두사를 붙여 `hasRole`/`hasAnyRole` SpEL에서 쓰고,
         * `authorities`(세밀 permission 코드, 현재는 항상 빈 배열)는 접두사 없이 그대로 매핑한다.
         * 클레임 자체가 없으면 `getClaimAsStringList`가 null을 반환하므로 `orEmpty()`로 빈 권한에
         * 수렴한다. 클레임은 있으나 문자열 배열이 아닌 형태로 손상된 경우(단일 값·다른 타입)는
         * null이 아니라 그 값을 그대로 감싼 단일 원소 목록이 반환될 수 있다 — 이 경우 무의미한
         * 권한 문자열이 생성되지만 실제 역할 값과는 절대 일치하지 않으므로 인가 체크에서 자연히
         * 거부된다(경로는 다르지만 결과는 동일하게 fail-closed).
         */
        fun grantedAuthorities(jwt: Jwt): Collection<GrantedAuthority> {
            val roles = jwt.getClaimAsStringList("roles").orEmpty()
                .map { SimpleGrantedAuthority("ROLE_$it") }
            val authorities = jwt.getClaimAsStringList("authorities").orEmpty()
                .map { SimpleGrantedAuthority(it) }
            return roles + authorities
        }
    }

    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter { jwt -> grantedAuthorities(jwt) }
        return converter
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val origins = allowedOrigins.split(",").map { it.trim() }
        val config = CorsConfiguration().apply {
            allowedOrigins = origins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "Accept", "X-Requested-With")
            allowCredentials = true
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", config)
        }
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/public/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { it.accessDeniedHandler(accessDeniedHandler) }
            .oauth2ResourceServer { rs ->
                rs.authenticationEntryPoint(authenticationEntryPoint)
                rs.accessDeniedHandler(accessDeniedHandler)
                rs.jwt { jwt ->
                    jwt.decoder(jwtDecoder())
                    jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
                }
            }
        return http.build()
    }
}
