package org.sopt.solply_server.global.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class PrincipalDetails implements UserDetails {

    private final User user;

    private final SocialPlatform platform;

    public static PrincipalDetails from(User user) {
        return new PrincipalDetails(user, null);
    }

    public static PrincipalDetails from(User user, SocialPlatform platform) {
        return new PrincipalDetails(user, platform);
    }


    public Long getUserId() {
        return user.getId();
    }



    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() { // 유저 권한 정보 반환(미사용)
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
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