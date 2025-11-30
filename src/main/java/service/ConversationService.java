package service;

import java.util.List;

import dao.ConversationDao;
import model.Conversation;
import model.Users;

public class ConversationService {
	private ConversationDao conversationDao = new ConversationDao();
	
	//3. Quan li cuoc hoi thoai
	// Lấy conversation theo id
    public Conversation getConversationById(Long conversationId) {
        return conversationDao.getConversation(conversationId);
    }
    
    public List<Conversation> listConversationsByUser(Long userId) {
        return conversationDao.listByUser(userId);
    }
    
 // Kiểm tra đã có direct conversation giữa 2 user chưa
    public Conversation getDirectConversation(Long userAId, Long userBId) {
        return conversationDao.getDirectConversation(userAId, userBId);
    }
    
 // Tạo direct conversation mới (nếu chưa có)
    public Conversation createDirectConversation(Long userAId, Long userBId) {
        return conversationDao.createDirectConversation(userAId, userBId);
    }
    
 // Lấy danh sách participants trong conversation
    public List<Users> listParticipants(Long conversationId) {
        return conversationDao.listParticipants(conversationId);
    }
    
    // Cập nhật tên nhóm (chỉ group conversation)
    public boolean updateConversationName(Long conversationId, String newName, Long requestUserId) {
        return conversationDao.updateConversation(conversationId, newName, requestUserId);
    }
    
    // public boolean deleteConversation(Long conversationId, Long requestUserId) {
    //     return conversationDao.deleteConversation(conversationId, requestUserId);
    // }
    
    //
}
