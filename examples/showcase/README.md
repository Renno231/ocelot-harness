# Showcase example

The demo from the top of the main README: a tier-3 computer drawing a scrolling rainbow wave, with panels that show the keys and clicks it receives.

Run it yourself and poke at it:

```text
ocelot-harnessd up --project examples/showcase
ocelotctl --project examples/showcase machine start main
ocelot-viewer --project examples/showcase
ocelotctl --project examples/showcase screen type main-screen "hi there"
ocelotctl --project examples/showcase screen touch main-screen 40 8
ocelot-harnessd down --project examples/showcase
```

The program is plain Lua talking to the GPU directly, in [`computer/computer/main.lua`](computer/computer/main.lua). Edit it and restart the machine to see your changes.

To re-record `docs/images/showcase.gif` after changing it (needs a built jar and Python with Pillow):

```text
python scripts/make_demo_gif.py
```
