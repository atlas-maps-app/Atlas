# Fixtures of the SVG renderer tests

- `probes/<name>.svg`: each filter the engine writes (the survey of 753 renders found twelve kinds), applied to a dark
  bar, a light square and a green disc on a grey-blue ground. `probes/<name>.png` is Safari's rasterisation at 2×,
  made on macOS with `tools/mkref.py` (`qlmanage -t -s 1024` of an HTML page showing the SVG at 2×).
- `newyork_sunny_g.svg`: a real render of the engine (Python, `render/`), and `newyork_sunny_g.png` Safari's picture of it.
- `turbulence_reference.txt`: the Filter Effects spec's C reference turbulence (`turbulence_reference.c`, compiled with
  `cc -O0 -o turb turbulence_reference.c -lm`), every fourth line of its output.
- `tools/survey.py renders/*.svg > survey.json`: counts every element, attribute and value the renders use.
  `tools/filters.py renders/*.svg`: the distinct `<filter>` definitions. `tools/mkref.py file.svg`: Safari's 2× picture.
