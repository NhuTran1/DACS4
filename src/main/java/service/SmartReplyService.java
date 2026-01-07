package service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * SmartReplyService - AI-powered quick reply suggestions
 * 
 * Features:
 * - Keyword-based suggestions
 * - Context-aware replies
 * - Vietnamese language support
 * - Configurable suggestion count
 */
public class SmartReplyService {
    
    private static final int DEFAULT_SUGGESTION_COUNT = 3;
    private static final int MAX_MESSAGE_LENGTH = 200; // Only analyze short messages
    
    // Keyword patterns with their suggested replies
    private final Map<Pattern, List<String>> replyPatterns = new HashMap<>();
    
    // Default generic replies
    private final List<String> genericReplies = Arrays.asList(
        "Ok 👍",
        "Được rồi",
        "Để mình xem lại nhé"
    );
    
    public SmartReplyService() {
        initializePatterns();
    }
    
    /**
     * Initialize keyword patterns and their corresponding replies
     */
    private void initializePatterns() {
        // Greetings
        addPattern("(?i).*(chào|hello|hi|hey|xin chào).*", Arrays.asList(
            "Chào bạn 👋",
            "Hi bạn!",
            "Chào, có gì giúp được không?"
        ));
        
        // Questions asking for confirmation
        addPattern("(?i).*(ok\\?|được không\\?|được ko\\?|có được không\\?|có ok không\\?).*", Arrays.asList(
            "Được nhé ✅",
            "Ok, không vấn đề gì",
            "Để mình kiểm tra"
        ));
        
        // Thank you
        addPattern("(?i).*(cảm ơn|thanks|thank you|cám ơn|tks).*", Arrays.asList(
            "Không có gì 😊",
            "Đừng khách sáo",
            "Luôn sẵn sàng giúp bạn"
        ));
        
        // Apologies
        addPattern("(?i).*(xin lỗi|sorry|lỗi|sai).*", Arrays.asList(
            "Không sao đâu",
            "Đừng bận tâm",
            "Mình hiểu mà"
        ));
        
        // Meeting/appointment related
        addPattern("(?i).*(họp|meeting|gặp|hẹn|lịch).*", Arrays.asList(
            "Mình sẽ sắp xếp",
            "Ok, ghi nhận rồi",
            "Để mình xem lịch nhé"
        ));
        
        // Questions
        addPattern("(?i).*(sao|tại sao|vì sao|why|how|như thế nào|thế nào).*\\?", Arrays.asList(
            "Để mình giải thích",
            "Mình sẽ gửi thêm chi tiết",
            "Bạn muốn biết gì cụ thể?"
        ));
        
        // Request for more info
        addPattern("(?i).*(chi tiết|details|thông tin|info|information).*", Arrays.asList(
            "Mình sẽ gửi thêm",
            "Ok, để mình tổng hợp",
            "Bạn cần gì cụ thể?"
        ));
        
        // Agreements
        addPattern("(?i).*(đồng ý|agree|ok|oke|okie|oki).*", Arrays.asList(
            "Tốt quá! 👍",
            "Perfect!",
            "Được rồi nhé"
        ));
        
        // Time-related
        addPattern("(?i).*(khi nào|when|bao giờ|lúc nào).*\\?", Arrays.asList(
            "Sớm nhất có thể",
            "Để mình xem và báo lại",
            "Trong hôm nay nhé"
        ));
        
        // Work/task related
        addPattern("(?i).*(làm|task|việc|work|job|công việc).*", Arrays.asList(
            "Đang làm rồi",
            "Sẽ hoàn thành sớm",
            "Mình đã ghi nhận"
        ));
        
        // Location
        addPattern("(?i).*(đâu|where|ở đâu|chỗ nào).*\\?", Arrays.asList(
            "Tại văn phòng",
            "Mình sẽ gửi địa chỉ",
            "Online nhé"
        ));
        
        // Urgent/important
        addPattern("(?i).*(gấp|urgent|quan trọng|important|khẩn).*", Arrays.asList(
            "Mình xử lý ngay",
            "Ưu tiên làm trước",
            "Hiểu rồi, mình làm ngay"
        ));
        
        // Help requests
        addPattern("(?i).*(giúp|help|hỗ trợ|support).*", Arrays.asList(
            "Bạn cần giúp gì?",
            "Mình ở đây",
            "Cứ nói mình nhé"
        ));
        
        // Files/documents
        addPattern("(?i).*(file|tài liệu|document|doc|pdf).*", Arrays.asList(
            "Mình sẽ gửi file",
            "Đang tìm và gửi",
            "Để mình kiểm tra file nhé"
        ));
    }
    
