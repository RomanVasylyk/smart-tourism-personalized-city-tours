package com.example.smarttourism.data.remote.dto

data class RegisterRequest(
    val email: String,
    val password: String,
    val display_name: String?
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class AccountUserDto(
    val id: Int,
    val email: String,
    val display_name: String?
)

data class AuthResponseDto(
    val token: String,
    val user: AccountUserDto
)

data class LinkDeviceResponseDto(
    val linked_sessions: Int
)

data class StatusResponseDto(
    val ok: Boolean
)
