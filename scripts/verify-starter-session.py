#!/usr/bin/env python3
"""Check generated starters through their running fz dev gateway and managed local IDP.

Usage: python3 scripts/verify-starter-session.py JAVA_ORIGIN KOTLIN_ORIGIN
Start each generated starter with fz dev first. No third-party Python packages are needed.
"""

import argparse
import http.cookiejar
import json
import urllib.error
import urllib.parse
import urllib.request


class LocalRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        require_local(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def require_local(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise ValueError("Use a local fz dev HTTP URL")


def request(browser, url, data=None):
    require_local(url)
    try:
        return browser.open(url, data=data, timeout=30)
    except urllib.error.HTTPError as response:
        return response


def check_session(response, status, subject=None):
    with response:
        assert response.status == status, f"Expected HTTP {status}, got {response.status}"
        assert response.headers.get_content_type() == "application/json"
        assert response.headers.get("Cache-Control") == "no-store"
        # Exactly one parse, equivalent to the frontend's response.json().
        payload = json.load(response)
    assert isinstance(payload, dict), f"Expected a JSON object, got {type(payload).__name__}"
    assert payload.get("authenticated") is (subject is not None)
    if subject is None:
        assert "subject" not in payload
    else:
        assert payload.get("subject") == subject


def verify(origin):
    require_local(origin)
    origin = origin.rstrip("/")
    browser = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
        LocalRedirects(),
    )
    session_url = origin + "/app/auth/session"
    check_session(request(browser, session_url), 401)

    with request(browser, origin + "/app/login?returnTo=/app/auth/session") as login:
        assert login.status == 200, "Local login page was not served"
        login_url = login.url
        assert urllib.parse.urlsplit(login_url).path.endswith("/login"), "Expected local IDP login"
    form = urllib.parse.urlencode({"username": "user"}).encode()
    check_session(request(browser, login_url, form), 200, "user")
    check_session(request(browser, session_url), 200, "user")
    print(f"PASS {origin}: HTTP 401 and 200 contain JSON objects after one parse")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("origins", nargs="+", help="Local origins of running generated starters")
    for origin in parser.parse_args().origins:
        verify(origin)
