--[[
Sliding window log, executed atomically inside Redis.

Keeps one timestamp per allowed request in a sorted set (score = time in ms).
A request is allowed if fewer than `limit` requests happened in the last
`window_ms` milliseconds, i.e. in the window (now - window_ms, now].

Inputs
  KEYS[1]  log key, e.g. "rate_limit:DemoController.search:user:42:swl"
  ARGV[1]  limit       max requests per window (integer)
  ARGV[2]  window_ms   window length in milliseconds (integer)
  ARGV[3]  member      unique id for this request, so two requests in the
                       same millisecond are both recorded

Output: { allowed, remaining, retry_after_ms }
]]

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local member = ARGV[3]

local time = redis.call('TIME')
local now_ms = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

-- Drop requests that have slid out of the window
redis.call('ZREMRANGEBYSCORE', key, '-inf', now_ms - window_ms)
local count = redis.call('ZCARD', key)

if count >= limit then
  -- A slot frees up when the entry that pushes us over the limit leaves the window.
  -- Normally that is the oldest entry; if the limit was lowered there may be more.
  local blocking = redis.call('ZRANGE', key, count - limit, count - limit, 'WITHSCORES')
  local retry_after_ms = math.max(1, tonumber(blocking[2]) + window_ms - now_ms)
  -- Denied requests are not logged, so they do not extend the wait
  return { 0, 0, retry_after_ms }
end

redis.call('ZADD', key, now_ms, member)
-- Every entry is gone after one window, so the whole key can go too
redis.call('PEXPIRE', key, window_ms)

return { 1, limit - count - 1, 0 }
