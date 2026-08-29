local marker = "ALPHA"
local gpu = component.proxy(component.list("gpu")())
local screen = component.list("screen")()
assert(gpu and screen, "GPU and screen are required")
gpu.bind(screen)
gpu.setResolution(40, 8)
gpu.fill(1, 1, 40, 8, " ")
gpu.set(1, 1, marker)
while true do
  local signal = table.pack(computer.pullSignal())
  if signal[1] == "touch" then
    gpu.fill(1, 1, 40, 1, " ")
    gpu.set(1, 1, "ALPHA TOUCHED")
  elseif signal[1] == "clipboard" then
    gpu.set(1, 2, tostring(signal[3]))
  end
end
