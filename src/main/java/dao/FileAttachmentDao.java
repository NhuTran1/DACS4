package dao;

import model.FileAttachment;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.Query;
import config.HibernateUtil;

import java.util.List;

/**
 * FileAttachmentDao - Quản lý file attachments trong database
 * 
 * Trách nhiệm:
 * - CRUD operations cho FileAttachment
 * - Query files theo conversation, user, fileId
 * - Tính toán storage usage
 */
public class FileAttachmentDao {

    /**
     * Lưu file attachment mới
     */
    public void save(FileAttachment attachment) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            session.save(attachment);
            tx.commit();
            
            System.out.println("✅ FileAttachment saved: " + attachment.getFileName());
        } catch (Exception e) {
            if (tx != null) tx.rollback();
            System.err.println("❌ Error saving FileAttachment: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Tìm file attachment theo fileId (unique)
     */
    public FileAttachment findByFileId(String fileId) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            String sql = """
                SELECT *
                FROM file_attachment
                WHERE file_id = :fileId
                LIMIT 1
                """;
            
            Query<FileAttachment> query = session.createNativeQuery(sql, FileAttachment.class);
            query.setParameter("fileId", fileId);
            
            List<FileAttachment> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            System.err.println("❌ Error finding FileAttachment by fileId: " + e.getMessage());
            return null;
        }
    }

    /**
     * Lấy tất cả files trong conversation
     */
    public List<FileAttachment> findByConversationId(Integer conversationId) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            String sql = """
                SELECT fa.*
                FROM file_attachment fa
                JOIN message m ON fa.message_id = m.id
                WHERE m.conversation_id = :conversationId
                ORDER BY fa.created_at DESC
                """;
            
            Query<FileAttachment> query = session.createNativeQuery(sql, FileAttachment.class);
            query.setParameter("conversationId", conversationId);
            
            return query.getResultList();
        } catch (Exception e) {
            System.err.println("❌ Error finding FileAttachments by conversationId: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Lấy tất cả files của user (sent)
     */
    public List<FileAttachment> findBySenderId(Integer senderId) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            String sql = """
                SELECT *
                FROM file_attachment
                WHERE sender_id = :senderId
                ORDER BY created_at DESC
                """;
            
            Query<FileAttachment> query = session.createNativeQuery(sql, FileAttachment.class);
            query.setParameter("senderId", senderId);
            
            return query.getResultList();
        } catch (Exception e) {
            System.err.println("❌ Error finding FileAttachments by senderId: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Tính tổng dung lượng file của user
     */
    public Long getTotalSizeByUser(Integer userId) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            String sql = """
                SELECT COALESCE(SUM(file_size), 0)
                FROM file_attachment
                WHERE sender_id = :userId
                """;
            
            Query<Number> query = session.createNativeQuery(sql);
            query.setParameter("userId", userId);
            
            Number result = query.uniqueResult();
            return result != null ? result.longValue() : 0L;
        } catch (Exception e) {
            System.err.println("❌ Error calculating total size: " + e.getMessage());
            return 0L;
        }
    }

    /**
     * Xóa file attachment (soft delete - chỉ xóa record, không xóa file thật)
     */
    public boolean delete(Integer id) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            
            FileAttachment attachment = session.get(FileAttachment.class, id);
            if (attachment != null) {
                session.delete(attachment);
                tx.commit();
                return true;
            }
            
            return false;
        } catch (Exception e) {
            if (tx != null) tx.rollback();
            System.err.println("❌ Error deleting FileAttachment: " + e.getMessage());
            return false;
        }
    }

    /**
     * Xóa file attachment theo fileId
     */
    public boolean deleteByFileId(String fileId) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            
            String sql = """
                DELETE FROM file_attachment
                WHERE file_id = :fileId
                """;
            
            Query<?> query = session.createNativeQuery(sql);
            query.setParameter("fileId", fileId);
            
            int rows = query.executeUpdate();
            tx.commit();
            
            return rows > 0;
        } catch (Exception e) {
            if (tx != null) tx.rollback();
            System.err.println("❌ Error deleting FileAttachment by fileId: " + e.getMessage());
            return false;
        }
    }

    /**
     * Count files by type (based on mime_type)
     */
    public Long countByMimeType(Integer conversationId, String mimeTypePrefix) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            String sql = """
                SELECT COUNT(*)
                FROM file_attachment fa
                JOIN message m ON fa.message_id = m.id
                WHERE m.conversation_id = :conversationId
                  AND fa.mime_type LIKE :mimeType
                """;
            
            Query<Number> query = session.createNativeQuery(sql);
            query.setParameter("conversationId", conversationId);
            query.setParameter("mimeType", mimeTypePrefix + "%");
            
            Number result = query.uniqueResult();
            return result != null ? result.longValue() : 0L;
        } catch (Exception e) {
            System.err.println("❌ Error counting by mime type: " + e.getMessage());
            return 0L;
        }
    }

    /**
     * Get recent files (limit)
     */
    public List<FileAttachment> getRecentFiles(Integer userId, int limit) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            String sql = """
                SELECT fa.*
                FROM file_attachment fa
                JOIN message m ON fa.message_id = m.id
                JOIN participant p ON m.conversation_id = p.conversation_id
                WHERE p.user_id = :userId
                ORDER BY fa.created_at DESC
                LIMIT :limit
                """;
            
            Query<FileAttachment> query = session.createNativeQuery(sql, FileAttachment.class);
            query.setParameter("userId", userId);
            query.setParameter("limit", limit);
            
            return query.getResultList();
        } catch (Exception e) {
            System.err.println("❌ Error getting recent files: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Search files by name
     */
    public List<FileAttachment> searchByFileName(Integer conversationId, String keyword) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            String sql = """
                SELECT fa.*
                FROM file_attachment fa
                JOIN message m ON fa.message_id = m.id
                WHERE m.conversation_id = :conversationId
                  AND fa.file_name LIKE :keyword
                ORDER BY fa.created_at DESC
                """;
            
            Query<FileAttachment> query = session.createNativeQuery(sql, FileAttachment.class);
            query.setParameter("conversationId", conversationId);
            query.setParameter("keyword", "%" + keyword + "%");
            
            return query.getResultList();
        } catch (Exception e) {
            System.err.println("❌ Error searching files: " + e.getMessage());
            return List.of();
        }
    }
}