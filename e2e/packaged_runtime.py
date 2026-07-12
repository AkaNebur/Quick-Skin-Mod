"""Packaged-jar Minecraft runtime used by the Phase 3 release gate.

This module deliberately launches production loader installations.  It never
adds a Gradle source set, a Loom development output, or a remapped cache copy of
Quick Skin to the game classpath.
"""

from __future__ import annotations

import hashlib
import importlib.metadata
import json
import os
import re
import shutil
import signal
import socket
import struct
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any, BinaryIO


FATAL_LOG_PATTERNS = (
    re.compile(r"(?i)mixin.*(?:apply|inject|target).*(?:fail|error)"),
    re.compile(r"(?i)(?:InvalidInjectionException|InjectionError|MixinApplyError)"),
    re.compile(r"(?i)access widener.*(?:fail|error|invalid)"),
    re.compile(
        r"(?:NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|"
        r"AbstractMethodError|VerifyError|LinkageError)"
    ),
    re.compile(r"(?i)@ExpectPlatform.*(?:assert|not transformed|missing)"),
    re.compile(r"(?i)(?:ModLoadingException|Failed to load mod|Incompatible mod set)"),
    re.compile(r"(?i)missing or unsupported mandatory dependencies"),
    re.compile(r"(?i)minecraft game provider couldn't locate the game"),
    re.compile(r"(?i)couldn't load (?:function|tag)"),
    re.compile(r"(?i)crash report saved to"),
)

EXPECTED_STEPS: dict[tuple[str, str], list[str]] = {
    ("phase0-smoke", "client_a"): ["baseline", "apply_local_skin"],
    ("propagation", "client_a"): ["baseline", "apply_local_look"],
    ("propagation", "client_b"): [
        "baseline",
        "confirm_self",
        "await_propagation",
        "observe_a",
    ],
    ("propagation-live", "client_a"): [
        "baseline",
        "await_observer_settled",
        "apply_live",
    ],
    ("propagation-live", "client_b"): [
        "baseline",
        "confirm_self",
        "observe_before",
        "await_live_change",
    ],
    ("full", "client_a"): [
        "baseline",
        "local_skin_apply",
        "skin_menu_screen",
        "model_slim",
        "model_classic",
        "cape_menu_screen",
        "known_cape_apply",
        "cape_adjust_screen",
        "animated_cape_apply",
        "animated_cape_advance",
        "hd_cape_no_downscale",
        "elytra_hides_cape",
        "settings_screen",
        "rename_dialog",
        "delete_dialog",
        "hud_preview_overlay",
    ],
}

EXPECTED_SCREENSHOT_STEPS: dict[tuple[str, str], set[str]] = {
    ("phase0-smoke", "client_a"): {"baseline", "apply_local_skin"},
    ("propagation", "client_a"): {"baseline", "apply_local_look"},
    ("propagation", "client_b"): {"baseline", "observe_a"},
    ("propagation-live", "client_a"): {"baseline", "apply_live"},
    ("propagation-live", "client_b"): {"baseline", "observe_before", "await_live_change"},
    ("full", "client_a"): set(EXPECTED_STEPS[("full", "client_a")]),
}


