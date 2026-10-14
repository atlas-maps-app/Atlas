# Licences of the render module

The engine itself is a port of Atlas's own Python engine (`render/` in this repo).

`app.atlas.render.py` reproduces CPython's behaviour bit for bit. It is written from the published algorithms and
CPython's sources, whose terms are kept here:

- **MT19937** (`PyRandom`): the Mersenne Twister by Makoto Matsumoto and Takuji Nishimura, BSD-3-Clause
  (Copyright (C) 1997-2002, Makoto Matsumoto and Takuji Nishimura; all rights reserved).
- **CPython** (`PyRandom` seeding and methods, `PyHash`, `PyFmt`'s rounding rules, `PyMath`'s `hypot`, `sum`,
  `fmod`, `round`): Python Software Foundation License Version 2 (Copyright (c) 2001 Python Software Foundation;
  all rights reserved).
- **SipHash** (`PyHash`): the reference algorithm by Jean-Philippe Aumasson and Daniel J. Bernstein, CC0.
- **fdlibm** (through `java.lang.StrictMath`, not copied): Sun Microsystems' freely distributable maths library,
  shipped with the JDK and Android.

Data shipped as resources:

- **`wx/ams.json`** (Amsterdam's hourly weather, a copy of `data/wx/ams.json`): from the Open-Meteo forecast API,
  CC BY 4.0 (weather data by Open-Meteo.com).
