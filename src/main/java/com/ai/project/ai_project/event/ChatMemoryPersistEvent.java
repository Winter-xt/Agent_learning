package com.ai.project.ai_project.event;

public class ChatMemoryPersistEvent {

    private final String memoryId;
    private final String category;
    private final String messagesJson;
    private final boolean delete;

    public ChatMemoryPersistEvent(String memoryId, String category, String messagesJson, boolean delete) {
        this.memoryId = memoryId;
        this.category = category;
        this.messagesJson = messagesJson;
        this.delete = delete;
    }

    public String getMemoryId() {
        return memoryId;
    }

    public String getMessagesJson() {
        return messagesJson;
    }

    public String getCategory() {
        return category;
    }

    public boolean isDelete() {
        return delete;
    }
}
