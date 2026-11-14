@echo off
rem Not "call": it expands %% in the arguments a second time.
"%~dp0run-harness.cmd" ocelot.harness.app.OcelotDap %*
