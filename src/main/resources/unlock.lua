-- 使用lua脚本来保证  比锁和删锁的一致性  ，防止比锁成功后 缓存过期以至于其他线程进入 结果误删锁

-- 这里的 KEYS[1] 就是锁的key(存在缓存中，是目标删除的key)，这里的ARGV[1] 就是当前线程标示
-- 获取锁中的标示，判断是否与当前线程标示一致
if(redis.call('GET',KEYS[1]) == ARGV[1]) then
    -- 一致，就删除锁
    return redis.call('DEL',KEYS[1])
end
-- 不一致，就直接返回0
return 0