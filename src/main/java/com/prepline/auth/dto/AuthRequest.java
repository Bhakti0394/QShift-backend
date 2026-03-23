package com.prepline.auth.dto;

public class AuthRequest {
    public record Login(String email, String password) {}
    public record Register(String fullName, String email, String password) {}
}
