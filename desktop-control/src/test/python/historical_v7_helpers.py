"""Pinned Git helpers for offline v7 research; never an active game client.

The fixed commit is the final protocol-7 implementation used by the historical
corpus. A shallow checkout must fetch that commit before running these tools.
The current production decoder and its protocol checks are never replaced.
"""
from contextlib import contextmanager
import importlib.util
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[4]
OLD_COMMIT = "38c21149d84f166db6630a7c7fa220e5851cfd41"
HISTORICAL = {
    "historical_v7_decoder.py": "desktop-control/client/spdctl_client.py",
    "historical_protocol7.py": "desktop-control/src/test/python/protocol7.py",
}


def validate_research_output(output, *, input_files=(), input_directories=()):
    """Research entrypoints require a new or empty directory, apart from inputs."""
    output = Path(output)
    if output.is_symlink():
        raise ValueError("Research output must not be a symbolic link")
    output = output.resolve()
    for directory in input_directories:
        source = Path(directory).resolve()
        if output.is_relative_to(source) or source.is_relative_to(output):
            raise ValueError("Research output and source directories must be disjoint")
    if any(Path(path).resolve().is_relative_to(output) for path in input_files):
        raise ValueError("Research inputs must be outside the output directory")
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError("Research output must be a new or empty directory; choose a fresh output")
    return output


@contextmanager
def decoder_import_binding(decoder):
    """Bind a frozen projection's import only while loading that module."""
    absent = object()
    previous = sys.modules.get("spdctl_client", absent)
    old_path = sys.path[:]
    sys.modules["spdctl_client"] = decoder
    try:
        yield
    finally:
        sys.path[:] = old_path
        if previous is absent:
            sys.modules.pop("spdctl_client", None)
        else:
            sys.modules["spdctl_client"] = previous


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    previous = sys.modules.get(name)
    sys.modules[name] = module
    try:
        spec.loader.exec_module(module)
    except BaseException:
        if previous is None:
            sys.modules.pop(name, None)
        else:
            sys.modules[name] = previous
        raise
    return module


def freeze_helper(output, filename):
    source = HISTORICAL[filename]
    result = subprocess.run(["git", "show", f"{OLD_COMMIT}:{source}"],
                            cwd=ROOT, capture_output=True)
    if result.returncode:
        raise RuntimeError(
            f"Historical protocol-7 helper requires Git commit {OLD_COMMIT}; "
            "fetch the repository history before running this offline study")
    path = Path(output) / filename
    if path.exists():
        if path.read_bytes() != result.stdout:
            raise ValueError(f"Frozen historical helper drifted: {path}")
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(result.stdout)
    return path


def load_decoder(output):
    path = freeze_helper(output, "historical_v7_decoder.py")
    decoder = load_module("spdctl_research_v7_decoder", path)
    if decoder.PROTOCOL_VERSION != 7:
        raise ValueError("The pinned historical decoder must accept protocol 7")
    return decoder


def load_projection(output, decoder):
    path = freeze_helper(output, "historical_protocol7.py")
    with decoder_import_binding(decoder):
        return load_module("spdctl_research_v7_projection", path)
