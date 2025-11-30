package service;

import java.util.List;

import dao.FriendDao;
import model.FriendRequest;
import model.Users;

public class FriendService {
	private FriendDao friendDao = new FriendDao();

	//2.Quan li ban be
	public void sendFriendRequest(Long fromUserId, Long toUserId) {
		friendDao.sendFriendRequest(fromUserId, toUserId);
	}
	
	public void acceptFriendRequest(Long requestId) {
		friendDao.acceptFriend(requestId);
	}
	
	public void denyFriendRequest(Long requestId) {
		friendDao.denyFriendRequest(requestId);
	}
	
	public List<FriendRequest> listFriendRequest(Long userId){
		return friendDao.listFriendRequest(userId);
	}
	
	public List<Users> listFriends(Long userId) {
	    return friendDao.listFriend(userId);
	}
}
