--[[
Sliding window counter, executed atomically inside Redis.

Approximates a sliding window using two fixed windows aligned to multiples of
window_ms: the current one and the one before it. The previous window's count
is weighted by how much of it still overlaps the sliding window:

  estimate = prev * (window_ms - elapsed) / window_ms + curr

where `elapsed` is how far we are into the current fixed window. A request is
allowed if estimate + 1 <= limit.

State: one Redis hash at KEYS[1]
  window  index of the current fixed window (floor(now / window_ms))
  curr    requests allowed in the current fixed window
  prev    requests allowed in the previous fixed window

Inputs
  KEYS[1]  counter key, e.g. "rate_limit:DemoController.search:user:42:swc"
  ARGV[1]  limit       max requests per window (integer)
  ARGV[2]  window_ms   window length in milliseconds (integer)

Output: { allowed, remaining, retry_after_ms }
]]

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])

local time = redis.call('TIME')
local now_ms = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local window = math.floor(now_ms / window_ms)
local elapsed_ms = now_ms - window * window_ms

local state = redis.call('HMGET', key, 'window', 'curr', 'prev')
local stored_window = tonumber(state[1])
local curr = tonumber(state[2]) or 0
local prev = tonumber(state[3]) or 0

if stored_window == nil or stored_window < window - 1 then
  -- New key, or idle for more than a whole window: nothing counts any more
  curr, prev = 0, 0
elseif stored_window == window - 1 then
  -- First request in a new fixed window: the current window becomes the previous one
  curr, prev = 0, curr
elseif stored_window > window then
  -- Redis clock stepped backwards: stay in the stored window and weight prev fully
  window = stored_window
  elapsed_ms = 0
end

local estimate = prev * (window_ms - elapsed_ms) / window_ms + curr

if estimate + 1 > limit then
  -- Denied: nothing is written. Work out when estimate + 1 <= limit becomes true.
  local retry_after_ms
  if curr < limit then
    -- Wait for the previous window's weight to shrink enough
    retry_after_ms = (window_ms - elapsed_ms) - (limit - 1 - curr) * window_ms / prev
  else
    -- Current window alone is full: wait for the next window, where this one
    -- becomes `prev` and must then shrink enough
    retry_after_ms = (window_ms - elapsed_ms) + math.max(0, window_ms - (limit - 1) * window_ms / curr)
  end
  return { 0, 0, math.max(1, math.ceil(retry_after_ms)) }
end

curr = curr + 1
redis.call('HSET', key, 'window', window, 'curr', curr, 'prev', prev)
-- `curr` still matters during the next window (as `prev`), so keep the key until that ends
redis.call('PEXPIRE', key, 2 * window_ms - elapsed_ms)

return { 1, math.floor(limit - (estimate + 1)), 0 }
