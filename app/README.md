# Atlas, the app

Start here. The brief is in the [README](../README.md); the boards are the spec: the [Main canvas](https://claude.ai/artifact/Dapy8WL82jeyUhqPKPmA5L), every screen as a picture in `cache/pictures/img/`, their stars and your requests in the [Studio](https://claude.ai/artifact/91R7EDZTwhvELNpH6nvfSB). Atlas's own map engine, in Python, is `render/` (its README says how it draws from real data) and the data it draws from is `data/`.

Everything about the app is decided here, in its code and in this file. Nothing sits in between: no plan page, no procedure. A decision is written down the day it is made, under Decisions.

The code is `android/`: a Kotlin and Jetpack Compose project that builds from the command line (its README says how, and which versions it stands on). It has one blank screen so far.

## The order

1. **The base: how the map, the routes and the voice get made on Android.** The engine's look drawn natively by our own vector renderer, a port of `render/`; which open-source offline router and voice; what else is built as our own.
2. **The app: Native Android
3. **Traditions, further.** The plan’s next places.

## Decisions

1. **Our own vector renderer, a port of `render/`. The engine's look is drawn by our own code on the phone, not by an open-source renderer carrying its style.

Still open, taken by building: which open-source offline router, and which voice.

## Ideas

One line each, by tab, in [ideas.md](ideas.md). New ideas come in through the Studio's Inbox (Add an idea); they are moved here when they are taken up. The planned screens and the open to-dos the pages carried are there too, with the categories the boards cover.


- An idea is one plain line. What it is and how it is done is figured out when it is built.
- Before any workflow over many files or agents: the agent count and a token estimate
