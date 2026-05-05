package com.ai.project.ai_project.mapper;

import com.ai.project.ai_project.domain.ResumeParentBlockEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ResumeParentBlockMapper extends BaseMapper<ResumeParentBlockEntity> {

    @Delete("DELETE FROM resume_parent_block WHERE user_id_key = #{userIdKey} AND source_type = #{sourceType}")
    int deleteByUserIdKeyAndSourceType(@Param("userIdKey") String userIdKey,
                                       @Param("sourceType") String sourceType);

    @Delete("DELETE FROM resume_parent_block WHERE resume_document_id = #{resumeDocumentId}")
    int deleteByResumeDocumentId(@Param("resumeDocumentId") Long resumeDocumentId);
}
