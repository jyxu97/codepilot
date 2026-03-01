#!/usr/bin/env python3
"""
CodePilot Smoke Test
====================
End-to-end validation of the orchestrator → executor → sandbox → Maven → JDK
chain **without spending any Claude API credits**.

Usage:
    python3 smoke_test.py                          # default localhost ports
    python3 smoke_test.py --executor http://host:8001 --orchestrator http://host:8080

Exit code 0 = all checks passed, 1 = at least one failed.
"""
import argparse
import sys
import time
import uuid

import requests

# ---------------------------------------------------------------------------
# Colours
# ---------------------------------------------------------------------------
GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
BOLD = "\033[1m"
RESET = "\033[0m"

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
EXECUTOR_DEFAULT = "http://localhost:8001"
ORCHESTRATOR_DEFAULT = "http://localhost:8080"
WORKSPACE_REF = f"smoke-test-{uuid.uuid4().hex[:8]}"

# LANG-1814 — a real Apache Commons Lang bug used in the benchmark.
LANG1814_REPO = "https://github.com/yvie97/commons-lang.git"
LANG1814_REF = "93f53a58604264ae105e2327a2b8713b84b296bb"
LANG1814_TEST_FILTER = "ArrayUtilsTest#testSubarrayInt"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
results: list[tuple[str, bool, str]] = []


def check(name: str, passed: bool, detail: str = ""):
    """Record and print a single check result."""
    tag = f"{GREEN}PASS{RESET}" if passed else f"{RED}FAIL{RESET}"
    msg = f"  [{tag}] {name}"
    if detail:
        msg += f"  — {detail}"
    print(msg)
    results.append((name, passed, detail))
    return passed


def section(title: str):
    print(f"\n{BOLD}▸ {title}{RESET}")


# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------

def check_docker_services(executor_url: str, orchestrator_url: str):
    """1. Docker services health — executor, orchestrator (postgres implied)."""
    section("Docker services health")

    # Executor /workspace/health
    try:
        r = requests.get(f"{executor_url}/workspace/health", timeout=5)
        body = r.json()
        check("Executor health", r.status_code == 200 and body.get("status") == "ok")
    except Exception as e:
        check("Executor health", False, str(e))

    # Orchestrator /actuator/health
    try:
        r = requests.get(f"{orchestrator_url}/actuator/health", timeout=5)
        body = r.json()
        check(
            "Orchestrator health",
            r.status_code == 200 and body.get("status") == "UP",
            f"status={body.get('status')}",
        )
    except Exception as e:
        check("Orchestrator health", False, str(e))


def check_workspace_lifecycle(executor_url: str) -> bool:
    """2. Executor workspace lifecycle: create → list → snapshot → restore → (delete later)."""
    section("Executor workspace lifecycle")

    # Create
    try:
        r = requests.post(
            f"{executor_url}/workspace/create",
            json={
                "workspace_ref": WORKSPACE_REF,
                "repo_url": LANG1814_REPO,
                "git_ref": LANG1814_REF,
            },
            timeout=120,
        )
        body = r.json()
        ok = r.status_code == 200 and body.get("success") is True
        check("Workspace create (clone LANG-1814)", ok, body.get("message", ""))
        if not ok:
            return False
    except Exception as e:
        check("Workspace create (clone LANG-1814)", False, str(e))
        return False

    # List files via run_code
    try:
        r = requests.post(
            f"{executor_url}/workspace/run_code",
            json={
                "workspace_ref": WORKSPACE_REF,
                "code": "print(list_files('.', 'pom.xml'))",
                "timeout_sec": 15,
            },
            timeout=20,
        )
        body = r.json()
        ok = body.get("exit_code") == 0 and "pom.xml" in body.get("stdout", "")
        check("List files (pom.xml present)", ok, body.get("stdout", "").strip()[:120])
    except Exception as e:
        check("List files (pom.xml present)", False, str(e))

    # Snapshot
    snapshot_key = None
    try:
        r = requests.post(
            f"{executor_url}/workspace/snapshot",
            json={"workspace_ref": WORKSPACE_REF},
            timeout=30,
        )
        body = r.json()
        snapshot_key = body.get("snapshot_key")
        ok = r.status_code == 200 and snapshot_key is not None
        check("Snapshot create", ok, f"key={snapshot_key}")
    except Exception as e:
        check("Snapshot create", False, str(e))

    # Restore
    if snapshot_key:
        try:
            r = requests.post(
                f"{executor_url}/workspace/restore",
                json={
                    "workspace_ref": WORKSPACE_REF,
                    "snapshot_key": snapshot_key,
                },
                timeout=30,
            )
            body = r.json()
            ok = r.status_code == 200 and body.get("success") is True
            check("Snapshot restore", ok, body.get("message", ""))
        except Exception as e:
            check("Snapshot restore", False, str(e))
    else:
        check("Snapshot restore", False, "skipped — no snapshot key")

    return True


