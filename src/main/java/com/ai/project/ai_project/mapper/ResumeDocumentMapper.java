package com.ai.project.ai_project.mapper;

import com.ai.project.ai_project.domain.ResumeDocumentEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface ResumeDocumentMapper extends BaseMapper<ResumeDocumentEntity> {

    @Select("SELECT * FROM resume_document WHERE id = #{id} AND user_id_key = #{userIdKey} AND source_type = #{sourceType}")
    ResumeDocumentEntity selectByIdAndUserIdKeyAndSourceType(@Param("id") Long id,
                                                             @Param("userIdKey") String userIdKey,
                                                             @Param("sourceType") String sourceType);

    @Select("SELECT * FROM resume_document WHERE user_id_key = #{userIdKey} AND source_type = #{sourceType} AND candidate_name_key = #{candidateNameKey} LIMIT 1")
    ResumeDocumentEntity selectByUserIdKeyAndCandidateNameKey(@Param("userIdKey") String userIdKey,
                                                              @Param("sourceType") String sourceType,
                                                              @Param("candidateNameKey") String candidateNameKey);

    @Select("SELECT * FROM resume_document WHERE user_id_key = #{userIdKey} AND source_type = #{sourceType} AND candidate_name_key = #{candidateNameKey} AND original_file_name = #{originalFileName} LIMIT 1")
    ResumeDocumentEntity selectByUserIdKeyAndCandidateNameKeyAndOriginalFileName(@Param("userIdKey") String userIdKey,
                                                                                 @Param("sourceType") String sourceType,
                                                                                 @Param("candidateNameKey") String candidateNameKey,
                                                                                 @Param("originalFileName") String originalFileName);

    @Delete("DELETE FROM resume_document WHERE user_id_key = #{userIdKey} AND source_type = #{sourceType}")
    int deleteByUserIdKeyAndSourceType(@Param("userIdKey") String userIdKey,
                                       @Param("sourceType") String sourceType);

    @Select("SELECT * FROM resume_document WHERE user_id_key = #{userIdKey} AND source_type = #{sourceType} ORDER BY uploaded_at DESC, id DESC")
    List<ResumeDocumentEntity> selectByUserIdKeyAndSourceType(@Param("userIdKey") String userIdKey,
                                                              @Param("sourceType") String sourceType);

    @Delete("DELETE FROM resume_document WHERE id = #{id} AND user_id_key = #{userIdKey} AND source_type = #{sourceType}")
    int deleteByIdAndUserIdKeyAndSourceType(@Param("id") Long id,
                                            @Param("userIdKey") String userIdKey,
                                            @Param("sourceType") String sourceType);
}