class RuntimeFailure(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def allocate_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def safe_id(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value)


def java_executable(major: int) -> str:
    candidates = [
        os.environ.get(f"QUICKSKIN_JAVA_{major}"),
        os.environ.get(f"JAVA_HOME_{major}_X64"),
        os.environ.get(f"JAVA_HOME_{major}_x64"),
    ]
    for candidate in candidates:
        if not candidate:
            continue
        path = Path(candidate)
        if path.is_dir():
            path = path / "bin" / ("java.exe" if os.name == "nt" else "java")
        if path.is_file():
            return str(path)
    if os.environ.get("JAVA_HOME"):
        path = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if path.is_file() and detected_java_major(str(path)) == major:
            return str(path)
    found = shutil.which("java")
    if found and detected_java_major(found) == major:
        return found
    raise RuntimeFailure(
        f"Java {major} not found; configure QUICKSKIN_JAVA_{major} or JAVA_HOME_{major}_X64"
    )


def detected_java_major(java: str) -> int | None:
    try:
        output = subprocess.check_output(
            [java, "-version"], stderr=subprocess.STDOUT, text=True, timeout=15
        )
    except (OSError, subprocess.SubprocessError):
        return None
    match = re.search(r'version "(?:1\.)?(\d+)', output)
    return int(match.group(1)) if match else None


def download(url: str, destination: Path, expected_sha256: str | None = None) -> Path:
    if not url.startswith("https://"):
        raise RuntimeFailure(f"refusing non-HTTPS runtime download: {url}")
    if destination.is_file() and (expected_sha256 is None or sha256(destination) == expected_sha256):
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".part")
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "AkaNebur/Quick-Skin-Mod packaged-e2e"},
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as out:
            shutil.copyfileobj(response, out)
    except Exception as exc:
        temporary.unlink(missing_ok=True)
        raise RuntimeFailure(f"failed to download {url}: {exc}") from exc
    if expected_sha256 and sha256(temporary) != expected_sha256:
        actual = sha256(temporary)
        temporary.unlink(missing_ok=True)
        raise RuntimeFailure(
            f"download SHA-256 mismatch for {url}: expected {expected_sha256}, got {actual}"
        )
    temporary.replace(destination)
    return destination


def copy_verified(source: Path, destination_dir: Path, expected_sha256: str) -> Path:
    if not source.is_file():
        raise RuntimeFailure(f"package source does not exist: {source}")
    actual = sha256(source)
    if actual != expected_sha256:
        raise RuntimeFailure(
            f"package source hash mismatch for {source.name}: expected {expected_sha256}, got {actual}"
        )
    destination_dir.mkdir(parents=True, exist_ok=True)
    destination = destination_dir / source.name
    shutil.copy2(source, destination)
    if sha256(destination) != expected_sha256:
        raise RuntimeFailure(f"installed package hash mismatch for {destination}")
    return destination


def maven_dependency_url(loader: str, version: str, fabric_api: bool = False) -> tuple[str, str]:
    if fabric_api:
        filename = f"fabric-api-{version}.jar"
        encoded = urllib.parse.quote(version, safe="+.-_")
        return (
            f"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{encoded}/{filename}",
            filename,
        )
    module = {
        "fabric": "architectury-fabric",
        "forge": "architectury-forge",
        "neoforge": "architectury-neoforge",
    }[loader]
    filename = f"{module}-{version}.jar"
    return (
        f"https://maven.architectury.dev/dev/architectury/{module}/{version}/{filename}",
        filename,
    )


def runtime_dependencies(
    row: dict[str, Any], cache: Path
) -> list[tuple[Path, str]]:
    dependencies: list[tuple[Path, str]] = []
    if row["loader"] == "fabric":
        api_url, api_name = maven_dependency_url("fabric", row["fabric_api"], fabric_api=True)
        api = download(api_url, cache / api_name)
        dependencies.append((api, sha256(api)))

    architectury = row["architectury"]
    if architectury["kind"] != "maven":
        raise RuntimeFailure(f"unknown Architectury dependency kind {architectury['kind']!r}")
    url, name = maven_dependency_url(row["loader"], architectury["version"])
    jar = download(url, cache / name)
    dependencies.append((jar, sha256(jar)))
    return dependencies


def run_checked(
    command: list[str], cwd: Path, log_path: Path, env: dict[str, str], timeout: int = 1800
) -> None:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("wb") as log:
        process = subprocess.run(
            command,
            cwd=cwd,
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
            timeout=timeout,
            check=False,
        )
    if process.returncode:
        tail = log_path.read_text(encoding="utf-8", errors="replace").splitlines()[-40:]
        raise RuntimeFailure(
            f"command failed ({process.returncode}): {' '.join(command)}\n" + "\n".join(tail)
        )