def check_sandbox_execution(executor_url: str):
    """3. Sandbox code execution — run Python code, verify stdout/stderr."""
    section("Sandbox code execution")
    # Note: the sandbox validator restricts imports (only allowlisted
    # modules). We test stdout via print() and exercise a built-in
    # tool (list_files) to confirm the sandbox toolchain works.
    try:
        r = requests.post(
            f"{executor_url}/workspace/run_code",
            json={
                "workspace_ref": WORKSPACE_REF,
                "code": 'print("hello-smoke-" + str(1 + 2))',
                "timeout_sec": 10,
            },
            timeout=15,
        )
        body = r.json()
        ok = "hello-smoke-3" in body.get("stdout", "")
        check(
            "Python stdout capture",
            ok,
            body.get("stdout", "").strip()[:80],
        )
        check(
            "Python exit code == 0",
            body.get("exit_code") == 0,
        )
    except Exception as e:
        check("Python stdout capture", False, str(e))
        check("Python exit code == 0", False, str(e))


def check_tool_reliability(executor_url: str):
    """4. Tool reliability — write/read/diff/patch cycle."""
    section("Tool reliability (write → read → diff → patch)")

    # write_file returns confirmation string
    try:
        code = (
            'msg = write_file("smoke_test_tmp.txt", "hello\\n")\n'
            'print(msg)'
        )
        r = requests.post(
            f"{executor_url}/workspace/run_code",
            json={
                "workspace_ref": WORKSPACE_REF,
                "code": code,
                "timeout_sec": 10,
            },
            timeout=15,
        )
        body = r.json()
        stdout = body.get("stdout", "")
        check(
            "write_file returns confirmation",
            "Wrote" in stdout and "characters" in stdout,
            stdout.strip()[:80],
        )
    except Exception as e:
        check("write_file returns confirmation", False, str(e))

    # read_file returns what was written
    try:
        code = 'print(repr(read_file("smoke_test_tmp.txt")))'
        r = requests.post(
            f"{executor_url}/workspace/run_code",
            json={
                "workspace_ref": WORKSPACE_REF,
                "code": code,
                "timeout_sec": 10,
            },
            timeout=15,
        )
        body = r.json()
        check(
            "read_file round-trip",
            "hello" in body.get("stdout", ""),
            body.get("stdout", "").strip()[:80],
        )
    except Exception as e:
        check("read_file round-trip", False, str(e))

    # git_diff detects changes on a TRACKED file
    # (untracked files don't appear in git diff HEAD)
    try:
        code = (
            'content = read_file("pom.xml")\n'
            'write_file("pom.xml", content + "<!-- smoke -->")\n'
            'diff = git_diff("HEAD")\n'
            'print(diff[:500])\n'
        )
        r = requests.post(
            f"{executor_url}/workspace/run_code",
            json={
                "workspace_ref": WORKSPACE_REF,
                "code": code,
                "timeout_sec": 10,
            },
            timeout=15,
        )
        body = r.json()
        stdout = body.get("stdout", "")
        check(
            "git_diff detects uncommitted change",
            "pom.xml" in stdout and "smoke" in stdout,
            f"{len(stdout)} chars of diff",
        )
    except Exception as e:
        check("git_diff detects uncommitted change", False, str(e))

    # apply_patch with bad diff raises RuntimeError
    try:
        code = (
            'try:\n'
            '    apply_patch("not a valid diff")\n'
            '    print("ERROR: no exception raised")\n'
            'except RuntimeError as e:\n'
            '    print("OK: RuntimeError:", str(e)[:80])\n'
        )
        r = requests.post(
            f"{executor_url}/workspace/run_code",
            json={
                "workspace_ref": WORKSPACE_REF,
                "code": code,
                "timeout_sec": 10,
            },
            timeout=15,
        )
        body = r.json()
        stdout = body.get("stdout", "")
        check(
            "apply_patch bad diff → RuntimeError",
            "OK: RuntimeError" in stdout,
            stdout.strip()[:80],
        )
    except Exception as e:
        check("apply_patch bad diff → RuntimeError", False, str(e))

    # Clean up: reset workspace to HEAD
    try:
        requests.post(
            f"{executor_url}/workspace/run_code",
            json={
                "workspace_ref": WORKSPACE_REF,
                "code": 'git_reset("HEAD")',
                "timeout_sec": 10,
            },
            timeout=15,
        )
    except Exception:
        pass  # best-effort cleanup


