-- 释放锁逻辑

if (redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 触发释放锁
    return redis.call('del', KEYS[1])
end
return 0