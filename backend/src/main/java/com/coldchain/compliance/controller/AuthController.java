package com.coldchain.compliance.controller;

import com.coldchain.compliance.entity.AppUser;
import com.coldchain.compliance.repository.AppUserRepository;
import com.coldchain.compliance.security.JwtUtil;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 简单登录（演示用）：用户名 + 密码 → JWT。
 * 默认密码 password123（BCrypt 哈希见 V2 seed）。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AppUserRepository userRepo;
    private final JwtUtil jwtUtil;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        AppUser u = userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!Boolean.TRUE.equals(u.getEnabled())) {
            throw new IllegalArgumentException("用户已禁用");
        }
        if (!BCrypt.checkpw(password, u.getPasswordHash())) {
            throw new IllegalArgumentException("密码错误");
        }
        String token = jwtUtil.generate(u.getUsername(), u.getRole());
        Map<String, Object> r = new HashMap<>();
        r.put("token", token);
        r.put("username", u.getUsername());
        r.put("role", u.getRole());
        r.put("fullName", u.getFullName());
        return r;
    }
}
