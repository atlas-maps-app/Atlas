# Page tests

Headless WebKit tests for the Studio page. The page's data comes from an export (one JSON per doc), is inlined into a
harness, and the store is mocked, so nothing is read from or written to a live database.

| File | What it is |
|---|---|
| `runner.swift` → `runner` | Loads a harness in an off-screen WebKit view and runs test scripts in it. `./build.sh` compiles it. |
| `prelude.js` | Helpers every test gets (`ready`, `tabTo`, `openAll`, `over`, `bad`, `Q`, `QA`, …). Found beside `runner`. |
| `mkharness.py` | Builds harnesses from a page and an export (`--db studio`). |
| `studio/` | The Studio suite: `tests/`, `mkstudio.py` (its harnesses), `check.py`, `all.sh`, `run.sh`. |
| `export/` | The export the checks were written against (`studio/`), the scripts' default. |

The whole suite: `studio/all.sh [PAGE] [EXPORT]` (a few minutes; prints `== Studio checks: N passed, M failed`).
A test is the body of an async function run in the page; it returns a string, usually `JSON.stringify(out)`; the page
reloads between tests. In a harness, `window.__DATA` is the data, `window.__writes` lists every write
(`[op, path, payload]`), `window.__errors` every error; `window.__FAILWRITE = true` makes writes fail.

A test that returns `SNAP <png path> <height>` saves the page at the runner's width and that height (up to 16000).

Generated files (`cache/pagetest/`, `runner`) are not kept in git. A fresh export comes from `inbox.py`'s step 1.
