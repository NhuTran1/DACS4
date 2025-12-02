package service;

import java.util.List;

import dao.FriendDao;
import model.FriendRequest;
import model.Users;

public class FriendService {
	private FriendDao friendDao = new FriendDao();

	//2.Quan li ban be
	public void sendFriendRequest(Integer fromUserId, Integer toUserId) {
		friendDao.sendFriendRequest(fromUserId, toUserId);
	}
	
	public void acceptFriendRequest(Integer requestId) {
		friendDao.acceptFriend(requestId);
	}
	
	public void denyFriendRequest(Integer requestId) {
		friendDao.denyFriendRequest(requestId);
	}
	
	public List<FriendRequest> listFriendRequest(Integer userId){
		return friendDao.listFriendRequest(userId);
	}
	
	public List<Users> listFriends(Integer userId) {
	    return friendDao.listFriend(userId);
	}
}
