"""Verify the loopback-only Keycloak PKCE flow; creates and removes one synthetic user.

Needs KEYCLOAK_ADMIN_PASSWORD in the process environment and the local app/IdP running.
Uses only the Python standard library. Never prints credentials, codes or tokens.
"""
import base64
import hashlib
import html.parser
import http.cookiejar
import json
import os
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request

IDP = "http://localhost:8180"
APP = "http://localhost:8080"
REALM = IDP + "/realms/bank-lab"
ADMIN = IDP + "/admin/realms/bank-lab"
CALLBACK = APP + "/swagger-ui/oauth2-redirect.html"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class LoopbackCookiePolicy(http.cookiejar.DefaultCookiePolicy):
    def return_ok_secure(self, cookie, request):
        # Browsers treat localhost as trustworthy; urllib otherwise withholds Keycloak's
        # Secure session cookies on HTTP. This exception is restricted to this local IdP.
        if urllib.parse.urlparse(request.full_url).netloc == "localhost:8180":
            return True
        return super().return_ok_secure(cookie, request)


def session():
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar(policy=LoopbackCookiePolicy())), NoRedirect()
    )


def request(url, data=None, headers=None, method=None, browser=None):
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    try:
        response = (browser or session()).open(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.read().decode("utf-8"), response.headers


def form(url, values, browser=None):
    return request(url, urllib.parse.urlencode(values).encode(),
                   {"Content-Type": "application/x-www-form-urlencoded"}, browser=browser)


def check(condition, name):
    if not condition:
        raise RuntimeError(name)


class LoginForm(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.action = None
        self.values = {}
        self.active = False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form" and attrs.get("id") == "kc-form-login":
            self.action = attrs.get("action")
            self.active = True
        if tag == "input" and self.active and attrs.get("type") == "hidden" and attrs.get("name"):
            self.values[attrs["name"]] = attrs.get("value", "")

    def handle_endtag(self, tag):
        if tag == "form":
            self.active = False


def auth_values(verifier, state):
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    return {"client_id": "bank-swagger", "response_type": "code", "scope": "openid",
            "redirect_uri": CALLBACK, "state": state, "code_challenge_method": "S256",
            "code_challenge": challenge}


def code_for(username, password):
    browser = session()
    verifier, state = secrets.token_urlsafe(48), secrets.token_urlsafe(24)
    url = REALM + "/protocol/openid-connect/auth?" + urllib.parse.urlencode(auth_values(verifier, state))
    status, page, headers = request(url, browser=browser)
    check(status == 200, "authorization page unavailable")
    login = LoginForm()
    login.feed(page)
    check(login.action and login.action.startswith(IDP + "/"), "unexpected login target")
    login.values.update(username=username, password=password, credentialId="")
    status, page, headers = form(login.action, login.values, browser)
    for _ in range(8):
        location = headers.get("Location", "")
        if location.startswith(CALLBACK + "?"):
            query = urllib.parse.parse_qs(urllib.parse.urlparse(location).query)
            check(query.get("state") == [state] and "code" in query, "invalid authorization response")
            return query["code"][0], verifier
        check(status in (301, 302, 303, 307, 308) and location.startswith(IDP + "/"),
              "login did not complete")
        status, page, headers = request(location, browser=browser)
    raise RuntimeError("too many login redirects")


def exchange(code, verifier):
    return form(REALM + "/protocol/openid-connect/token",
                {"grant_type": "authorization_code", "client_id": "bank-swagger",
                 "redirect_uri": CALLBACK, "code": code, "code_verifier": verifier})


def verify():
    admin_password = os.environ.get("KEYCLOAK_ADMIN_PASSWORD")
    check(bool(admin_password), "load KEYCLOAK_ADMIN_PASSWORD before running")
    status, body, _ = form(IDP + "/realms/master/protocol/openid-connect/token",
                           {"grant_type": "password", "client_id": "admin-cli",
                            "username": "banklab-admin", "password": admin_password})
    check(status == 200, "local admin authentication failed")
    admin_headers = {"Authorization": "Bearer " + json.loads(body)["access_token"],
                     "Content-Type": "application/json"}
    user_id = None
    results = {}
    try:
        username = "bl003-verify-" + secrets.token_hex(6)
        password = secrets.token_urlsafe(32)
        user = {"username": username, "enabled": True, "emailVerified": True,
                "firstName": "Synthetic", "lastName": "Verification",
                "email": username + "@example.invalid", "requiredActions": [],
                "credentials": [{"type": "password", "value": password, "temporary": False}]}
        status, _, headers = request(ADMIN + "/users", json.dumps(user).encode(), admin_headers, "POST")
        check(status == 201, "synthetic user creation failed")
        user_id = headers["Location"].rsplit("/", 1)[1]
        status, body, _ = request(ADMIN + "/clients?clientId=bank-api", headers=admin_headers)
        check(status == 200, "API client lookup failed")
        client_id = json.loads(body)[0]["id"]
        mapping = ADMIN + "/users/" + user_id + "/role-mappings/clients/" + client_id
        for role_name in ("CUSTOMER", "OPERATOR"):
            status, body, _ = request(ADMIN + "/clients/" + client_id + "/roles/" + role_name,
                                      headers=admin_headers)
            check(status == 200, "client role lookup failed")
            role = json.loads(body)
            status, _, _ = request(mapping, json.dumps([role]).encode(), admin_headers, "POST")
            check(status == 204, "role assignment failed")
            code, verifier = code_for(username, password)
            status, body, _ = exchange(code, verifier)
            check(status == 200, "PKCE code exchange failed")
            tokens = json.loads(body)
            auth = {"Authorization": "Bearer " + tokens["access_token"]}
            status, body, headers = request(APP + "/api/v1/iam/me", headers=auth)
            actor = json.loads(body)
            check(status == 200 and actor["subject"] == user_id and actor["roles"] == [role_name],
                  "verified actor or role mismatch")
            check(headers.get("Cache-Control") == "no-store" and not headers.get("Set-Cookie"),
                  "unexpected API cache or session")
            expected = 403 if role_name == "CUSTOMER" else 404
            status, _, _ = request(APP + "/api/v1/operations/not-implemented", headers=auth)
            check(status == expected, "operations role policy mismatch")
            status, _, _ = request(APP + "/api/v1/iam/me",
                                   headers={"Authorization": "Bearer " + tokens["id_token"]})
            check(status == 401, "ID token accepted as API credential")
            results[role_name + "_pkce_actor_and_policy"] = "PASS"
            status, _, _ = request(mapping, json.dumps([role]).encode(), admin_headers, "DELETE")
            check(status == 204, "role cleanup failed")
        values = auth_values(secrets.token_urlsafe(48), secrets.token_urlsafe(24))
        values.pop("code_challenge")
        values.pop("code_challenge_method")
        status, _, headers = request(REALM + "/protocol/openid-connect/auth?" + urllib.parse.urlencode(values))
        location = headers.get("Location", "")
        error = urllib.parse.parse_qs(urllib.parse.urlparse(location).query)
        check(status == 302 and location.startswith(CALLBACK + "?")
              and error.get("error") == ["invalid_request"] and "code" not in error
              and error.get("state") == [values["state"]], "PKCE challenge was not required")
        results["missing_pkce_rejected"] = "PASS"
        code, _ = code_for(username, password)
        status, _, _ = exchange(code, secrets.token_urlsafe(48))
        check(status == 400, "wrong verifier was accepted")
        results["wrong_verifier_rejected"] = "PASS"
        status, _, _ = form(REALM + "/protocol/openid-connect/token",
                             {"grant_type": "password", "client_id": "bank-swagger",
                              "username": username, "password": password})
        check(status in (400, 401), "application password grant was enabled")
        results["application_password_grant_disabled"] = "PASS"
    finally:
        if user_id:
            status, _, _ = request(ADMIN + "/users/" + user_id, headers=admin_headers, method="DELETE")
            check(status == 204, "remove synthetic verification user: " + user_id)
            results["synthetic_user_removed"] = "PASS"
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    try:
        verify()
    except Exception as error:
        # Only our controlled check messages are displayed; never dump an HTTP response/token.
        print("OIDC verification failed: " + (str(error) if type(error) is RuntimeError else type(error).__name__))
        sys.exit(1)
