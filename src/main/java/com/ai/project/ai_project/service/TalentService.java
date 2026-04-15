package com.ai.project.ai_project.service;

import com.ai.project.ai_project.tools.FinancialTools;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class TalentService {

    private final ChatLanguageModel chatLanguageModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatMemoryStore chatMemoryStore;
    private final FinancialTools financialTools;

    private TalentAnalyst analyst;

    public TalentService(ChatLanguageModel chatLanguageModel,
                         EmbeddingModel embeddingModel,
                         EmbeddingStore<TextSegment> embeddingStore,
                         ChatMemoryStore chatMemoryStore,
                         FinancialTools financialTools) {
        this.chatLanguageModel = chatLanguageModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.chatMemoryStore = chatMemoryStore;
        this.financialTools = financialTools;
    }

    @PostConstruct
    public void init() throws IOException {
        // Load data into vector store
        Path csvPath = Path.of("/Users/winter/Downloads/test_users.csv");
        if (Files.exists(csvPath)) {
            List<String> lines = Files.readAllLines(csvPath);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (!line.isEmpty()) {
                    TextSegment segment = TextSegment.from(line);
                    embeddingStore.add(embeddingModel.embed(segment).content(), segment);
                }
            }
        }
        EmbeddingStoreContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(3)
                .build();

        this.analyst = AiServices.builder(TalentAnalyst.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemory(MessageWindowChatMemory.builder()
                        .id("talent-analyst-service")
                        .maxMessages(10)
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .contentRetriever(retriever)
                .tools(financialTools)
                .build();
    }

    public String analyze(String query) {
        return analyst.analyze(query);
    }

    public interface TalentAnalyst {
        @SystemMessage("""
                你是一个高效的金融自动化助手。
                你的任务流程如下：
                1. 首先查询用户的余额。
                2. 如果余额少于 50000 元（五万元），你必须【立即】且【自动】调用申请贷款的工具，无需询问用户。
                3. 完成所有操作后，再一次性向用户汇报最终结果：余额是多少，以及是否已自动提交贷款申请。
                请注意：不要在中途停下来问用户，直接执行完整流程。
                """)
        String analyze(@UserMessage String userQuery);
    }
}
