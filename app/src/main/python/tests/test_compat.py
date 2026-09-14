from otto_app import compat


def test_the_pinned_otto_is_within_range():
    info = compat.probe()
    assert compat._version_tuple(info.otto_version) >= compat.MIN_OTTO
    assert info.api_version <= compat.MAX_API
    for feature in ("disabled_tools", "guidance", "environ_keys", "transcript", "phone_commit"):
        assert feature in info.features, feature


def test_a_future_api_is_refused_loudly(monkeypatch):
    from agent import embed

    monkeypatch.setattr(embed, "API_VERSION", compat.MAX_API + 1)
    try:
        compat.probe()
    except compat.IncompatibleOtto as exc:
        assert "update the app" in str(exc)
    else:
        raise AssertionError("expected IncompatibleOtto")


def test_an_old_otto_is_refused(monkeypatch):
    monkeypatch.setattr(compat, "installed_version", lambda: "0.1.1")
    try:
        compat.probe()
    except compat.IncompatibleOtto as exc:
        assert "older" in str(exc)
    else:
        raise AssertionError("expected IncompatibleOtto")


def test_version_tuples_ignore_suffixes():
    assert compat._version_tuple("0.2.0") == (0, 2, 0)
    assert compat._version_tuple("0.3.0rc1") == (0, 3, 0)