def installer_path(matrix: dict[str, Any], row: dict[str, Any], cache: Path) -> Path:
    installer = matrix["installers"][row["installer"]]
    filename = Path(urllib.parse.urlparse(installer["url"]).path).name
    return download(installer["url"], cache / filename, installer["sha256"])


def ensure_launcher_files(directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    profiles = directory / "launcher_profiles.json"
    if not profiles.exists():
        profiles.write_text('{"profiles":{}}\n', encoding="utf-8")


def installed_version_id(row: dict[str, Any]) -> str:
    if row["loader"] == "fabric":
        return f"fabric-loader-{row['loader_version']}-{row['runtime_version']}"
    if row["loader"] == "forge":
        forge_loader = row["loader_version"].removeprefix(f"{row['runtime_version']}-")
        return f"{row['runtime_version']}-forge-{forge_loader}"
    return f"neoforge-{row['loader_version']}"


def normalize_inherited_profile(version_json: Path, loader: str) -> None:
    """Make inherited loader profiles explicit for minecraft-launcher-lib 8.0.

    Loader installers normally omit ``jar`` and rely on the official launcher
    to select the inherited vanilla jar.  minecraft-launcher-lib instead falls
    back to the loader profile id. Fabric needs an explicit inherited jar.
    Forge/NeoForge intentionally keep the profile-id tail nonexistent: their
    ModLauncher bootstrap owns the transformed Minecraft module, and putting
    the vanilla jar on its classpath creates a duplicate-module failure.
    """
    try:
        data = json.loads(version_json.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeFailure(f"invalid installed loader profile {version_json}: {exc}") from exc
    inherited = data.get("inheritsFrom")
    if inherited:
        if loader != "fabric":
            changed = data.pop("jar", None) is not None
            stale_loader_jar = version_json.with_suffix(".jar")
            if stale_loader_jar.is_file():
                stale_loader_jar.unlink()
            if changed:
                version_json.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
            return
        inherited_jar = version_json.parent.parent / inherited / f"{inherited}.jar"
        if not inherited_jar.is_file():
            raise RuntimeFailure(
                f"installed loader profile {version_json} inherits missing base jar {inherited_jar}"
            )
        selected_jar = inherited
        if data.get("jar") != selected_jar:
            data["jar"] = selected_jar
            version_json.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def prepare_client_install(
    matrix: dict[str, Any], row: dict[str, Any], cache_root: Path, java: str
) -> tuple[Path, str]:
    try:
        version = importlib.metadata.version("minecraft-launcher-lib")
    except importlib.metadata.PackageNotFoundError as exc:
        raise RuntimeFailure(
            "minecraft-launcher-lib is not installed; run `python -m pip install -r e2e/requirements.txt`"
        ) from exc
    if version != "8.0":
        raise RuntimeFailure(f"minecraft-launcher-lib must be exactly 8.0, found {version}")
    import minecraft_launcher_lib.install  # type: ignore[import-not-found]

    key = safe_id(f"{row['loader']}-{row['runtime_version']}-{row['loader_version']}")
    directory = cache_root / "clients" / key
    ensure_launcher_files(directory)
    version_id = installed_version_id(row)
    version_json = directory / "versions" / version_id / f"{version_id}.json"
    if version_json.is_file():
        normalize_inherited_profile(version_json, row["loader"])
        return directory, version_id

    install_log = cache_root / "logs" / f"{key}.log"
    install_log.parent.mkdir(parents=True, exist_ok=True)
    with install_log.open("a", encoding="utf-8") as log:
        log.write(f"Installing vanilla Minecraft {row['runtime_version']}\n")
    minecraft_launcher_lib.install.install_minecraft_version(
        row["runtime_version"], str(directory)
    )
    installer = installer_path(matrix, row, cache_root / "installers")
    if row["loader"] == "fabric":
        arguments = [
            java,
            "-jar",
            str(installer),
            "client",
            "-dir",
            str(directory),
            "-mcversion",
            row["runtime_version"],
            "-loader",
            row["loader_version"],
            "-noprofile",
            "-snapshot",
        ]
    elif row["loader"] == "forge":
        arguments = [java, "-jar", str(installer), "--installClient", str(directory)]
    else:
        # Direct installer invocation avoids minecraft-launcher-lib 8.0's pre-26.x
        # NeoForge version normalizer while retaining its standard command builder.
        arguments = [java, "-jar", str(installer), "--install-client", str(directory)]
    run_checked(arguments, directory, install_log, process_env(java), timeout=1800)
    if not version_json.is_file():
        raise RuntimeFailure(f"loader installer did not create {version_json}")
    normalize_inherited_profile(version_json, row["loader"])
    return directory, version_id


def process_env(java: str) -> dict[str, str]:
    env = os.environ.copy()
    java_home = str(Path(java).resolve().parent.parent)
    env["JAVA_HOME"] = java_home
    env["PATH"] = str(Path(java).resolve().parent) + os.pathsep + env.get("PATH", "")
    return env


def prepare_server(
    matrix: dict[str, Any], row: dict[str, Any], server: Path, cache: Path, java: str, log: Path
) -> list[str]:
    installer = installer_path(matrix, row, cache / "installers")
    env = process_env(java)
    if row["loader"] == "fabric":
        arguments = [
            java,
            "-jar",
            str(installer),
            "server",
            "-dir",
            str(server),
            "-mcversion",
            row["runtime_version"],
            "-loader",
            row["loader_version"],
            "-downloadMinecraft",
        ]
        run_checked(arguments, server, log, env)
        launcher = server / "fabric-server-launch.jar"
        if not launcher.is_file():
            raise RuntimeFailure(f"Fabric server launcher was not created at {launcher}")
        return [java, "-Xms512M", "-Xmx1024M", "-jar", str(launcher), "nogui"]

    flag = "--installServer" if row["loader"] == "forge" else "--install-server"
    run_checked([java, "-jar", str(installer), flag, str(server)], server, log, env)
    (server / "user_jvm_args.txt").write_text("-Xms512M\n-Xmx1024M\n", encoding="utf-8")
    if os.name == "nt":
        script = server / "run.bat"
        if not script.is_file():
            raise RuntimeFailure(f"server installer did not create {script}")
        return ["cmd", "/c", str(script), "nogui"]
    script = server / "run.sh"
    if not script.is_file():
        raise RuntimeFailure(f"server installer did not create {script}")
    return ["bash", str(script), "nogui"]


def write_server_files(server: Path, port: int, template_root: Path) -> None:
    properties = (template_root / "server.properties").read_text(encoding="utf-8")
    properties = re.sub(r"(?m)^server-port=.*$", f"server-port={port}", properties)
    (server / "server.properties").write_text(properties, encoding="utf-8")
    shutil.copy2(template_root / "eula.txt", server / "eula.txt")
    datapack = server / "world" / "datapacks" / "qs_e2e_time"
    datapack.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(template_root / "datapack", datapack, dirs_exist_ok=True)


def client_command(
    install_dir: Path,
    version_id: str,
    game_dir: Path,
    row: dict[str, Any],
    scenario: str,
    role: str,
    username: str,
    port: int,
    java: str,
) -> list[str]:
    import minecraft_launcher_lib.command  # type: ignore[import-not-found]
    import minecraft_launcher_lib.utils  # type: ignore[import-not-found]

    options = minecraft_launcher_lib.utils.generate_test_options()
    options.update(
        {
            "username": username,
            "uuid": uuid.uuid5(uuid.NAMESPACE_DNS, f"quickskin-e2e-{username}").hex,
            "token": "quickskin-e2e-offline",
            "executablePath": java,
            "defaultExecutablePath": java,
            "gameDirectory": str(game_dir),
            "customResolution": True,
            "resolutionWidth": "1280",
            "resolutionHeight": "720",
            "quickPlayMultiplayer": f"127.0.0.1:{port}",
            "jvmArguments": [
                "-Xms512M",
                "-Xmx1024M",
                "-Dquickskin.e2e.enabled=true",
                f"-Dquickskin.e2e.role={role}",
                f"-Dquickskin.e2e.scenario={scenario}",
                f"-Dquickskin.e2e.version={row['runtime_version']}",
                "-Dfml.earlyprogresswindow=false",
            ],
        }
    )
    return minecraft_launcher_lib.command.get_minecraft_command(
        version_id, str(install_dir), options
    )


def start_process(command: list[str], cwd: Path, log_path: Path, env: dict[str, str]) -> tuple[subprocess.Popen[bytes], BinaryIO]:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    handle = log_path.open("wb")
    kwargs: dict[str, Any] = {"start_new_session": True} if os.name != "nt" else {
        "creationflags": subprocess.CREATE_NEW_PROCESS_GROUP
    }
    try:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            env=env,
            stdout=handle,
            stderr=subprocess.STDOUT,
            **kwargs,
        )
    except Exception:
        handle.close()
        raise
    return process, handle


def stop_process(process: subprocess.Popen[bytes] | None) -> None:
    if process is None or process.poll() is not None:
        return
    try:
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=20,
            )
        else:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
    except (OSError, subprocess.SubprocessError):
        process.kill()


