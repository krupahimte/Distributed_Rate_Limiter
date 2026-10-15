--[[
Token bucket, executed atomically inside Redis.

Redis runs a script start-to-finish with no other command in between, so the
read -> refill -> consume -> write sequence below cannot race, even with many
app instances calling it at the same moment.

State: one Redis hash at KEYS[1] with two fields
  tokens       current number of tokens (may be fractional, e.g. 3.25)
  last_refill  time of the last refill, in milliseconds

Inputs
  KEYS[1]  bucket key, e.g. "rate_limit:DemoController.search:user:42:tb"
  ARGV[1]  capacity             max tokens the bucket holds (integer)
  ARGV[2]  refill_per_ms        tokens added per millisecond (decimal, e.g. 0.005)
  ARGV[3]  requested            tokens this request costs (always 1 for now)

Output: an array of three integers
  { allowed, remaining, retry_after_ms }
  allowed         1 if the request may proceed, 0 if not
  remaining       whole tokens left after this request (round down)
  retry_after_ms  0 when allowed; otherwise ms until enough tokens exist (round up)

Requirements
  * A bucket that does not exist yet starts full.
  * Tokens never exceed capacity, however long the bucket was idle.
  * A denied request consumes nothing.
  * Set an expiry (PEXPIRE) on the key so idle buckets disappear: once a bucket
    would be full again it is equivalent to a missing key, so it can be deleted.
  * Take "now" from Redis itself, not from the app, so every app instance agrees
    on the time:
        local t = redis.call('TIME')            -- { seconds, microseconds } as strings
        local now_ms = t[1] * 1000 + math.floor(t[2] / 1000)

Lua cheat sheet (for TypeScript developers)
  local x = 5                         -- like `let`; always use `local`
  tonumber(ARGV[1])                   -- ARGV values arrive as strings
  if a == nil then ... elseif ... else ... end
  math.min(a, b)  math.max(a, b)  math.floor(x)  math.ceil(x)
  a ~= b                              -- "not equal" (=== !== in TS)
  redis.call('HMGET', key, 'f1', 'f2')     -- returns { v1, v2 }; missing fields are `false`
  redis.call('HSET', key, 'f1', v1, 'f2', v2)
  redis.call('PEXPIRE', key, ms)            -- ms must be a whole number
  return { 1, 2, 3 }                        -- Lua tables are 1-indexed!

  Redis converts returned Lua numbers to integers by dropping the decimals,
  so do your own math.floor / math.ceil before returning.
]]

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_per_ms = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

-- Redis' clock, shared by every app instance
local time = redis.call('TIME')
local now_ms = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

-- Load the bucket; a missing key (new or expired) means a full bucket
local state = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(state[1])
local last_refill = tonumber(state[2])
if tokens == nil or last_refill == nil then
  tokens = capacity
  last_refill = now_ms
end

-- Lazy refill: add what dripped in since the last refill, capped at capacity.
-- max(0, ...) guards against the Redis clock stepping backwards.
local elapsed_ms = math.max(0, now_ms - last_refill)
tokens = math.min(capacity, tokens + elapsed_ms * refill_per_ms)

if tokens < requested then
  -- Denied: leave the stored bucket untouched, so retries cost nothing
  local retry_after_ms = math.ceil((requested - tokens) / refill_per_ms)
  return { 0, math.floor(tokens), retry_after_ms }
end

tokens = tokens - requested
redis.call('HSET', key, 'tokens', tokens, 'last_refill', now_ms)

-- Once the bucket is full again it is the same as a missing key, so let it expire then
local ms_until_full = math.ceil((capacity - tokens) / refill_per_ms)
redis.call('PEXPIRE', key, math.max(1, ms_until_full))

return { 1, math.floor(tokens), 0 }
