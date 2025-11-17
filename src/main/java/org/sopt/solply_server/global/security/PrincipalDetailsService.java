package org.sopt.solply_server.global.security;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PrincipalDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return PrincipalDetails.from(
                userRepository.findById(Long.parseLong(username))
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER)));
    }

    public PrincipalDetails loadUserWithPlatform(Long userId, SocialPlatform platform) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER));
        return PrincipalDetails.from(user, platform);
    }

}