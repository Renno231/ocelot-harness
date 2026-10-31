@echo off
rem Not "call": it expands %% in the arguments a second time, and %%0 in mill-%%02d would become this script's path.
"%~dp0run-harness.cmd" ocelot.harness.app.OcelotCtl %*
