package com.ai.project.ai_project.service;

import com.ai.project.ai_project.tools.FinancialTools;
import com.ai.project.ai_project.util.IntentRoutingUtils;
import com.ai.project.ai_project.util.MemoryIdUtils;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class TalentService {
    private static final String MEMORY_CATEGORY = "talent";
    private static final String MEMORY_SCOPE_SEPARATOR = "::";
    private static final int MAX_CHAT_MEMORY_MESSAGES = 10;
    private static final int RAG_MAX_RESULTS = 3;

    private final ChatModel chatLanguageModel;
    private final StreamingChatModel streamingChatLanguageModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatMemoryStore chatMemoryStore;
    private final FinancialTools financialTools;

    private AiService intentClassifier;
    private TalentAnalyst analyst;
    private TalentAnalyst analystWithoutRag;
    private StreamingTalentAnalyst streamingAnalyst;
    private StreamingTalentAnalyst streamingAnalystWithoutRag;

    public TalentService(ChatModel chatLanguageModel,
                         StreamingChatModel streamingChatLanguageModel,
                         EmbeddingModel embeddingModel,
                         EmbeddingStore<TextSegment> embeddingStore,
                         ChatMemoryStore chatMemoryStore,
                         FinancialTools financialTools) {
        this.chatLanguageModel = chatLanguageModel;
        this.streamingChatLanguageModel = streamingChatLanguageModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.chatMemoryStore = chatMemoryStore;
        this.financialTools = financialTools;
    }

    @PostConstruct
    public void init() throws IOException {
        EmbeddingStoreContentRetriever retriever = buildRetriever();

        this.intentClassifier = AiServices.builder(AiService.class)
                .chatModel(chatLanguageModel)
                .build();

        this.analyst = buildAnalyst(true, retriever);
        this.analystWithoutRag = buildAnalyst(false, retriever);
        this.streamingAnalyst = buildStreamingAnalyst(true, retriever);
        this.streamingAnalystWithoutRag = buildStreamingAnalyst(false, retriever);
    }

    /**
     * 同步问答入口：
     * 1) 先识别用户意图
     * 2) 再根据意图选择是否启用 RAG
     */
    public String analyze(String userId, String query) {
        Intent intent = classifyIntent(query);
        if (IntentRoutingUtils.shouldUseRag(intent)) {
            return analyst.analyze(scopedMemoryId(userId), query);
        }
        return analystWithoutRag.analyze(scopedMemoryId(userId), query);
    }

    /**
     * 流式问答入口：
     * 与同步入口保持一致的路由策略，避免两条链路行为不一致。
     */
    public TokenStream analyzeStream(String userId, String query) {
        Intent intent = classifyIntent(query);
        if (IntentRoutingUtils.shouldUseRag(intent)) {
            return streamingAnalyst.analyze(scopedMemoryId(userId), query);
        }
        return streamingAnalystWithoutRag.analyze(scopedMemoryId(userId), query);
    }

    /**
     * 使用轻量分类模型将用户问题映射为业务意图，解析失败时回退 UNKNOWN。
     */
    private Intent classifyIntent(String query) {
        String label = intentClassifier.classify(query);
        return IntentRoutingUtils.parseIntentLabel(label);
    }

    private String scopedMemoryId(String userId) {
        return MemoryIdUtils.buildScopedMemoryId(MEMORY_CATEGORY, MEMORY_SCOPE_SEPARATOR, userId);
    }

    private EmbeddingStoreContentRetriever buildRetriever() {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(RAG_MAX_RESULTS)
                .build();
    }

    private MessageWindowChatMemory buildChatMemory(Object memoryId) {
        return MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(MAX_CHAT_MEMORY_MESSAGES)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    private TalentAnalyst buildAnalyst(boolean useRag, EmbeddingStoreContentRetriever retriever) {
        AiServices<TalentAnalyst> builder = AiServices.builder(TalentAnalyst.class)
                .chatModel(chatLanguageModel)
                .chatMemoryProvider(this::buildChatMemory)
                .tools(financialTools);
        if (useRag) {
            builder.contentRetriever(retriever);
        }
        return builder.build();
    }

    private StreamingTalentAnalyst buildStreamingAnalyst(boolean useRag, EmbeddingStoreContentRetriever retriever) {
        AiServices<StreamingTalentAnalyst> builder = AiServices.builder(StreamingTalentAnalyst.class)
                .streamingChatModel(streamingChatLanguageModel)
                .chatMemoryProvider(this::buildChatMemory)
                .tools(financialTools);
        if (useRag) {
            builder.contentRetriever(retriever);
        }
        return builder.build();
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
        String analyze(@MemoryId String userId, @UserMessage String userQuery);
    }

    public interface StreamingTalentAnalyst {
        @SystemMessage("""
                你是一个高效的金融自动化助手。
                你的任务流程如下：
                1. 首先查询用户的余额。
                2. 如果余额少于 50000 元（五万元），你必须【立即】且【自动】调用申请贷款的工具，无需询问用户。
                3. 完成所有操作后，再一次性向用户汇报最终结果：余额是多少，以及是否已自动提交贷款申请。
                请注意：不要在中途停下来问用户，直接执行完整流程。
                """)
        TokenStream analyze(@MemoryId String userId, @UserMessage String userQuery);
    }
}
