"""Create fictional care records, then verify them after recreating the application and database containers."""

import argparse
import http.cookiejar
import json
import os
from pathlib import Path
from datetime import datetime, timezone
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPCookieProcessor, Request, build_opener
from uuid import uuid4


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


class Client:
    def __init__(self, base_url):
        self.base_url = base_url
        self.http = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, method, path, data=None, status=200, form=False):
        headers = {"Accept": "application/json" if path.startswith(("/api/", "/actuator/")) else "*/*"}
        body = None
        if method != "GET":
            csrf = self.call("GET", "/api/auth/csrf")
            headers[csrf["headerName"]] = csrf["token"]
        if data is not None:
            body = (urlencode(data) if form else json.dumps(data)).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        request = Request(self.base_url + path, data=body, headers=headers, method=method)
        try:
            response = self.http.open(request, timeout=15)
        except HTTPError as error:
            response = error
        with response:
            content = response.read().decode()
            check(response.status == status, f"{method} {path}: expected {status}, received {response.status}")
            if not content:
                return None
            return json.loads(content) if "json" in response.headers.get("Content-Type", "") else content

    def login(self, role):
        name_key, password_key = {
            "OPERATOR": ("SPRING_SECURITY_USER_NAME", "SPRING_SECURITY_USER_PASSWORD"),
            "VIEWER": ("CLINICFLOW_VIEWER_USERNAME", "CLINICFLOW_VIEWER_PASSWORD"),
            "ADMIN": ("CLINICFLOW_ADMIN_USERNAME", "CLINICFLOW_ADMIN_PASSWORD")
        }[role]
        username = os.environ.get(name_key, role.lower())
        password = os.environ.get(password_key)
        check(bool(password), f"Set {password_key} for the smoke test")
        self.call("POST", "/api/auth/login", {"username": username, "password": password}, 204, form=True)
        session = self.call("GET", "/api/auth/session")
        check(session == {"username": username, "roles": [role]}, f"Unexpected session for {role}")


def now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def snapshot(client, records):
    encounter = f'/api/v1/encounters/{records["encounterId"]}'
    return {
        "patient": client.call("GET", f'/api/v1/patients/{records["patientId"]}'),
        "timeline": client.call("GET", encounter + "/timeline"),
        "responsibility": client.call("GET", encounter + "/physician-assignments"),
        "physician": client.call("GET", f'/api/v1/physicians/{records["physicianId"]}'),
        "departments": client.call("GET", "/api/v1/departments"),
        "wards": client.call("GET", "/api/v1/wards"),
        "beds": client.call("GET", "/api/v1/beds")
    }


