package com.linda.gateway.util;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

public class JwtTokenProvider {

	private final SecretKey key;
	private final long expirationMillis;

	public JwtTokenProvider(String secret, long expirationMillis) {
		this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		this.expirationMillis = expirationMillis;
	}

	public String generateToken(String userId, String username, List<String> roles) {
		long now = System.currentTimeMillis();
		Date expiryDate = new Date(now + expirationMillis);

		return Jwts.builder().subject(username).claim("userId", userId).claim("roles", roles).issuedAt(new Date(now))
				.expiration(expiryDate).signWith(key, Jwts.SIG.HS256).compact();
	}
}
