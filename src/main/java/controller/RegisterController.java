package controller;

import service.AuthService;

public class RegisterController {
	AuthService authService = new AuthService();
	
	public boolean register(String username, String displayName, String email, String password, String confirm) {
		 if (username == null || username.trim().isEmpty()) {
	            throw new IllegalArgumentException("Username is required");
	        }
	        if (displayName == null || displayName.trim().isEmpty()) {
	            throw new IllegalArgumentException("Display name is required");
	        }
	        if (email == null || email.trim().isEmpty()) {
	            throw new IllegalArgumentException("Email is required");
	        }
	        if (password == null || password.isEmpty()) {
	            throw new IllegalArgumentException("Password is required");
	        }
	        if (!password.equals(confirm)) {
	            throw new IllegalArgumentException("Passwords do not match");
	        }

	        return authService.register(username.trim(), displayName.trim(), password, email.trim());
	}
}
