package org.sopt.solply_server.domain.auth.service.oauth.google;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.URI;
import java.net.URL;
import java.util.Set;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.service.oauth.IdTokenProvider;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GoogleIdTokenProvider implements IdTokenProvider {

    private static final Set<String> ISSUERS = Set.of(
            "https://accounts.google.com",
            "accounts.google.com"
    );

    private static final String JWK_SET_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private final JWKSource<SecurityContext> keySource;

    @Value("${oauth.google.client-id}")
    private String clientId;

    public GoogleIdTokenProvider() {
        try {
            URL jwkSetUrl = URI.create(JWK_SET_URL).toURL();

            DefaultResourceRetriever resourceRetriever = new DefaultResourceRetriever(
                    2000,
                    2000,
                    1024 * 1024
            );

            this.keySource = new RemoteJWKSet<>(jwkSetUrl, resourceRetriever);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize GoogleIdTokenProvider", e);
        }
    }

    @Override
    public SocialPlatform platform() {
        return SocialPlatform.GOOGLE;
    }

    @Override
    public Payload parseAndValidate(String idToken) {
        try {
            DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
            JWSKeySelector<SecurityContext> keySelector =
                    new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);
            jwtProcessor.setJWSKeySelector(keySelector);

            JWTClaimsSet claimsSet = jwtProcessor.process(idToken, null);

            // iss 검증
            String issuer = claimsSet.getIssuer();
            if (issuer == null || !ISSUERS.contains(issuer)) {
                throw new BusinessException(ErrorCode.GOOGLE_INVALID_ISSUER);
            }

            // aud 검증
            var audience = claimsSet.getAudience();
            if (audience == null || !audience.contains(clientId)) {
                throw new BusinessException(ErrorCode.INVALID_AUDIENCE);
            }

            // exp 확인
            if (claimsSet.getExpirationTime() == null ||
                    claimsSet.getExpirationTime().getTime() < System.currentTimeMillis()) {
                throw new BusinessException(ErrorCode.EXPIRED_TOKEN);
            }

            String sub = claimsSet.getSubject();
            String email = claimsSet.getStringClaim("email");

            return new Payload(sub, email);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
    }
}