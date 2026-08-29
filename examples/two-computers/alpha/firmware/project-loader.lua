local boot = computer.getBootAddress()
local filesystem = assert(component.proxy(boot), "project filesystem unavailable")
local handle, reason = filesystem.open("/computer/main.lua", "r")
assert(handle, reason)
local chunks = {}
while true do
  local chunk = filesystem.read(handle, math.huge)
  if not chunk then break end
  chunks[#chunks + 1] = chunk
end
filesystem.close(handle)
local program, loadReason = load(table.concat(chunks), "=/computer/main.lua", "t", _G)
assert(program, loadReason)
return program()
