package com.ai.project.ai_project.test.rag.db;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("chat_memory")
public class ChatMemoryEntity {

    @TableId
    private String id;
    private String messages;

    public ChatMemoryEntity() {
    }

    public ChatMemoryEntity(String id, String messages) {
        this.id = id;
        this.messages = messages;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMessages() {
        return messages;
    }

    public void setMessages(String messages) {
        this.messages = messages;
    }
}
