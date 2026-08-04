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

KQUEUE_NATIVE_INIT_FAILURE = (
    "java.lang.NoClassDefFoundError: Could not initialize class "
    "io.netty.channel.kqueue.Native"
)
KQUEUE_UNSUPPORTED_PLATFORM_CAUSE = (
    "java.lang.IllegalStateException: Only supported on OSX/BSD"
)
DEBUG_FILE_APPENDER_FAILURE = "An exception occurred processing Appender DebugFile"
DEBUG_FILE_APPENDER_STACK_WINDOW = 96
COMPATIBILITY_LOG_MARKERS = {
    "neoforge-26.1-break-event-v1": (
        "Quick Skin applied Architectury NeoForge 26.1 BreakEvent compatibility patch"
    ),
}

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
        "external_skin_drop",
        "model_slim",
        "model_classic",
        "cape_menu_screen",
        "known_cape_apply",
        "cape_adjust_screen",
        "cape_preview_selected_a",
        "cape_preview_selected_b",
        "cape_adjust_opaque_off",
        "cape_adjust_opaque_on",
        "cape_adjust_zoom_out",
        "cape_adjust_zoom_in",
        "animated_cape_apply",
        "animated_cape_advance",
        "hd_cape_no_downscale",
        "elytra_hides_cape",
        "cape_editor_ignores_elytra",
        "settings_screen",
        "rename_dialog",
        "delete_dialog",
        "hud_preview_overlay",
        "title_screen_splash_order",
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

# Fractional (left, top, right, bottom) crop holding the observed player and no HUD: the toasts sit
# above/right of it, the hotbar below, the held item bottom-right. A whole-frame threshold cannot
# assert that a PLAYER changed, because an idle HUD animation moves more pixels than a body does --
# that is exactly how a stale render once passed this gate. Restricting the comparison to this box
# makes the number mean "the player's pixels changed" instead of "the frames are not identical".
PLAYER_REGION = (0.30, 0.28, 0.60, 0.85)

# A live skin+cape swap moves ~0.10 of the region (measured: 0.106 for a skin change, 0.061 for a
# cape appearing). Idle arm sway between two frames of an UNCHANGED player moves ~0.004. 0.03 sits
# an order of magnitude above the sway floor and well under the smallest real change.
MINIMUM_APPEARANCE_CHANGE = 0.03