def wait_for_log(process: subprocess.Popen[bytes], log: Path, text: str, timeout: int) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        content = log.read_text(encoding="utf-8", errors="replace") if log.exists() else ""
        if text in content:
            return
        if process.poll() is not None:
            raise RuntimeFailure(f"process exited before {text!r}; see {log}")
        time.sleep(2)
    raise RuntimeFailure(f"timed out waiting for {text!r}; see {log}")


def wait_for_marker(
    process: subprocess.Popen[bytes], game_dir: Path, role: str, timeout: int = 600
) -> str:
    marker = game_dir / "e2e-report" / "done.marker"
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if marker.is_file():
            value = marker.read_text(encoding="utf-8").strip()
            if value not in {"pass", "fail"}:
                raise RuntimeFailure(f"invalid {role} done.marker value {value!r}")
            return value
        if process.poll() is not None:
            raise RuntimeFailure(f"{role} exited before writing {marker}")
        time.sleep(2)
    raise RuntimeFailure(f"timed out waiting for {role} marker {marker}")


def png_width(path: Path) -> int:
    try:
        with path.open("rb") as stream:
            header = stream.read(24)
        if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
            return -1
        return struct.unpack(">I", header[16:20])[0]
    except OSError:
        return -1


def validate_report(game_dir: Path, row: dict[str, Any], scenario: str, role: str) -> dict[str, Any]:
    report_path = game_dir / "e2e-report" / "report.json"
    if not report_path.is_file():
        raise RuntimeFailure(f"missing {role} report: {report_path}")
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeFailure(f"unreadable {role} report: {exc}") from exc
    expected_steps = EXPECTED_STEPS.get((scenario, role))
    if expected_steps is None:
        raise RuntimeFailure(f"no locked report contract for {scenario}/{role}")
    if report.get("version") != row["runtime_version"]:
        raise RuntimeFailure(f"{role} report runtime version mismatch")
    if report.get("role") != role or report.get("scenario") != scenario:
        raise RuntimeFailure(f"{role} report identity mismatch")
    steps = report.get("steps")
    if not isinstance(steps, list) or [step.get("name") for step in steps] != expected_steps:
        raise RuntimeFailure(
            f"{role} step contract mismatch: expected {expected_steps}, got "
            f"{[step.get('name') for step in steps] if isinstance(steps, list) else steps}"
        )
    if report.get("status") != "pass" or any(step.get("status") != "pass" for step in steps):
        raise RuntimeFailure(f"{role} report contains a failed/timed-out step")
    screenshot_steps = EXPECTED_SCREENSHOT_STEPS[(scenario, role)]
    for step in steps:
        screenshot = step.get("screenshot")
        if step["name"] in screenshot_steps and not screenshot:
            raise RuntimeFailure(f"{role}/{step['name']} omitted its required screenshot")
        if screenshot:
            screenshot_path = game_dir / "screenshots" / screenshot
            width = png_width(screenshot_path)
            if width < 640:
                raise RuntimeFailure(
                    f"{role}/{step['name']} screenshot missing or undersized: {screenshot_path} ({width}px)"
                )
    return report