def check_sandbox_timeout(executor_url: str):
    """5. Sandbox timeout — verify TIMEOUT error_type on long-running code."""
    section("Sandbox timeout handling")
    try:
        # Request a 3-second timeout, run an infinite loop.
        # (import time is blocked by sandbox; use a busy loop instead)
        code = 'while True: pass\n'
        r = requests.post(
            f"{executor_url}/workspace/run_code",
            json={
                "workspace_ref": WORKSPACE_REF,
                "code": code,
                "timeout_sec": 3,
            },
            timeout=15,
        )
        body = r.json()
        check(
            "Timeout returns error_type=TIMEOUT",
            body.get("error_type") == "TIMEOUT",
            f"error_type={body.get('error_type')}, "
            f"exit_code={body.get('exit_code')}",
        )
        check(
            "Timeout stderr mentions timeout",
            "timed out" in body.get("stderr", "").lower(),
            body.get("stderr", "").strip()[:80],
        )
    except Exception as e:
        check("Timeout returns error_type=TIMEOUT", False, str(e))
        check("Timeout stderr mentions timeout", False, str(e))


def check_disallowed_command(executor_url: str):
    """6. Policy enforcement — blocked command raises POLICY_VIOLATION."""
    section("Sandbox policy enforcement")
    try:
        code = 'run_command(["curl", "http://example.com"])'
        r = requests.post(
            f"{executor_url}/workspace/run_code",
            json={
                "workspace_ref": WORKSPACE_REF,
                "code": code,
                "timeout_sec": 10,
            },
            timeout=15,
        )
        body = r.json()
        stderr = body.get("stderr", "")
        ok = (
            body.get("error_type") == "POLICY_VIOLATION"
            or "not allowed" in stderr.lower()
            or "PermissionError" in stderr
        )
        check(
            "Blocked command → POLICY_VIOLATION",
            ok,
            stderr.strip()[:100],
        )
    except Exception as e:
        check("Blocked command → POLICY_VIOLATION", False, str(e))


def check_maven_execution(executor_url: str):
    """7. Maven execution in sandbox — run a real mvn test and verify output."""
    section("Maven execution in sandbox (may take 60-90s on first run)")
    try:
        code = (
            'result = run_command(["mvn", "test", '
            f'"-Dtest={LANG1814_TEST_FILTER}"], timeout=300)\n'
            'print("EXIT:", result["exit_code"])\n'
            'print("STDOUT:", result["stdout"][-3000:])\n'
            'print("STDERR:", result["stderr"][-3000:])\n'
        )
        r = requests.post(
            f"{executor_url}/workspace/run_code",
            json={
                "workspace_ref": WORKSPACE_REF,
                "code": code,
                "timeout_sec": 600,
            },
            timeout=660,
        )
        body = r.json()
        stdout = body.get("stdout", "")
        stderr = body.get("stderr", "")
        combined = stdout + stderr

        # Maven should have run — we expect test FAILURE because the bug is unfixed.
        ran_tests = "Tests run:" in combined or "BUILD FAILURE" in combined
        check(
            "Maven ran tests",
            ran_tests,
            next(
                (line.strip() for line in combined.splitlines()
                 if "Tests run:" in line),
                "no 'Tests run:' line",
            )[:120],
        )

        # The sandbox run_code exit_code should be 0 (the Python wrapper succeeded),
        # but the Maven exit_code printed inside should be 1 (test failure).
        has_exit_1 = "EXIT: 1" in stdout
        check(
            "Maven exit_code == 1 (expected test failure)",
            has_exit_1,
            "unfixed bug → test fails as expected" if has_exit_1 else stdout[:120],
        )
    except Exception as e:
        check("Maven ran tests", False, str(e))
        check("Maven exit_code == 1 (expected test failure)", False, str(e))


