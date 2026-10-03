-- Ocelot Harness showcase: an animated wave plus live input and event panels.
local gpu = component.proxy(component.list("gpu")())
local screen = component.list("screen")()
assert(gpu and screen, "GPU and screen are required")
gpu.bind(screen)

local W, H = 80, 25
gpu.setResolution(W, H)

local WAVE_TOP, WAVE_BOTTOM = 3, 12
local PANEL_TOP, PANEL_BOTTOM = 14, 23
local SPLIT = 41
local palette = { 0x3366FF, 0x3399FF, 0x33CCCC, 0x33CC66, 0x99CC33, 0xFFCC33, 0xFF9933, 0xFF3366, 0xCC33CC }

local function box(x, y, w, h, title)
  gpu.setBackground(0x1A1A2E)
  gpu.setForeground(0x555577)
  gpu.fill(x, y, w, h, " ")
  gpu.set(x, y, "┌" .. string.rep("─", w - 2) .. "┐")
  gpu.set(x, y + h - 1, "└" .. string.rep("─", w - 2) .. "┘")
  gpu.fill(x, y + 1, 1, h - 2, "│")
  gpu.fill(x + w - 1, y + 1, 1, h - 2, "│")
  gpu.setForeground(0xFFCC33)
  gpu.set(x + 2, y, " " .. title .. " ")
end

local function chrome()
  gpu.setBackground(0x0F0F1A)
  gpu.fill(1, 1, W, H, " ")
  gpu.setBackground(0x3366CC)
  gpu.setForeground(0xFFFFFF)
  gpu.fill(1, 1, W, 1, " ")
  gpu.set(3, 1, "OCELOT HARNESS")
  gpu.setForeground(0xCCDDFF)
  gpu.set(19, 1, "OpenComputers, no Minecraft required")
  box(1, PANEL_TOP, SPLIT - 1, PANEL_BOTTOM - PANEL_TOP + 1, "keyboard")
  box(SPLIT, PANEL_TOP, W - SPLIT + 1, PANEL_BOTTOM - PANEL_TOP + 1, "events")
end

-- Scroll the wave left one column per frame and draw only the new column,
-- which keeps each frame well inside the GPU call budget.
local column = 0
local function waveColumn(x)
  local height = WAVE_BOTTOM - WAVE_TOP + 1
  local v = math.sin(column / 6) * 0.6 + math.sin(column / 2.7) * 0.4
  local bar = math.max(1, math.floor((v + 1) / 2 * height + 0.5))
  gpu.setBackground(0x0F0F1A)
  gpu.fill(x, WAVE_TOP, 1, height - bar, " ")
  gpu.setBackground(palette[math.floor(column / 3) % #palette + 1])
  gpu.fill(x, WAVE_BOTTOM - bar + 1, 1, bar, " ")
  column = column + 1
end

local function wave()
  gpu.copy(3, WAVE_TOP, W - 3, WAVE_BOTTOM - WAVE_TOP + 1, -1, 0)
  waveColumn(W - 1)
end

local typed = ""
local function drawTyped()
  gpu.setBackground(0x1A1A2E)
  gpu.setForeground(0xFFFFFF)
  local inner = SPLIT - 5
  local lines = PANEL_BOTTOM - PANEL_TOP - 1
  for row = 1, lines do
    local text = typed:sub((row - 1) * inner + 1, row * inner)
    gpu.set(3, PANEL_TOP + row, text .. string.rep(" ", inner - #text))
  end
  local cursorRow = math.min(lines, math.floor(#typed / inner) + 1)
  gpu.setForeground(0x33CCCC)
  gpu.set(3 + #typed % inner, PANEL_TOP + cursorRow, "_")
end

local events = {}
local function logEvent(text, color)
  table.insert(events, { text, color })
  if #events > PANEL_BOTTOM - PANEL_TOP - 1 then table.remove(events, 1) end
  gpu.setBackground(0x1A1A2E)
  for i, entry in ipairs(events) do
    gpu.setForeground(entry[2])
    gpu.set(SPLIT + 2, PANEL_TOP + i, (entry[1] .. string.rep(" ", W - SPLIT - 3)):sub(1, W - SPLIT - 3))
  end
end

local function status(t)
  gpu.setBackground(0x0F0F1A)
  gpu.setForeground(0x777799)
  gpu.set(2, H, string.format("uptime %6.2fs   memory %3d%% free   driven by ocelotctl", t,
    math.floor(computer.freeMemory() / computer.totalMemory() * 100)))
end

chrome()
for x = 2, W - 1 do waveColumn(x) end
drawTyped()
logEvent("boot ok", 0x33CC66)

while true do
  wave()
  status(computer.uptime())
  local name, _, a, b = computer.pullSignal(0.05)
  if name == "key_down" then
    if a == 8 then
      typed = typed:sub(1, -2)
    elseif a >= 32 then
      typed = typed .. unicode.char(a)
    end
    drawTyped()
    logEvent(string.format("key_down  %q", a >= 32 and unicode.char(a) or tostring(a)), 0x33CCCC)
  elseif name == "touch" then
    local _, _, background = gpu.get(a, b)
    gpu.setBackground(background)
    gpu.setForeground(0xFFFFFF)
    gpu.set(a, b, "◆")
    logEvent(string.format("touch     x=%d y=%d", a, b), 0xFF9933)
  elseif name == "drag" then
    logEvent(string.format("drag      x=%d y=%d", a, b), 0xFF3366)
  elseif name == "scroll" then
    logEvent(string.format("scroll    x=%d y=%d", a, b), 0xCC33CC)
  end
end
