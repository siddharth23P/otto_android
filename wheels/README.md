# wheels/

The Android wheels otto needs that PyPI does not carry for Android:
pydantic-core, jiter, orjson, ormsgpack, tiktoken, uuid-utils and zstandard.
otto itself (pure Python, 0.1.2) and numpy (Chaquopy's 1.26.2) come from their
indexes at build time.

Built by `.github/workflows/wheels.yml` (cibuildwheel, `--platform android`,
CPython 3.13, arm64-v8a and x86_64, 16 KB page alignment checked).

## Where the binaries live

Not here. A run of the workflow with `publish` set uploads the whole set to a
GitHub Release, and writes `wheels.json`: the release tag and, for every file,
its size and SHA-256. `app/build.gradle.kts`'s `fetchWheels` reads that manifest,
takes each file from this directory when it is already here (the machine that
built them, or an offline build) and downloads the rest from the release,
refusing anything whose digest is not the one the manifest names. Chaquopy's pip
then installs from `app/build/wheels` with `--find-links`, constrained by
`app/src/main/python/constraints.txt` to the versions the set carries.

`.gitignore` keeps `*.whl` out of the repository: 10 MB of binaries per set, and
a digest in git is the better record.

## Record

- Run #7 (2026-09-14, https://github.com/siddharth23P/otto_android/actions/runs/34895506407,
  cibuildwheel 4.2.1, CPython 3.13): the seven original packages built for
  arm64_v8a and x86_64 and passed the 16 KB check. Two things were needed: no
  `setup-android` step (the runner's SDK is enough) and dropping each sdist's own
  `[tool.cibuildwheel]` tables (zstandard's names an enable group cibuildwheel 4
  rejects).
- 2026-09-16: `-PembeddedPython=true` resolved everything except
  `numpy>=2.5.3` -- the newest numpy for Android CPython 3.13 is Chaquopy's own
  1.26.2, and PyPI publishes no Android wheel for any numpy version. numpy is
  therefore an eighth matrix entry here rather than a lowered floor in otto.
- 2026-09-16: numpy left the matrix -- otto's floor became `numpy>=1.26.2`, which
  Chaquopy's index serves. otto was published to PyPI as 0.1.2 (the embeddable
  release, versioned 0.2.0 until then), so the otto wheel left the set too.
