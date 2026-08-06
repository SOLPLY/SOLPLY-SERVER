package org.sopt.solply_server.global.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 인증 주체. 이 클래스가 실제로 쓰이는 경로는 두 게터뿐이다 —
 * {@code getUserId()}(컨트롤러의 {@code @CurrentUserId})와 {@code getAuthorities()}
 * (스프링 시큐리티의 {@code hasRole("ADMIN")}).
 *
 * <p><b>그래서 User 엔티티가 필수가 아니다 (2026-08-03).</b> 필요한 값은 id와 role 둘이고,
 * 그 둘은 access 토큰의 클레임에 있다. {@link #ofClaims}가 엔티티 없이 주체를 세우는 경로이며,
 * 인증 필터의 기본 경로가 그것이다. {@link #from}은 role 클레임이 없는 옛 토큰을 위한
 * DB 폴백 경로로 남는다 — 자세한 사정은 {@code JwtAuthenticationFilter} javadoc.
 *
 * <p><b>{@code getUser()}는 클레임 경로에서 {@code null}이다.</b> 지금 외부 사용처가 없어
 * (2026-08-03 확인) 문제가 없지만, 여기서 엔티티를 꺼내 쓰기 시작하면 그 코드는 토큰 종류에
 * 따라 갈린다. 유저 정보가 필요하면 서비스 계층에서 userId로 조회할 것.
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class PrincipalDetails implements UserDetails {

    /** 클레임만으로 세운 주체에서는 {@code null}이다 — 위 클래스 주석 참조 */
    private final User user;

    private final Long userId;

    private final UserRole role;

    private final SocialPlatform platform;

    /** DB 폴백 경로 (role 클레임 없는 옛 토큰) */
    public static PrincipalDetails from(User user) {
        return new PrincipalDetails(user, user.getId(), user.getRole(), null);
    }

    /** DB 폴백 경로 (role 클레임 없는 옛 토큰) */
    public static PrincipalDetails from(User user, SocialPlatform platform) {
        return new PrincipalDetails(user, user.getId(), user.getRole(), platform);
    }

    /** 기본 경로 — 토큰 클레임만으로 주체를 세운다. DB를 타지 않는다. */
    public static PrincipalDetails ofClaims(Long userId, UserRole role, SocialPlatform platform) {
        return new PrincipalDetails(null, userId, role, platform);
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
    public boolean isEnabled() { // 유저 활성화 상태 체크(미사용
        return true;
    }
}