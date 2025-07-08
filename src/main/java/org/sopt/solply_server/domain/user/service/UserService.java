package org.sopt.solply_server.domain.user.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.constant.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.SocialUserInfo;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.SocialUserInfoRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {


}