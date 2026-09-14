# wheels/

Android wheels for the compiled packages otto needs that PyPI does not
carry for Android: pydantic-core, jiter, orjson, ormsgpack, tiktoken,
uuid-utils, zstandard -- plus the pure-Python otto wheel built from the
pinned git ref until 0.2.0 is on PyPI.

Produced by `.github/workflows/wheels.yml` (cibuildwheel, `--platform
android`, CPython 3.13, arm64-v8a and x86_64, 16 KB page alignment checked).
Chaquopy's pip installs from here with `--find-links` when the build runs
with `-PembeddedPython=true`.

Empty until the first successful run. The job's summary lists, per package,
whether the build succeeded; a failed package is recorded here with the
error so the next attempt starts from it.
