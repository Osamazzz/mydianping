package com.lyh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lyh.entity.Voucher;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 *  Mapper 接口
 */
public interface VoucherMapper extends BaseMapper<Voucher> {

    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);
}
