package org.sopt.solply_server.domain.auth.service;

import org.sopt.solply_server.domain.auth.constant.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.User;

public interface OAuthService {

    boolean support(SocialPlatform socialPlatform);

    User socialLogin(String oauthAccessToken);

}
