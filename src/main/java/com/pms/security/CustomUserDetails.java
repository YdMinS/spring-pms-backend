package com.pms.security;

import com.pms.domain.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * {@link UserDetails} implementation carrying the authenticated user's {@code tenantId}.
 *
 * <p>This is the single principal type used across the app so the tenant id flows from login
 * into the JWT (see {@link JwtTokenProvider#generateToken}). {@link CustomUserDetailsService}
 * returns this type, and {@code AuthServiceImpl} builds its {@code Authentication} with it —
 * keep both in sync if the principal shape changes.</p>
 */
public class CustomUserDetails implements UserDetails {

    private final Long tenantId;
    private final String email;
    private final String password;
    private final Collection<? extends GrantedAuthority> authorities;

    public CustomUserDetails(User user) {
        this.tenantId = user.getTenantId();
        this.email = user.getEmail();
        this.password = user.getPassword();
        this.authorities = List.of(
                new SimpleGrantedAuthority("ROLE_" + user.getRole().getKey()));
    }

    public Long getTenantId() {
        return tenantId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