# These comparisons assert only invariant visual change, never renderer-specific golden pixels.
# They catch a frozen animation, an ignored apply, or a reused screenshot while tolerating lighting,
# GPU, UI-scale, and version differences.
#
# Entries are (first_step, second_step, minimum_changed_fraction) over the whole frame, or
# (first_step, second_step, minimum_changed_fraction, region) to restrict the comparison to a
# fractional crop.
ScreenshotPair = (
    tuple[str, str, float] | tuple[str, str, float, tuple[float, float, float, float]]
)
DISTINCT_SCREENSHOT_PAIRS: dict[tuple[str, str], list[ScreenshotPair]] = {
    ("phase0-smoke", "client_a"): [("baseline", "apply_local_skin", 0.00001)],
    ("propagation", "client_a"): [("baseline", "apply_local_look", 0.00001)],
    ("propagation", "client_b"): [("baseline", "observe_a", 0.00001)],
    ("propagation-live", "client_a"): [("baseline", "apply_live", 0.00001)],
    # The point of the live scenario: the observer must SEE the transition, not merely resolve new
    # identifiers. The programmatic assertion checks ids and cached bytes, which stayed true while
    # the render was stale, so the pixels are what actually proves propagation here.
    ("propagation-live", "client_b"): [
        (
            "observe_before",
            "await_live_change",
            MINIMUM_APPEARANCE_CHANGE,
            PLAYER_REGION,
        )
    ],
    ("full", "client_a"): [
        ("baseline", "local_skin_apply", 0.00001),
        ("model_slim", "model_classic", 0.00001),
        ("animated_cape_apply", "animated_cape_advance", 0.00001),
        ("known_cape_apply", "hd_cape_no_downscale", 0.00001),
        # The cape preview must follow the SELECTION. Both shots are taken with the same cape worn
        # (known:test) and only the preview widget's cape differs, so if the preview fell back to the
        # worn cape these two frames would be identical.
        ("cape_preview_selected_a", "cape_preview_selected_b", 0.00001),
        # Same CapeAdjustScreen instance, same source image, same camera; only the opaque-fill
        # toggle moves between the two frames. Like the pairs above this asserts visual change, not
        # how much: the toggle's own button label also changes, so the threshold alone cannot tell
        # a filled preview from an unfilled one. That part is asserted programmatically instead —
        # the scenario checks hasTransparentPixels and the exact fill pixel on both the composed
        # preview atlas and the applied one.
        ("cape_adjust_opaque_off", "cape_adjust_opaque_on", 0.00001),
        # Same CapeAdjustScreen instance, same source image, same camera; only the zoom slider moves
        # between the two frames (0.10 -> 0.80 of its track). The same honesty caveat applies as
        # above: the slider handle and its "Zoom: n%" label are in frame, so this threshold alone
        # cannot separate a rescaled preview from a repainted control. That part is asserted
        # programmatically instead — the scenario composes the atlas at both zoom levels and
        # requires at least a tenth of its pixels to differ, checks that the widget's value still
        # equals the position imgScale implies, that one wheel notch moves the handle by exactly one
        # mapping step, and that the applied cape matches the previewed one pixel for pixel.
        ("cape_adjust_zoom_out", "cape_adjust_zoom_in", 0.00001),
    ],
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
    if row["loader"] == "neoforge":
        return f"neoforge-{row['loader_version']}"
    raise RuntimeFailure(f"unsupported loader {row['loader']!r}")


def normalize_inherited_profile(version_json: Path, loader: str) -> None:
    """Make inherited loader profiles explicit for minecraft-launcher-lib 8.0.

    Loader installers normally omit ``jar`` and rely on the official launcher
    to select the inherited vanilla jar.  minecraft-launcher-lib instead falls
    back to the loader profile id. Fabric needs an explicit inherited jar.
    Forge and NeoForge intentionally keep the profile-id tail nonexistent: their
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
    elif row["loader"] == "neoforge":
        # Direct installer invocation avoids minecraft-launcher-lib's pre-26.x
        # NeoForge version normalizer while retaining its standard command builder.
        arguments = [java, "-jar", str(installer), "--install-client", str(directory)]
    else:
        raise RuntimeFailure(f"unsupported loader {row['loader']!r}")
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

    if row["loader"] not in {"forge", "neoforge"}:
        raise RuntimeFailure(f"unsupported loader {row['loader']!r}")
    install_flag = "--installServer" if row["loader"] == "forge" else "--install-server"
    run_checked([java, "-jar", str(installer), install_flag, str(server)], server, log, env)
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
            # Must fit inside the virtual display the CI workflows start (see the xvfb-run
            # --server-args in on-demand-e2e.yml and release.yml); a window larger than the
            # screen is silently clamped and the evidence stops matching what was asked for.
            # Pixel comparisons are unaffected by this number: the regions are fractional, so
            # the same transition measured 0.0723 at 2560x1440 locally and 0.0725 at 1280x720
            # in CI. It only governs how legible the captured evidence is.
            "resolutionWidth": "1920",
            "resolutionHeight": "1080",
            "quickPlayMultiplayer": f"127.0.0.1:{port}",
            "jvmArguments": [
                "-Xms512M",
                "-Xmx1024M",
                "-Dquickskin.e2e.enabled=true",
                f"-Dquickskin.e2e.role={role}",
                f"-Dquickskin.e2e.scenario={scenario}",
                f"-Dquickskin.e2e.version={row['runtime_version']}",
                # Exercise injector `expect` counts in packaged clients without making optional
                # integrations fail-closed in ordinary production launches.
                "-Dmixin.debug.countInjections=true",
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


def failed_marker_summary(game_dir: Path, role: str) -> str:
    """Return a bounded report summary suitable for the CI log when a harness marker is ``fail``."""
    report_path = game_dir / "e2e-report" / "report.json"
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return f"{role}: report unavailable ({exc})"
    if not isinstance(report, dict):
        return f"{role}: malformed report root"
    steps = report.get("steps")
    if not isinstance(steps, list):
        return f"{role}: malformed report steps"
    failures: list[str] = []
    for step in steps:
        if not isinstance(step, dict) or step.get("status") == "pass":
            continue
        name = str(step.get("name", "<unnamed>"))
        status = str(step.get("status", "<missing>"))
        message = str(step.get("message", "")).replace("\n", " ")[:500]
        failures.append(f"{name}={status}: {message}")
    return f"{role}: " + ("; ".join(failures) if failures else "marker failed without a failed step")


def inspect_screenshot(
    path: Path, *, expected_format: str = "PNG"
) -> dict[str, Any]:
    """Decode an image and reject corrupt, implausible, or effectively blank evidence.

    Packaged-runtime callers retain the PNG-only default.  Protected Pages code may use the
    same pixel inspection for the WebP derivative that it creates from an already validated PNG.
    """

    if expected_format not in {"PNG", "WEBP"}:
        raise RuntimeFailure(f"unsupported screenshot format contract: {expected_format!r}")

    try:
        from PIL import Image, ImageStat, UnidentifiedImageError
    except ImportError as exc:  # pragma: no cover - CI installs the locked E2E requirements
        raise RuntimeFailure("Pillow is required for screenshot pixel validation") from exc

    try:
        Image.MAX_IMAGE_PIXELS = 20_000_000
        with Image.open(path) as image:
            if image.format != expected_format:
                raise RuntimeFailure(
                    f"screenshot is not a {expected_format} image: {path}"
                )
            width, height = image.size
            if width < 640 or height < 360 or width * height > Image.MAX_IMAGE_PIXELS:
                raise RuntimeFailure(
                    f"screenshot dimensions are implausible: {path} ({width}x{height})"
                )
            image.load()
            rgb = image.convert("RGB")
            sample = rgb.resize((160, 90), Image.Resampling.BILINEAR)
            luma = sample.convert("L")
            entropy = float(luma.entropy())
            channel_stddev = [float(value) for value in ImageStat.Stat(sample).stddev]
            palette_counts = sample.quantize(colors=32).getcolors() or []
            sample_pixels = sample.width * sample.height
            meaningful_colors = sum(
                count >= max(2, sample_pixels // 1000) for count, _ in palette_counts
            )
            luma_histogram = luma.histogram()
            dark_fraction = sum(luma_histogram[:8]) / sample_pixels
            light_fraction = sum(luma_histogram[248:]) / sample_pixels
            if (
                entropy < 0.75
                or max(channel_stddev) < 2.0
                or meaningful_colors < 4
                or dark_fraction > 0.98
                or light_fraction > 0.995
            ):
                raise RuntimeFailure(
                    f"screenshot is effectively blank: {path} "
                    f"(entropy={entropy:.3f}, colors={meaningful_colors}, "
                    f"dark={dark_fraction:.3f}, light={light_fraction:.3f})"
                )
            pixel_sha256 = hashlib.sha256(rgb.tobytes()).hexdigest()
    except RuntimeFailure:
        raise
    except (OSError, UnidentifiedImageError, ValueError) as exc:
        raise RuntimeFailure(f"screenshot cannot be decoded: {path}: {exc}") from exc

    return {
        "width": width,
        "height": height,
        "file_sha256": sha256(path),
        "pixel_sha256": pixel_sha256,
        "luma_entropy": round(entropy, 3),
        "meaningful_colors": meaningful_colors,
        "dark_fraction": round(dark_fraction, 4),
        "light_fraction": round(light_fraction, 4),
    }


def compare_screenshots(
    first: Path,
    second: Path,
    minimum_changed_fraction: float,
    region: tuple[float, float, float, float] | None = None,
) -> dict[str, Any]:
    try:
        from PIL import Image, ImageChops
    except ImportError as exc:  # pragma: no cover - CI installs the locked E2E requirements
        raise RuntimeFailure("Pillow is required for screenshot pixel validation") from exc

    try:
        with Image.open(first) as first_image, Image.open(second) as second_image:
            first_rgb = first_image.convert("RGB")
            second_rgb = second_image.convert("RGB")
            if first_rgb.size != second_rgb.size:
                raise RuntimeFailure(
                    f"screenshots changed dimensions unexpectedly: {first} {first_rgb.size}, "
                    f"{second} {second_rgb.size}"
                )
            if region is not None:
                width, height = first_rgb.size
                left, top, right, bottom = region
                box = (
                    int(left * width),
                    int(top * height),
                    int(right * width),
                    int(bottom * height),
                )
                if box[0] >= box[2] or box[1] >= box[3]:
                    raise RuntimeFailure(
                        f"comparison region {region} is empty at {width}x{height}"
                    )
                first_rgb = first_rgb.crop(box)
                second_rgb = second_rgb.crop(box)
            difference = ImageChops.difference(first_rgb, second_rgb).convert("L")
            histogram = difference.histogram()
            pixels = difference.width * difference.height
            changed_fraction = sum(histogram[8:]) / pixels
            rms_difference = (
                sum(value * value * count for value, count in enumerate(histogram)) / pixels
            ) ** 0.5
    except RuntimeFailure:
        raise
    except (OSError, ValueError) as exc:
        raise RuntimeFailure(f"cannot compare screenshots {first} and {second}: {exc}") from exc

    if changed_fraction < minimum_changed_fraction:
        scope = "in region " + repr(region) if region is not None else "over the frame"
        raise RuntimeFailure(
            f"screenshots expected to change did not change enough {scope}: {first} -> {second} "
            f"(changed={changed_fraction:.7f}, required={minimum_changed_fraction:.7f})"
        )
    comparison: dict[str, Any] = {
        "changed_fraction": round(changed_fraction, 7),
        "rms_difference": round(rms_difference, 3),
        "required_changed_fraction": minimum_changed_fraction,
    }
    if region is not None:
        comparison["region"] = list(region)
    return comparison


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
    screenshot_paths: dict[str, Path] = {}
    screenshot_validation: dict[str, dict[str, Any]] = {}
    for step in steps:
        screenshot = step.get("screenshot")
        if step["name"] in screenshot_steps and not screenshot:
            raise RuntimeFailure(f"{role}/{step['name']} omitted its required screenshot")
        if screenshot:
            screenshots_root = (game_dir / "screenshots").resolve()
            screenshot_path = (screenshots_root / screenshot).resolve()
            if screenshots_root not in screenshot_path.parents:
                raise RuntimeFailure(f"{role}/{step['name']} screenshot escapes its profile")
            screenshot_paths[step["name"]] = screenshot_path
            screenshot_validation[step["name"]] = inspect_screenshot(screenshot_path)
    pair_validation: dict[str, dict[str, Any]] = {}
    for pair in DISTINCT_SCREENSHOT_PAIRS.get((scenario, role), []):
        first_step, second_step, minimum_change = pair[0], pair[1], pair[2]
        region = pair[3] if len(pair) > 3 else None
        if first_step not in screenshot_paths or second_step not in screenshot_paths:
            raise RuntimeFailure(
                f"pixel comparison contract is missing {role}/{first_step} or {role}/{second_step}"
            )
        pair_validation[f"{first_step}->{second_step}"] = compare_screenshots(
            screenshot_paths[first_step],
            screenshot_paths[second_step],
            minimum_change,
            region,
        )
    report["pixel_validation"] = {
        "screenshots": screenshot_validation,
        "comparisons": pair_validation,
    }
    return report


def is_benign_kqueue_debug_appender_line(lines: list[str], line_index: int) -> bool:
    """Recognize NeoForge's harmless Linux kqueue-probe logging recursion.

    Minecraft probes Netty's macOS/BSD kqueue transport before falling back to a supported
    transport.  NeoForge's extended DebugFile throwable renderer can try to initialize the failed
    native class again while formatting that DEBUG message, which breaks only the appender and
    prints a NoClassDefFoundError to the redirected console.  The game keeps running.  Ignore only
    the exact NoClassDefFoundError inside that appender stack and only when the original unsupported
    platform cause is present; every other linkage error remains fatal.
    """

    if KQUEUE_NATIVE_INIT_FAILURE not in lines[line_index]:
        return False

    first_candidate = max(0, line_index - DEBUG_FILE_APPENDER_STACK_WINDOW)
    for header_index in range(line_index, first_candidate - 1, -1):
        if DEBUG_FILE_APPENDER_FAILURE not in lines[header_index]:
            continue
        block = lines[
            header_index : min(len(lines), header_index + DEBUG_FILE_APPENDER_STACK_WINDOW)
        ]
        return any(KQUEUE_UNSUPPORTED_PLATFORM_CAUSE in candidate for candidate in block)
    return False


def scan_runtime_logs(logs: list[Path]) -> None:
    hits: list[str] = []
    for log in logs:
        if not log.is_file():
            raise RuntimeFailure(f"runtime log missing: {log}")
        content = log.read_text(encoding="utf-8", errors="replace")
        if "client" in log.stem.lower() and "[QS-E2E] FINISHED status=pass" not in content:
            hits.append(f"{log}: missing [QS-E2E] FINISHED status=pass")
        lines = content.splitlines()
        for line_index, line in enumerate(lines):
            if any(pattern.search(line) for pattern in FATAL_LOG_PATTERNS):
                if is_benign_kqueue_debug_appender_line(lines, line_index):
                    continue
                hits.append(f"{log}:{line_index + 1}: {line[:300]}")
    if hits:
        raise RuntimeFailure("fatal runtime log evidence:\n" + "\n".join(hits[:30]))


def require_compatibility_marker(logs: list[Path], row: dict[str, Any]) -> None:
    patch = row.get("compatibility_patch")
    if patch is None:
        return
    marker = COMPATIBILITY_LOG_MARKERS.get(patch)
    if marker is None:
        raise RuntimeFailure(f"unknown runtime compatibility patch {patch!r}")
    missing: list[str] = []
    for log in logs:
        content = log.read_text(encoding="utf-8", errors="replace")
        if marker not in content:
            missing.append(str(log))
    if missing:
        raise RuntimeFailure(
            f"compatibility patch {patch!r} was not observed in every process: {missing}"
        )


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
                shutil.copy2(
                    repo / "e2e" / "fml.toml.neoforge",
                    game_dir / "config" / "fml.toml",
                )
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
            summaries: list[str] = []
            if marker_a != "pass":
                summaries.append(failed_marker_summary(client_a, "client_a"))
            if two_clients and marker_b != "pass":
                summaries.append(failed_marker_summary(client_b, "client_b"))
            details = "; ".join(summaries)
            raise RuntimeFailure(
                f"harness marker failure: A={marker_a}, B={marker_b}"
                + (f"; {details}" if details else "")
            )
        reports = {"client_a": validate_report(client_a, row, scenario, "client_a")}
        if two_clients:
            reports["client_b"] = validate_report(client_b, row, scenario, "client_b")
        scan_runtime_logs(runtime_logs)
        require_compatibility_marker(runtime_logs, row)
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