def check_jdk_maven_installed(executor_url: str):
    """8. JDK & Maven installed — verify java and mvn are on PATH."""
    section("JDK & Maven installed")
    # java -version
    try:
        code = (
            'r = run_command(["java", "-version"], timeout=10)\n'
            'print(r["stderr"][:200])\n'
        )
        r = requests.post(
            f"{executor_url}/workspace/run_code",
            json={
                "workspace_ref": WORKSPACE_REF,
                "code": code,
                "timeout_sec": 15,
            },
            timeout=20,
        )
        body = r.json()
        stdout = body.get("stdout", "")
        check(
            "java -version",
            "version" in stdout.lower(),
            stdout.strip().splitlines()[0][:80] if stdout.strip() else "",
        )
    except Exception as e:
        check("java -version", False, str(e))

    # mvn --version
    try:
        code = (
            'r = run_command(["mvn", "--version"], timeout=10)\n'
            'print(r["stdout"][:200])\n'
        )
        r = requests.post(
            f"{executor_url}/workspace/run_code",
            json={
                "workspace_ref": WORKSPACE_REF,
                "code": code,
                "timeout_sec": 15,
            },
            timeout=20,
        )
        body = r.json()
        stdout = body.get("stdout", "")
        check(
            "mvn --version",
            "Maven" in stdout,
            stdout.strip().splitlines()[0][:80] if stdout.strip() else "",
        )
    except Exception as e:
        check("mvn --version", False, str(e))


def check_orchestrator_api(orchestrator_url: str):
    """9. Orchestrator API — verify /jobs endpoint is reachable."""
    section("Orchestrator API")

    # GET /jobs/{random-uuid} should return 404 (not 500 or connection error)
    try:
        fake_id = str(uuid.uuid4())
        r = requests.get(f"{orchestrator_url}/jobs/{fake_id}", timeout=5)
        ok = r.status_code in (404, 400)
        check(
            "GET /jobs/{id} reachable",
            ok,
            f"status={r.status_code} (expected 404 for nonexistent job)",
        )
    except Exception as e:
        check("GET /jobs/{id} reachable", False, str(e))


def cleanup_workspace(executor_url: str):
    """6. Delete test workspace."""
    section("Cleanup")
    try:
        r = requests.delete(
            f"{executor_url}/workspace/{WORKSPACE_REF}",
            timeout=30,
        )
        body = r.json()
        ok = r.status_code == 200 and body.get("success") is True
        check("Delete test workspace", ok, body.get("message", ""))
    except Exception as e:
        check("Delete test workspace", False, str(e))


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="CodePilot smoke test")
    parser.add_argument(
        "--executor",
        default=EXECUTOR_DEFAULT,
        help=f"Executor base URL (default: {EXECUTOR_DEFAULT})",
    )
    parser.add_argument(
        "--orchestrator",
        default=ORCHESTRATOR_DEFAULT,
        help=f"Orchestrator base URL (default: {ORCHESTRATOR_DEFAULT})",
    )
    args = parser.parse_args()

    print(f"{BOLD}CodePilot Smoke Test{RESET}")
    print(f"  executor:     {args.executor}")
    print(f"  orchestrator: {args.orchestrator}")
    print(f"  workspace:    {WORKSPACE_REF}")
    start = time.time()

    # Run all checks in order
    check_docker_services(args.executor, args.orchestrator)
    ws_ok = check_workspace_lifecycle(args.executor)
    if ws_ok:
        check_sandbox_execution(args.executor)
        check_tool_reliability(args.executor)
        check_sandbox_timeout(args.executor)
        check_disallowed_command(args.executor)
        check_maven_execution(args.executor)
        check_jdk_maven_installed(args.executor)
    else:
        section("Sandbox code execution")
        check("Python stdout capture", False,
              "skipped — workspace not created")
        section("Tool reliability")
        check("write_file returns confirmation", False,
              "skipped — workspace not created")
        section("Maven execution in sandbox")
        check("Maven ran tests", False,
              "skipped — workspace not created")
    check_orchestrator_api(args.orchestrator)
    if ws_ok:
        cleanup_workspace(args.executor)

    # Summary
    elapsed = time.time() - start
    passed = sum(1 for _, ok, _ in results if ok)
    failed = sum(1 for _, ok, _ in results if not ok)
    colour = GREEN if failed == 0 else RED
    print(f"\n{BOLD}Summary:{RESET} {passed} passed, {colour}{failed} failed{RESET}  ({elapsed:.1f}s)")

    if failed:
        print(f"\n{RED}Failed checks:{RESET}")
        for name, ok, detail in results:
            if not ok:
                print(f"  • {name}: {detail}")

    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
