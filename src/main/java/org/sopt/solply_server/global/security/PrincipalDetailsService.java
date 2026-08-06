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

/**
 * 인증 주체 로딩 — <b>요청당 1회, 트랜잭션 없이</b> 유저 1행을 읽는다.
 *
 * <p>{@code UserRepository#findById} 대신 {@code findForAuthentication}을 쓴다. 이유는 그쪽
 * javadoc에 있다: 필터 단계라 바깥 트랜잭션이 없어 {@code findById}의 클래스 레벨
 * {@code readOnly} 트랜잭션이 요청마다 새로 열렸고, 그 개폐에 드는 제어 문장 5개가
 * 단건 읽기를 위해 지불되고 있었다(2026-08-03 digest 실측).
 *
 * <p>여기서 얻은 {@code User}는 준영속이다 — {@code PrincipalDetails}는 이 엔티티를 읽기만 하고
 * (id·role) 지연 연관도 없으므로 준영속이어도 아무것도 달라지지 않는다. 반대로 이 엔티티를
 * 변경해 반영되기를 기대하는 코드를 나중에 붙이면 조용히 무시된다 — 그런 변경은 서비스
 * 트랜잭션 안에서 다시 조회해 하는 것이 맞다.
 */
@Service
@RequiredArgsConstructor
public class PrincipalDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return PrincipalDetails.from(loadActiveUser(Long.parseLong(username)));
    }

    public PrincipalDetails loadUserWithPlatform(Long userId, SocialPlatform platform) {
        return PrincipalDetails.from(loadActiveUser(userId), platform);
    }

    /**
     * 소프트 삭제되지 않은 유저 1행. 없으면 기존과 동일하게 {@code NOT_FOUND_USER}다 —
     * "행이 없음"과 "탈퇴함"을 구분하지 않는 것은 의도된 기존 동작이고, 필터가
     * {@code @Where(is_deleted = false)}에 그 판정을 맡기는 구조도 그대로다.
     */
    private User loadActiveUser(Long userId) {
        return userRepository.findForAuthentication(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER));
    }

}