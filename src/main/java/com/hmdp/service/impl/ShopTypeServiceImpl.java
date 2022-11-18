package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.*;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate template;

    @Override
    public Result queryTypeList() {
//        使用hash来查询：不知道为什么无法缓存到数据库中去，但是第二次刷新却又不需要重更新查询数据库
    /*    Map<Object, Object> entries = template.opsForHash().entries(CACHE_SHOP_TYPE_KEY+"hash");
        if (!entries.isEmpty()) {           //有缓存，直接返回
//            String jsonStr = JSONUtil.toJsonStr(entries);
            System.out.println("从缓存中查询的entris"+entries);
            return Result.ok(entries);
        }
        // 无缓存，进行数据库查询并保存到缓存
//        LambdaQueryWrapper wrapper = new LambdaQueryWrapper();
//        wrapper.orderByAsc("sort");
        List list = query().orderByAsc("sort").list();
        System.out.println("数据库查询的list:"+list);
//        以map形式存入Redis
        Map<String, Object> shopTypeMap = BeanUtil.beanToMap(list, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((filedName, filedValue) ->
                                filedValue.toString()           //将需要修改类型的参数变量修改为String如Long
                        ));
        template.opsForHash().putAll(CACHE_SHOP_TYPE_KEY+"hash",shopTypeMap);
        return Result.ok(list);*/
        String jsonStr = template.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        if(StrUtil.isNotBlank(jsonStr)){                //获取值不为空，直接返回缓存
            List<ShopType> list =  JSONUtil.toList(jsonStr,ShopType.class);
            return Result.ok(list);
        }
        // 无缓存，进行数据库查询并保存到缓存
        List list = query().orderByAsc("sort").list();
        if(list == null){
            return Result.fail("数据库查询异常");
        }
        template.opsForValue().set(CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(list));
        return Result.ok(list);
    }
}
