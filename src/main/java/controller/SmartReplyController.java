package controller;

import service.SmartReplyService;
import service.ChatService;
import model.Message;

import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;


public class SmartReplyController {
    
    private final SmartReplyService smartReplyService;
    private final ChatService chatService;
    private final Integer currentUserId;
    
    // Callbacks
    private SuggestionsCallback suggestionsCallback;
    
    public interface SuggestionsCallback {
        void onSuggestionsGenerated(List<String> suggestions);
    }
    
    public SmartReplyController(ChatService chatService, Integer currentUserId) {
        this.smartReplyService = new SmartReplyService();
        this.chatService = chatService;
        this.currentUserId = currentUserId;
    }

    
    /**
     * Generate suggestions for a received message
     * 
     * @param message The incoming message
     * @return List of suggested replies
     */
    public List<String> generateSuggestions(Message message) {
        if (message == null || message.getContent() == null) {
            return new ArrayList<>();
        }
        
        // Don't generate suggestions for own messages
        if (message.getSender().getId().equals(currentUserId)) {
            return new ArrayList<>();
        }
        
        // Only generate for text messages
        if (message.getMessageType() != Message.MessageType.TEXT) {
            return new ArrayList<>();
        }
        
        String content = message.getContent();
        
        // Generate basic suggestions
        return smartReplyService.generateSuggestions(content);
    }
    
    /**
     * Generate contextual suggestions based on conversation history
     * 
     * @param conversationId The conversation ID
     * @param lastMessage The most recent message
     * @return List of contextual suggestions
     */
    public List<String> generateContextualSuggestions(Integer conversationId, Message lastMessage) {
        if (lastMessage == null) {
            return new ArrayList<>();
        }
        
        try {
            // Get recent messages for context
            List<Message> recentMessages = chatService.listMessages(conversationId);
            
            // Take last 5 messages for context
            int startIndex = Math.max(0, recentMessages.size() - 5);
            List<Message> contextMessages = recentMessages.subList(startIndex, recentMessages.size());
            
            // Extract message contents
            List<String> messageContents = new ArrayList<>();
            for (Message msg : contextMessages) {
                if (msg.getMessageType() == Message.MessageType.TEXT) {
                    messageContents.add(msg.getContent());
                }
            }
            
            // Generate contextual suggestions
            return smartReplyService.generateContextualSuggestions(messageContents);
            
        } catch (Exception e) {
            System.err.println("❌ Error generating contextual suggestions: " + e.getMessage());
            // Fallback to basic suggestions
            return smartReplyService.generateSuggestions(lastMessage.getContent());
        }
    }
    
    /**
     * Generate suggestions with emoji enhancements
     */
    public List<String> generateEmojiSuggestions(Message message) {
        if (message == null || message.getContent() == null) {
            return new ArrayList<>();
        }
        
        return smartReplyService.getEmojiSuggestions(message.getContent());
    }
    
    /**
     * Get user's custom quick replies (if any)
     */
    public List<String> getCustomQuickReplies() {
        return smartReplyService.getCustomSuggestions(currentUserId);
    }
    
    /**
     * Handle when user selects a suggestion
     * This can be used for learning patterns
     */
    public void onSuggestionSelected(Message receivedMessage, String selectedSuggestion) {
        try {
            // Learn from user's selection for future improvements
            smartReplyService.learnFromReply(
                receivedMessage.getContent(), 
                selectedSuggestion
            );
        } catch (Exception e) {
            System.err.println("⚠️ Error learning from suggestion: " + e.getMessage());
        }
    }
    
    /**
     * Generate suggestions asynchronously
     */
    public void generateSuggestionsAsync(Message message, Consumer<List<String>> callback) {
        new Thread(() -> {
            try {
                List<String> suggestions = generateSuggestions(message);
                if (callback != null) {
                    callback.accept(suggestions);
                }
            } catch (Exception e) {
                System.err.println("❌ Error generating async suggestions: " + e.getMessage());
                if (callback != null) {
                    callback.accept(new ArrayList<>());
                }
            }
        }, "smart-reply-generator").start();
    }
    
    /**
     * Generate contextual suggestions asynchronously
     */
    public void generateContextualSuggestionsAsync(Integer conversationId, 
                                                   Message lastMessage, 
                                                   Consumer<List<String>> callback) {
        new Thread(() -> {
            try {
                List<String> suggestions = generateContextualSuggestions(conversationId, lastMessage);
                if (callback != null) {
                    callback.accept(suggestions);
                }
            } catch (Exception e) {
                System.err.println("❌ Error generating async contextual suggestions: " + e.getMessage());
                if (callback != null) {
                    callback.accept(new ArrayList<>());
                }
            }
        }, "smart-reply-contextual").start();
    }
    
    /**
     * Set callback for suggestions
     */
    public void setSuggestionsCallback(SuggestionsCallback callback) {
        this.suggestionsCallback = callback;
    }
    
    /**
     * Notify callback with suggestions
     */
    private void notifySuggestions(List<String> suggestions) {
        if (suggestionsCallback != null && suggestions != null && !suggestions.isEmpty()) {
            suggestionsCallback.onSuggestionsGenerated(suggestions);
        }
    }
    
    /**
     * Check if suggestions should be shown for a message
     * 
     * @param message The message to check
     * @return true if suggestions should be shown
     */
    public boolean shouldShowSuggestions(Message message) {
        if (message == null) return false;
        
        // Don't show for own messages
        if (message.getSender().getId().equals(currentUserId)) {
            return false;
        }
        
        // Only show for text messages
        if (message.getMessageType() != Message.MessageType.TEXT) {
            return false;
        }
        
        // Don't show for very long messages
        if (message.getContent() != null && message.getContent().length() > 200) {
            return false;
        }
        
        return true;
    }
}
