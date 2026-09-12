package org.sopt.solply_server.global.security;

import java.util.Collection;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserRole;
import org.sopt.solply_server.global.jwt.dto.AccessTokenPayload;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 인증 주체. 쓰이는 경로는 게터 셋뿐이다 — {@code getUserId()}({@code @CurrentUserId}),
 * {@code getAuthorities()}({@code hasRole("ADMIN")}), {@code getFamilyId()}
 * ({@code @CurrentTokenFamilyId}, 로그아웃이 끊을 계열).
 *
 * <p><b>그래서 User 엔티티가 필요 없다.</b> 세 값이 전부 access 토큰의 클레임에 있고,
 * {@link #ofAccessToken}이 유일한 인증 경로다. {@link #from}은 {@code PrincipalDetailsService}
 * (스프링 시큐리티가 요구하는 {@code UserDetailsService} 구현)만 쓰는 자리로 남는다 —
 * <b>인증 필터는 더 이상 DB로 폴백하지 않는다.</b>
 *
 * <p>{@code getUser()}·{@code getFamilyId()}는 서로 다른 경로에서 {@code null}이다. 엔티티
 * 경로에는 계열이 없고, 토큰 경로에는 엔티티가 없다. 유저 정보가 필요하면 서비스 계층에서
 * userId로 조회할 것.
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class PrincipalDetails implements UserDetails {

    /** 토큰 경로에서는 {@code null}이다 — 위 클래스 주석 참조 */
    private final User user;

    private final Long userId;

    private final UserRole role;

    private final SocialPlatform platform;

    /** 이 access를 낸 로그인(계열). 엔티티 경로에서는 {@code null}이다 */
    private final String familyId;

    /** 기본이자 유일한 인증 경로 — 토큰 클레임만으로 주체를 세운다. DB를 타지 않는다. */
    public static PrincipalDetails ofAccessToken(AccessTokenPayload payload) {
        return new PrincipalDetails(
                null, payload.userId(), payload.role(), payload.platform(), payload.familyId());
    }

    /** {@code UserDetailsService} 계약용. 인증 필터는 쓰지 않는다. */
    public static PrincipalDetails from(User user) {
        return new PrincipalDetails(user, user.getId(), user.getRole(), null, null);
    }

    /** {@code UserDetailsService} 계약용. 인증 필터는 쓰지 않는다. */
    public static PrincipalDetails from(User user, SocialPlatform platform) {
        return new PrincipalDetails(user, user.getId(), user.getRole(), platform, null);
    }

    /**
     * 권한 문자열은 두 경로에서 완전히 같다 — {@code "ROLE_" + role.name()}이고, 값의 출처가
     * 엔티티냐 클레임이냐만 다르다. {@code SecurityConfig}의 {@code hasRole("ADMIN")}이
     * 요구하는 {@code ROLE_ADMIN}이 그대로 만들어진다.
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return null;
    }

    @Override
    public boolean isAccountNonExpired() { // 계정 만료 기능(미사용)
        return true;
    }

    @Override
    public boolean isAccountNonLocked() { // 정지 상태 체크(미사용)
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() { // 자격 증명 만료 기능(미사용)
        return true;
    }

    @Override
    public boolean isEnabled() { // 유저 활성화 상태 체크(미사용)
        return true;
    }
}
