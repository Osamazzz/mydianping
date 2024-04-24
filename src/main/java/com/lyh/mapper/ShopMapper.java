package com.lyh.mapper;

import com.lyh.entity.Shop;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 *  Mapper 接口
 */
@Mapper
public interface ShopMapper extends BaseMapper<Shop> {

    @Select("select id from tb_shop")
    List<Long> getIds();
}
