package controller;

import dao.UserDao;
import model.Users;
import service.AuthService;

public class LoginController {
	private final AuthService authService = new AuthService();
    private final UserDao userDao = new UserDao();
    
    public Users login(String username, String password) {
		if(username == null || username.isEmpty() || password == null || password.isEmpty()) {
			throw new IllegalArgumentException("Please fill in all fields");
		}
		
		String token = authService.login(username, password);
		if(token == null) {
			return null;
		}
		
		return userDao.findByUsername(username);
	}
}
