local marker = "READY"
local gpuAddress = component.list("gpu")()
local screenAddress = component.list("screen")()
assert(gpuAddress and screenAddress, "GPU and screen are required")
local gpu = component.proxy(gpuAddress)
gpu.bind(screenAddress)
gpu.setResolution(40, 8)
gpu.fill(1, 1, 40, 8, " ")
gpu.set(1, 1, marker)
gpu.set(1, 4, "UNICODE:λ")

local dragStage = 0
while true do
  local signal = table.pack(computer.pullSignal())
  if signal[1] == "touch" then
    dragStage = 1
    gpu.fill(1, 1, 40, 1, " ")
    if signal[3] == 1 and signal[4] == 1 then
      gpu.set(1, 1, "TOUCHED")
    else
      gpu.set(1, 1, "BAD-COORDINATES")
    end
  elseif signal[1] == "drag" then
    dragStage = 2
  elseif signal[1] == "drop" and dragStage == 2 then
    dragStage = 0
    gpu.fill(1, 1, 40, 1, " ")
    gpu.set(1, 1, "DRAGGED")
  elseif signal[1] == "clipboard" then
    gpu.fill(1, 2, 40, 1, " ")
    gpu.set(1, 2, tostring(signal[3]))
  elseif signal[1] == "key_down" then
    gpu.fill(1, 3, 40, 1, " ")
    gpu.set(1, 3, "KEY:" .. string.char(signal[3]) .. ":" .. tostring(signal[4]))
  end
end