def seed(operator, admin):
    departments = operator.call("GET", "/api/v1/departments?active=true")
    wards = operator.call("GET", "/api/v1/wards?active=true")
    department = next(item for item in departments if item["departmentCode"] == "DEMO-MED")
    other_department = next(item for item in departments if item["departmentCode"] == "DEMO-REHAB")
    first_ward = next(item for item in wards if item["wardCode"] == "DEMO-WARD-1")
    second_ward = next(item for item in wards if item["wardCode"] == "DEMO-WARD-2")
    beds = operator.call("GET", "/api/v1/beds?active=true&occupied=false")
    first_bed = next(item for item in beds if item["wardId"] == first_ward["id"])
    second_bed = next(item for item in beds if item["wardId"] == second_ward["id"])
    run_id = uuid4().hex[:12]
    patient = operator.call("POST", "/api/v1/patients", {
        "medicalRecordNumber": f"DEPLOY-{run_id}", "firstName": "Deployment", "lastName": "Test", "dateOfBirth": "1990-01-01"
    }, 201)
    physician = admin.call("POST", "/api/v1/physicians", {
        "physicianCode": f"DR-{run_id}", "firstName": "Deployment", "lastName": "Physician",
        "departmentIds": [department["id"], other_department["id"]]
    }, 201)
    encounter = operator.call("POST", "/api/v1/encounters", {
        "patientId": patient["id"], "encounterNumber": f"DEPLOY-{run_id}", "admittedAt": now()
    }, 201)
    path = f'/api/v1/encounters/{encounter["id"]}'
    first = operator.call("POST", path + "/department-admissions", {
        "departmentId": department["id"], "wardId": first_ward["id"], "bedId": first_bed["id"], "startedAt": now()
    }, 201)
    operator.call("POST", path + "/physician-assignments", {
        "physicianId": physician["id"], "expectedLocationId": first["id"], "expectedAssignmentId": None, "startedAt": now()
    }, 201)
    second = operator.call("POST", path + "/transfers", {
        "departmentId": other_department["id"], "wardId": second_ward["id"], "bedId": second_bed["id"],
        "expectedLocationId": first["id"], "transferredAt": now()
    }, 201)
    operator.call("POST", path + "/physician-assignments", {
        "physicianId": physician["id"], "expectedLocationId": second["id"], "expectedAssignmentId": None, "startedAt": now()
    }, 201)
    records = {"patientId": patient["id"], "encounterId": encounter["id"], "physicianId": physician["id"]}
    saved = snapshot(operator, records)
    check(saved["timeline"]["encounter"]["status"] == "IN_DEPARTMENT", "Expected an active stay")
    check(len(saved["timeline"]["locations"]) == 2, "Expected closed and current location history")
    check(len(saved["responsibility"]["assignments"]) == 2, "Expected closed and current physician history")
    check(saved["responsibility"]["currentAssignmentId"] is not None, "Expected current responsibility")
    check(operator.call("GET", f'/api/v1/beds/{first_bed["id"]}')["occupied"] is False, "Old bed was not released")
    check(operator.call("GET", f'/api/v1/beds/{second_bed["id"]}')["occupied"] is True, "Current bed was not occupied")
    return {"records": records, "snapshot": saved}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["seed", "verify"])
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--state", type=Path, required=True)
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")
    url = urlsplit(base_url)
    check(url.scheme == "http" and url.hostname in {"localhost", "127.0.0.1", "::1"}
          and not url.username and not url.password and not url.path and not url.query and not url.fragment,
          "Smoke tests require a loopback HTTP URL for an isolated local stack")
    check(args.mode != "seed" or not args.state.exists(), "Refusing to replace an existing smoke-test snapshot")
    anonymous = Client(base_url)
    check(anonymous.call("GET", "/actuator/health")["status"] == "UP", "Application is not healthy")
    check("Sign in" in anonymous.call("GET", "/login.html"), "Login page is missing")
    anonymous.call("GET", "/api/v1/patients", status=401)
    clients = []
    try:
        for role in ["OPERATOR", "VIEWER", "ADMIN"]:
            client = Client(base_url)
            client.login(role)
            clients.append(client)
        operator, viewer, admin = clients
        check("Patients" in operator.call("GET", "/"), "Workbench is missing")
        check("loadPatients" in operator.call("GET", "/patients.js"), "Workbench script is missing")
        check("Physicians" in admin.call("GET", "/physicians.html"), "Directory page is missing")
        if args.mode == "seed":
            state = seed(operator, admin)
            args.state.parent.mkdir(parents=True, exist_ok=True)
            args.state.write_text(json.dumps(state, indent=2), encoding="utf-8")
        else:
            state = json.loads(args.state.read_text(encoding="utf-8"))
            check(snapshot(operator, state["records"]) == state["snapshot"], "Persisted care or reference data changed after recreation")
        check(snapshot(viewer, state["records"]) == state["snapshot"], "Viewer cannot read the same care history")
        forbidden = viewer.call("POST", "/api/v1/patients", {
            "medicalRecordNumber": "FORBIDDEN", "firstName": "Test", "lastName": "Viewer", "dateOfBirth": "1990-01-01"
        }, 403)
        check(forbidden["title"] == "Access denied", "Viewer write failed for a reason other than authorization")
        admin.call("GET", "/api/v1/patients", status=403)
        print(f"Deployment {args.mode} passed: three roles, pages, care history, physician responsibility and bed occupancy.")
    finally:
        for client in clients:
            client.call("POST", "/api/auth/logout", status=204)


if __name__ == "__main__":
    main()
