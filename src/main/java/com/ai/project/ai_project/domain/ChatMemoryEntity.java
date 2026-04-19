package com.ai.project.ai_project.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("chat_memory")
public class ChatMemoryEntity {

    @TableId
    private String id;
    @TableField("category")
    private String category;
    private String messages;

    public ChatMemoryEntity() {
    }

    public ChatMemoryEntity(String id, String category, String messages) {
        this.id = id;
        this.category = category;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }
}
