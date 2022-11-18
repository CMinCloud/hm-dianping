package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate template;

    @Override
    public Result queryBYId(Long id) {
//        1.查询redis，检查是否有缓存内容
        String key = CACHE_SHOP_KEY + id;
        String shopJson = template.opsForValue().get(key);  //获取商铺信息的json字符串
//        2.如果有，直接返回
        if(StrUtil.isNotBlank(shopJson)){      // 缓存命中了非空值
            return Result.ok(JSONUtil.toBean(shopJson,Shop.class));
        }
//        判断命中的是否是空值：意思是缓存中存有值，不过是我们自己存入的空值 ""，说明数据库中暂时没有
        if(shopJson != null){
            return Result.fail("商铺不存在！");
        }
//        3.如果没有，进行查询数据库
        Shop shop = getById(id);
//        3.1 数据库中查询不到，返回报错
        if(shop == null){
//            解决缓存穿透：：：存入一个空值 ,有效期为2min
            template.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return Result.fail("商铺不存在！！！");
        }
//        3.2 数据库中查询到了，缓存       （同时设置缓存时间， 减小更新数据库后删除缓存  错误影响）
        template.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        4.返回
        return Result.ok(shop);
    }

    @Override
    @Transactional      //这里注意理解，这里更新数据库和删除   /  这里用mq实现异步更新其实更好一些
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
//        1.更新数据库
        updateById(shop);

//        2.清理缓存
        String key = CACHE_SHOP_KEY + id;
        template.delete(key);
        return Result.ok();
    }
}