    /**
     * Add a pattern with its reply suggestions
     */
    private void addPattern(String regex, List<String> replies) {
        replyPatterns.put(Pattern.compile(regex), replies);
    }
    
    /**
     * Generate smart reply suggestions for a message
     * 
     * @param message The incoming message text
     * @return List of suggested replies (2-3 suggestions)
     */
    public List<String> generateSuggestions(String message) {
        if (message == null || message.trim().isEmpty()) {
            return Collections.emptyList();
        }
        
        // Only analyze short messages
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return genericReplies.subList(0, Math.min(2, genericReplies.size()));
        }
        
        String normalizedMessage = message.trim().toLowerCase();
        
        // Try to match patterns
        for (Map.Entry<Pattern, List<String>> entry : replyPatterns.entrySet()) {
            if (entry.getKey().matcher(normalizedMessage).matches()) {
                List<String> suggestions = entry.getValue();
                return suggestions.subList(0, Math.min(DEFAULT_SUGGESTION_COUNT, suggestions.size()));
            }
        }
        
        // Check for simple yes/no questions
        if (isYesNoQuestion(normalizedMessage)) {
            return Arrays.asList("Có", "Không", "Có thể");
        }
        
        // Return generic replies for unmatched messages
        return genericReplies.subList(0, Math.min(DEFAULT_SUGGESTION_COUNT, genericReplies.size()));
    }
    
    /**
     * Generate contextual suggestions based on conversation history
     * 
     * @param lastMessages Recent messages in the conversation (last 5)
     * @return List of contextual suggestions
     */
    public List<String> generateContextualSuggestions(List<String> lastMessages) {
        if (lastMessages == null || lastMessages.isEmpty()) {
            return genericReplies.subList(0, 2);
        }
        
        // Analyze last message (most recent)
        String lastMessage = lastMessages.get(lastMessages.size() - 1);
        return generateSuggestions(lastMessage);
    }
    
    /**
     * Check if message is a yes/no question
     */
    private boolean isYesNoQuestion(String message) {
        return message.matches("(?i).*(có|không|chưa|không\\?|chưa\\?).*") ||
               message.matches("(?i).*(is|are|do|does|can|could|would|should).*\\?");
    }
    
    /**
     * Get emoji-enhanced suggestions (optional feature)
     */
    public List<String> getEmojiSuggestions(String message) {
        List<String> baseSuggestions = generateSuggestions(message);
        List<String> enhanced = new ArrayList<>();
        
        for (String suggestion : baseSuggestions) {
            // Already has emoji
            if (containsEmoji(suggestion)) {
                enhanced.add(suggestion);
            } else {
                // Add appropriate emoji
                enhanced.add(addContextualEmoji(suggestion));
            }
        }
        
        return enhanced;
    }
    
    /**
     * Check if text contains emoji
     */
    private boolean containsEmoji(String text) {
        return text.matches(".*[\\p{So}\\p{Cn}].*");
    }
    
    /**
     * Add contextual emoji to suggestion
     */
    private String addContextualEmoji(String suggestion) {
        String lower = suggestion.toLowerCase();
        
        if (lower.contains("ok") || lower.contains("được")) {
            return suggestion + " 👍";
        } else if (lower.contains("chào") || lower.contains("hi")) {
            return suggestion + " 👋";
        } else if (lower.contains("cảm ơn") || lower.contains("thanks")) {
            return suggestion + " 😊";
        } else if (lower.contains("xin lỗi") || lower.contains("sorry")) {
            return suggestion + " 🙏";
        } else if (lower.contains("tốt") || lower.contains("perfect")) {
            return suggestion + " ✨";
        }
        
        return suggestion;
    }
    
    /**
     * Get custom suggestions based on user preferences
     * This can be extended to support user-defined quick replies
     */
    public List<String> getCustomSuggestions(Integer userId) {
        // TODO: Load from database if user has custom quick replies
        // For now, return generic
        return genericReplies;
    }
    
    /**
     * Learn from user's reply patterns (Future enhancement)
     * Can be used to improve suggestions over time
     */
    public void learnFromReply(String receivedMessage, String userReply) {
        // TODO: Implement machine learning to improve suggestions
        // Store patterns in database for future reference
    }
}