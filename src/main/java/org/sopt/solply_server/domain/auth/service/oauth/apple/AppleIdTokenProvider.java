package org.sopt.solply_server.domain.auth.service.oauth.apple;

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
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.service.oauth.IdTokenProvider;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AppleIdTokenProvider implements IdTokenProvider {

    private static final String ISSUER = "https://appleid.apple.com";
    private static final String JWK_SET_URL = "https://appleid.apple.com/auth/keys";
    private final JWKSource<SecurityContext> keySource;

    @Value("${oauth.apple.client-id}")
    private String clientId;

    public AppleIdTokenProvider() {
        try {
            URL jwkSetUrl = URI.create(JWK_SET_URL).toURL();

            DefaultResourceRetriever resourceRetriever = new DefaultResourceRetriever(
                    2000,  // connect timeout (ms)
                    2000,  // read timeout (ms)
                    1024 * 1024 // size limit (1MB)
            );

            this.keySource = new RemoteJWKSet<>(jwkSetUrl, resourceRetriever);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize ApplePublicKeyProvider", e);
        }
    }

    @Override
    public SocialPlatform platform() {
        return SocialPlatform.APPLE;
    }

    @Override
    public Payload parseAndValidate(String idToken) {
        try {
            DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
            JWSKeySelector<SecurityContext> keySelector =
                    new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);
            jwtProcessor.setJWSKeySelector(keySelector);

            JWTClaimsSet claimsSet = jwtProcessor.process(idToken, null);

            if (!ISSUER.equals(claimsSet.getIssuer())) {
                throw new BusinessException(ErrorCode.APPLE_INVALID_ISSUER);
            }

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