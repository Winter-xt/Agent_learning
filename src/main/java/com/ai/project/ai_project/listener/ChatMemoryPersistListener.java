package com.ai.project.ai_project.listener;

import com.ai.project.ai_project.domain.ChatMemoryEntity;
import com.ai.project.ai_project.event.ChatMemoryPersistEvent;
import com.ai.project.ai_project.mapper.ChatMemoryMapper;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class ChatMemoryPersistListener {

    private final ChatMemoryMapper mapper;

    public ChatMemoryPersistListener(ChatMemoryMapper mapper) {
        this.mapper = mapper;
    }

    @Async
    @EventListener
    public void onChatMemoryPersistEvent(ChatMemoryPersistEvent event) {
        if (event.isDelete()) {
            mapper.deleteById(event.getMemoryId());
            return;
        }

        ChatMemoryEntity entity = new ChatMemoryEntity(event.getMemoryId(), event.getMessagesJson());
        if (mapper.selectById(entity.getId()) == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
    }
}
