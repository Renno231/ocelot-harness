-- Ocelot Harness instrumentation. The harness runs this chunk in a computer's kernel state
-- before the computer's next slice whenever the state lacks it: after boot, reboot and snapshot
-- load. It wraps three kernel functions so the harness sees component calls, time and hooks:
--
--   component.invoke  every component call and its results (the flight recorder)
--   computer.realTime the clock the kernel's "too long without yielding" check reads, so a
--                     debugger pause or deterministic execution does not trip it
--   debug.sethook     the kernel's per-coroutine instruction hook, chained with the profiler,
--                     breakpoints, stepping and deterministic instruction time
--
-- Saved computer state never reaches a Java function: the probe lives in the registry, which
-- saves never include, and the wrappers sit in kernel tables that saves record by name, so a
-- loaded state starts with the plain functions until the harness installs this chunk again.
-- Programs cannot see any of it; the sandbox exposes none of these tables.

local PROBE, FLAGS, BREAKS = "ocelot.harness.probe", "ocelot.harness.flags", "ocelot.harness.breaks"

local getregistry, getinfo, getlocal, getupvalue = debug.getregistry, debug.getinfo, debug.getlocal, debug.getupvalue
local sethook = debug.sethook
local invoke = component.invoke
local realTime = computer.realTime
local type = type

if getregistry()["ocelot.harness.installed"] then return end

local function finish(probe, ...)
  if probe then probe("return", ...) end
  return ...
end

component.invoke = function(address, method, ...)
  local probe = getregistry()[PROBE]
  if probe then probe("call", address, method, ...) end
  return finish(probe, invoke(address, method, ...))
end

computer.realTime = function()
  local probe = getregistry()[PROBE]
  if probe then return probe("time") end
  return realTime()
end

-- Profiler: one sample per instruction-hook fire, the stack of the interrupted function.
-- Level 1 is this function, 2 the hook, 3 the interrupted function.
local function sample(probe)
  local level = 3
  while level < 67 do
    local info = getinfo(level, "Sln")
    if not info then break end
    probe("frame", info.source, info.currentline, info.linedefined, info.name)
    level = level + 1
  end
  probe("sample")
end

-- Stack depth seen from onLine, for stepping. Only compared with other depths taken there.
local function depth()
  local level = 3
  while getinfo(level, "l") do level = level + 1 end
  return level
end

-- Resolves a debugger value path: frame, "L"ocal or "U"pvalue, index, then table keys.
local function resolve(base, frame, kind, index, ...)
  local level = base + frame
  local value
  if kind == "L" then
    local _, v = getlocal(level, index)
    value = v
  else
    local info = getinfo(level, "f")
    if info and info.func then
      local _, v = getupvalue(info.func, index)
      value = v
    end
  end
  for i = 1, select("#", ...) do
    if type(value) ~= "table" then return nil end
    value = rawget(value, (select(i, ...)))
  end
  return value
end

-- Debugger pause. The probe blocks in the harness until the user acts and returns either an
-- inspection request, answered here, or how to go on. Level 1 is this function, 2 onLine, 3 the
-- hook, 4 the paused function.
local function pause(probe, reason)
  while true do
    local command, a, b = probe("paused", reason)
    if command == "frames" then
      local level = 4
      while level < 132 do
        local info = getinfo(level, "Sln")
        if not info then break end
        probe("frame", info.source, info.currentline, info.linedefined, info.name, info.what)
        level = level + 1
      end
    elseif command == "variables" then
      -- a = frame, b = "L" or "U"
      local level = 4 + a
      local i = 1
      if b == "L" then
        while true do
          local name, value = getlocal(level, i)
          if not name then break end
          -- Lua's own scratch slots, such as "(*temporary)", are not the program's variables.
          if name:sub(1, 1) ~= "(" then probe("variable", name, value, i) end
          i = i + 1
        end
      else
        local info = getinfo(level, "f")
        local f = info and info.func
        while f do
          local name, value = getupvalue(f, i)
          if not name then break end
          probe("variable", name, value, i)
          i = i + 1
        end
      end
    elseif command == "value" then
      -- resolve's level 1 is itself, so the paused function is at 5.
      probe("value", resolve(5, probe("path")))
    else
      return command, a
    end
  end
end

local function onLine(probe, flags, registry, line)
  local hit
  local breaks = registry[BREAKS]
  local atLine = breaks and breaks[line]
  if atLine then
    local source = getinfo(3, "S").source
    for i = 1, #atLine do
      local suffix = atLine[i]
      if source:sub(-#suffix) == suffix then hit = "breakpoint" break end
    end
  end
  local step = flags.step
  if not hit and step then
    local d = depth()
    if step == "in" or (step == "over" and d <= flags.stepDepth) or (step == "out" and d < flags.stepDepth) then
      hit = "step"
    end
  end
  if not hit and flags.pause then hit = "pause" end
  if hit then
    local command = pause(probe, hit)
    if command == "in" or command == "over" or command == "out" then
      flags.step, flags.stepDepth = command, depth()
    else
      flags.step = nil
    end
    flags.pause = nil
  end
end

debug.sethook = function(...)
  local thread, hook, mask, count
  if type((...)) == "thread" then thread, hook, mask, count = ... else hook, mask, count = ... end
  if type(hook) ~= "function" then
    if thread then return sethook(thread) end
    return sethook()
  end
  local flags = getregistry()[FLAGS]
  -- The kernel re-arms its hook at every resume, so a slice shorter than its interval never
  -- fires it. Sampling and instruction time use their own finer interval and pass the kernel
  -- its events at the original cadence.
  local every = count
  if count and count > 0 and flags and flags.every and flags.every < count then every = flags.every end
  local pending = 0
  local composite = function(event, line)
    local registry = getregistry()
    local probe, current = registry[PROBE], registry[FLAGS]
    if event == "line" then
      if probe and current then onLine(probe, current, registry, line) end
      return
    end
    if probe and current then
      if current.instructions then probe("instructions", every) end
      if current.profile then sample(probe) end
    end
    pending = pending + every
    if pending >= count then
      pending = pending - count
      return hook(event, line)
    end
  end
  local m = mask or ""
  if flags and flags.lines then m = m .. "l" end
  if thread then return sethook(thread, composite, m, every) end
  return sethook(composite, m, every)
end

getregistry()["ocelot.harness.installed"] = true
