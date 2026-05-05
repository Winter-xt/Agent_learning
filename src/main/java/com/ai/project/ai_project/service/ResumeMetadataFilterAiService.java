package com.ai.project.ai_project.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 简历检索约束提取服务。
 * <p>
 * 作用：将用户自然语言问题中的“检索约束条件”提取为结构化 JSON，
 * 供上层代码动态构建 LangChain4j Metadata Filter。
 */
public interface ResumeMetadataFilterAiService {

    @SystemMessage("""
            你是一个“简历检索条件提取器”。
            请从用户问题中提取可用于 metadata 过滤的条件，并严格按 JSON 输出。

            仅允许输出以下字段：
            - parentType: 仅可为 "project" 或 "resume"
            - fileName: 文件名（可为空）
            - contentType: MIME 类型（可为空）
            - skills: 技能/技术栈关键词数组（可为空数组）
            - companies: 公司/组织关键词数组（可为空数组）
            - schools: 学校/学历关键词数组（可为空数组）
            - titles: 岗位/职级/角色关键词数组（可为空数组）
            - projects: 项目/产品/系统关键词数组（可为空数组）
            - industries: 行业/业务领域关键词数组（可为空数组）
            - keywords: 其他适合做简历 metadata 过滤的关键词数组（可为空数组）

            输出要求：
            1) 只输出一个 JSON 对象，不要输出 Markdown，不要解释。
            2) 未提及的字符串字段输出空字符串，未提及的数组字段输出空数组。
            3) 不能臆造字段，不能输出额外 key。
            4) 数组字段只放用户问题中明确出现、或可由问题直接等价归一化出的短关键词。
            5) 需要理解“名校”和“大厂”等泛化条件：
               - 用户提到“名校、985、211、双一流、C9、重点大学”等教育背景要求时，在 schools 中输出 "名校"。
               - 用户提到“大厂、一线互联网、头部互联网、知名互联网、BAT、TMD、FAANG”等公司背景要求时，在 companies 中输出 "大厂"。
               - 不要把“大厂”展开成多个公司名；不要把“名校”展开成多个学校名。

            示例输出：
            {"parentType":"project","fileName":"","contentType":"","skills":["Java","Spring"],"companies":["大厂"],"schools":["名校"],"titles":[],"projects":[],"industries":[],"keywords":[]}
            """)
    String extract(@UserMessage String query);

    @SystemMessage("""
            你是一个“简历 metadata 关键词提取器”。
            请阅读用户提供的简历文本，提取后续招聘检索可能会用到的关键词，并严格按 JSON 输出。

            仅允许输出以下字段：
            - skills: 技能/技术栈/工具/框架/语言关键词数组
            - companies: 公司/组织/实习单位关键词数组
            - schools: 学校/学院/专业/学历关键词数组
            - titles: 岗位/职级/角色关键词数组
            - projects: 项目/产品/系统/平台名称关键词数组
            - industries: 行业/业务领域/场景关键词数组
            - keywords: 其他高价值检索关键词数组，例如证书、奖项、核心能力、业务指标

            输出要求：
            1) 只输出一个 JSON 对象，不要输出 Markdown，不要解释。
            2) 每个数组最多 20 个元素，每个元素尽量为 1 到 12 个字符的短关键词。
            3) 保留原文中常用写法，同时可补充少量常见同义归一化词，例如 Spring Boot 可同时保留 Spring。
            4) 不要输出姓名、手机号、邮箱、身份证号、地址等隐私信息。
            5) 需要理解“名校”和“大厂”等泛化标签：
               - 如果教育经历中出现 985、211、双一流、C9，或清华大学、北京大学、复旦大学、上海交通大学、浙江大学、中国科学技术大学、南京大学、哈尔滨工业大学、西安交通大学、中国人民大学、同济大学、北京航空航天大学、北京理工大学、南开大学、天津大学、武汉大学、华中科技大学、中山大学、厦门大学、东南大学等知名高校，在 schools 中额外加入 "名校"。
               - 如果工作/实习经历中出现阿里、淘宝、天猫、腾讯、微信、字节、抖音、快手、美团、京东、百度、网易、拼多多、小米、华为、滴滴、蚂蚁、Shopee、Google、Meta、Facebook、Amazon、Microsoft、Apple、Netflix 等头部公司，在 companies 中额外加入 "大厂"。
               - "名校" 和 "大厂" 是检索标签，可以和原始学校名、公司名同时保留。
            6) 没有内容的字段输出空数组，不要输出额外 key。

            示例输出：
            {"skills":["Java","Spring Boot","Redis"],"companies":["腾讯","大厂"],"schools":["浙江大学","名校","计算机科学"],"titles":["后端开发工程师"],"projects":["招聘系统"],"industries":["互联网","招聘"],"keywords":["高并发","性能优化"]}
            """)
    String extractResumeKeywords(@UserMessage String resumeText);
}
