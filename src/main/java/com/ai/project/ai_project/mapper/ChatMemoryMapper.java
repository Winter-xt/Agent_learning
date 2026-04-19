package com.ai.project.ai_project.mapper;

import com.ai.project.ai_project.domain.ChatMemoryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ChatMemoryMapper extends BaseMapper<ChatMemoryEntity> {

    @Select("SELECT id, category, messages FROM chat_memory WHERE id = #{id} AND category = #{category} LIMIT 1")
    ChatMemoryEntity selectByIdAndCategory(@Param("id") String id, @Param("category") String category);

    @Delete("DELETE FROM chat_memory WHERE id = #{id} AND category = #{category}")
    int deleteByIdAndCategory(@Param("id") String id, @Param("category") String category);

    @Update("UPDATE chat_memory SET messages = #{messages} WHERE id = #{id} AND category = #{category}")
    int updateMessagesByIdAndCategory(@Param("id") String id,
                                      @Param("category") String category,
                                      @Param("messages") String messages);
}
