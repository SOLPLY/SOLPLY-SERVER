package org.sopt.solply_server.global.feign.oauth.kakao;

import org.sopt.solply_server.global.feign.oauth.kakao.dto.KakaoSocialUserProfile;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "kakaoApiClient", url = "https://kapi.kakao.com")
public interface KakaoServerClient {

    @PostMapping(value = "/v2/user/me")
    KakaoSocialUserProfile getUserInformation(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String accessToken
    );
}
