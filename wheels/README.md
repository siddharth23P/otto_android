# wheels/

Android wheels for the compiled packages otto needs that PyPI does not
carry for Android: pydantic-core, jiter, orjson, ormsgpack, tiktoken,
uuid-utils, zstandard -- plus the pure-Python otto wheel built from the
pinned git ref until 0.2.0 is on PyPI.

Produced by `.github/workflows/wheels.yml` (cibuildwheel, `--platform
android`, CPython 3.13, arm64-v8a and x86_64, 16 KB page alignment checked).
Chaquopy's pip installs from here with `--find-links` when the build runs
with `-PembeddedPython=true`.

## Record

- Run #7 (2026-09-14, https://github.com/siddharth23P/otto_android/actions/runs/34895506407,
  cibuildwheel 4.2.1, CPython 3.13): all seven packages built for arm64_v8a
  and x86_64 and passed the 16 KB check. The wheels are that run's artifacts
  (`wheels-<package>`); committing them here and writing `constraints.txt`
  is issue #12. Two things were needed: no `setup-android` step (the
  runner's SDK is enough) and dropping each sdist's own
  `[tool.cibuildwheel]` tables (zstandard's names an enable group
  cibuildwheel 4 rejects).

Empty of wheels until they are committed. The job's summary lists, per package,
whether the build succeeded; a failed package is recorded here with the
error so the next attempt starts from it.
