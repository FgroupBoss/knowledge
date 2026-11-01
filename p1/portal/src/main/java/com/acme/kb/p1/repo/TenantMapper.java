package com.acme.kb.p1.repo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TenantMapper {

    record Row(String id, String code, String name, String status) {
    }

    @Select("SELECT id, code, name, status FROM tenant WHERE code = #{code} AND status = 'ACTIVE'")
    Row findByCode(@Param("code") String code);

    @Select("SELECT id, code, name, status FROM tenant WHERE id = #{id}")
    Row findById(@Param("id") String id);

    @Select("SELECT COUNT(*) FROM tenant")
    int count();
}