def scan_runtime_logs(logs: list[Path]) -> None:
    hits: list[str] = []
    for log in logs:
        if not log.is_file():
            raise RuntimeFailure(f"runtime log missing: {log}")
        content = log.read_text(encoding="utf-8", errors="replace")
        if "client" in log.stem.lower() and "[QS-E2E] FINISHED status=pass" not in content:
            hits.append(f"{log}: missing [QS-E2E] FINISHED status=pass")
        for line_number, line in enumerate(content.splitlines(), start=1):
            if any(pattern.search(line) for pattern in FATAL_LOG_PATTERNS):
                hits.append(f"{log}:{line_number}: {line[:300]}")
    if hits:
        raise RuntimeFailure("fatal runtime log evidence:\n" + "\n".join(hits[:30]))


def artifact_record(manifest: dict[str, Any], node: str) -> dict[str, Any]:
    records = [record for record in manifest.get("artifacts", []) if record.get("artifact_node") == node]
    if len(records) != 1:
        raise RuntimeFailure(f"artifact manifest has {len(records)} records for {node}")
    return records[0]


def run_packaged_row(
    repo: Path,
    matrix: dict[str, Any],
    row: dict[str, Any],
    scenario: str,
    manifest: dict[str, Any],
    manifest_path: Path,
    output_root: Path,
) -> dict[str, Any]:
    port = allocate_port()
    identity = safe_id(f"{row['artifact_node']}--{row['runtime_version']}--{scenario}")
    profiles_root = output_root / "profiles"
    profile = profiles_root / identity
    profiles_root.mkdir(parents=True, exist_ok=True)
    if profile.exists():
        shutil.rmtree(profile)
    profile.mkdir(parents=True)

    result: dict[str, Any] = {
        "artifact_node": row["artifact_node"],
        "runtime_version": row["runtime_version"],
        "loader": row["loader"],
        "scenario": scenario,
        "jar_sha256": None,
        "port": port,
        "status": "fail",
        "profile": profile.relative_to(output_root).as_posix(),
    }
    result_path = profile / "result.json"
    started = time.monotonic()
    processes: list[subprocess.Popen[bytes]] = []
    handles: list[BinaryIO] = []
    runtime_logs: list[Path] = []
    try:
        record = artifact_record(manifest, row["artifact_node"])
        if record["loader"] != row["loader"]:
            raise RuntimeFailure("artifact manifest loader mismatch")
        result["jar_sha256"] = record["sha256"]
        stage = manifest_path.parent
        release_jar = stage / record["path"]
        harness_jar = stage / record["harness"]["path"]
        if sha256(release_jar) != record["sha256"]:
            raise RuntimeFailure(f"fan-in artifact hash mismatch: {release_jar}")
        if sha256(harness_jar) != record["harness"]["sha256"]:
            raise RuntimeFailure(f"fan-in harness hash mismatch: {harness_jar}")

        java = java_executable(int(row["java"]))
        cache = output_root / "cache"
        dependencies = runtime_dependencies(row, cache / "dependencies")
        server = profile / "server"
        client_a = profile / "client_a"
        client_b = profile / "client_b"
        for directory in (server, client_a):
            directory.mkdir(parents=True)
        two_clients = scenario.startswith("propagation")
        if two_clients:
            client_b.mkdir(parents=True)

        server_install_log = profile / "logs" / "server-install.log"
        server_command = prepare_server(matrix, row, server, cache, java, server_install_log)
        write_server_files(server, port, repo / "e2e" / "server-template")
        install_dir, version_id = prepare_client_install(matrix, row, cache, java)

        installed_quickskin: list[dict[str, str]] = []
        for game_dir in (server, client_a, *([client_b] if two_clients else [])):
            destination = copy_verified(release_jar, game_dir / "mods", record["sha256"])
            installed_quickskin.append(
                {"path": destination.relative_to(profile).as_posix(), "sha256": sha256(destination)}
            )
            for dependency, dependency_hash in dependencies:
                copy_verified(dependency, game_dir / "mods", dependency_hash)
        for game_dir in (client_a, *([client_b] if two_clients else [])):
            copy_verified(harness_jar, game_dir / "mods", record["harness"]["sha256"])
            shutil.copy2(repo / "e2e" / "options.txt.template", game_dir / "options.txt")
            if row["loader"] == "neoforge":
                (game_dir / "config").mkdir(parents=True, exist_ok=True)
                shutil.copy2(repo / "e2e" / "fml.toml.neoforge", game_dir / "config" / "fml.toml")
        result["installed_quickskin"] = installed_quickskin

        env = process_env(java)
        server_log = profile / "logs" / "server.log"
        server_process, server_handle = start_process(server_command, server, server_log, env)
        processes.append(server_process)
        handles.append(server_handle)
        runtime_logs.append(server_log)
        wait_for_log(server_process, server_log, "Done (", timeout=1200)

        client_a_log = profile / "logs" / "client_a.log"
        command_a = client_command(
            install_dir, version_id, client_a, row, scenario, "client_a", "Alice", port, java
        )
        process_a, handle_a = start_process(command_a, client_a, client_a_log, env)
        processes.append(process_a)
        handles.append(handle_a)
        runtime_logs.append(client_a_log)

        marker_b = "n/a"
        if scenario == "propagation-live":
            wait_for_log(process_a, server_log, "Alice joined the game", timeout=300)
            client_b_log = profile / "logs" / "client_b.log"
            command_b = client_command(
                install_dir, version_id, client_b, row, scenario, "client_b", "Bob", port, java
            )
            process_b, handle_b = start_process(command_b, client_b, client_b_log, env)
            processes.append(process_b)
            handles.append(handle_b)
            runtime_logs.append(client_b_log)
            marker_a = wait_for_marker(process_a, client_a, "client_a")
            marker_b = wait_for_marker(process_b, client_b, "client_b")
        else:
            marker_a = wait_for_marker(process_a, client_a, "client_a")
            if two_clients:
                client_b_log = profile / "logs" / "client_b.log"
                command_b = client_command(
                    install_dir, version_id, client_b, row, scenario, "client_b", "Bob", port, java
                )
                process_b, handle_b = start_process(command_b, client_b, client_b_log, env)
                processes.append(process_b)
                handles.append(handle_b)
                runtime_logs.append(client_b_log)
                marker_b = wait_for_marker(process_b, client_b, "client_b")

        if marker_a != "pass" or (two_clients and marker_b != "pass"):
            raise RuntimeFailure(f"harness marker failure: A={marker_a}, B={marker_b}")
        reports = {"client_a": validate_report(client_a, row, scenario, "client_a")}
        if two_clients:
            reports["client_b"] = validate_report(client_b, row, scenario, "client_b")
        scan_runtime_logs(runtime_logs)
        crash_reports = list(profile.rglob("crash-reports/*.txt"))
        if crash_reports:
            raise RuntimeFailure(f"runtime produced crash reports: {crash_reports}")
        result["reports"] = reports
        result["status"] = "pass"
    except Exception as exc:
        result["error"] = str(exc)
    finally:
        for process in reversed(processes):
            stop_process(process)
        for handle in handles:
            handle.close()
        result["elapsed_s"] = round(time.monotonic() - started, 1)
        result_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result
