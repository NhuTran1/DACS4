package service;

import java.util.List;

import dao.UserDao;
import model.Users;

public class UserService {
	private UserDao userDao = new UserDao();
	
	//1.Quan li User
		public Users getUserById(Long id) {
			return userDao.findById(id);
		}
		
		public Users getUserByUserName(String username) {
			return userDao.findByUsername(username);
		}
		
//		public boolean registerUser(Users user) {
//			if(userDao.findByUsername(user.getUsername()) != null) {
//				return false;
//			}
//			
//			userDao.save(user);
//			return true;
//		}
		
		public boolean updateUserDisplayName(Long userId, String newName) {
	        Users user = userDao.findById(userId);
	        if (user == null) return false;

	        if (newName == null || newName.trim().isEmpty()) return false;

	        user.setDisplayName(newName.trim());
	        userDao.update(user);
	        return true;
	    }
		
		public List<Users> searchUser(String keyword){
			return userDao.searchUser(keyword);
		}
		
}
