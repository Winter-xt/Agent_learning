package com.ai.project.ai_project.event;

public class ChatMemoryPersistEvent {

    private final String memoryId;
    private final String messagesJson;
    private final boolean delete;

    public ChatMemoryPersistEvent(String memoryId, String messagesJson, boolean delete) {
        this.memoryId = memoryId;
        this.messagesJson = messagesJson;
        this.delete = delete;
    }

    public String getMemoryId() {
        return memoryId;
    }

    public String getMessagesJson() {
        return messagesJson;
    }

    public boolean isDelete() {
        return delete;
    }
}
