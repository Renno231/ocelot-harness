local boot = computer.getBootAddress()
local filesystem = assert(component.proxy(boot), "project filesystem unavailable")
local handle, reason = filesystem.open("/firmware/project-loader.lua", "r")
assert(handle, reason)
local chunks = {}
while true do
  local chunk = filesystem.read(handle, math.huge)
  if not chunk then break end
  chunks[#chunks + 1] = chunk
end
filesystem.close(handle)
local loader, loadReason = load(table.concat(chunks), "=/firmware/project-loader.lua", "t", _G)
assert(loader, loadReason)
return loader()
