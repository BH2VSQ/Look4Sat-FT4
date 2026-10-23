"""Check the production CW token pattern using native ICU, not OpenJDK regex."""
import ctypes
import ctypes.util
import json
from pathlib import Path
import re
import sys


def main():
    library = "icu.dll" if sys.platform == "win32" else ctypes.util.find_library("icui18n")
    if not library:
        raise RuntimeError("Native ICU is required; this check must not fall back to OpenJDK")
    icu = ctypes.CDLL(library)
    suffix = next((s for s in [""] + [f"_{n}" for n in range(50, 100)]
                   if hasattr(icu, "uregex_open" + s)), None)
    if suffix is None:
        raise RuntimeError("Native ICU regex entry point is unavailable")
    open_regex = getattr(icu, "uregex_open" + suffix)
    close = getattr(icu, "uregex_close" + suffix)
    error_name = getattr(icu, "u_errorName" + suffix)
    open_regex.argtypes = [ctypes.c_void_p, ctypes.c_int32, ctypes.c_uint32,
                           ctypes.c_void_p, ctypes.POINTER(ctypes.c_int32)]
    open_regex.restype = ctypes.c_void_p
    close.argtypes = [ctypes.c_void_p]
    error_name.argtypes = [ctypes.c_int32]
    error_name.restype = ctypes.c_char_p

    def compile_pattern(pattern):
        status = ctypes.c_int32()
        encoding = "utf-16-le" if sys.byteorder == "little" else "utf-16-be"
        text = ctypes.create_string_buffer(pattern.encode(encoding))
        handle = open_regex(text, len(pattern), 0, None, ctypes.byref(status))
        if handle:
            close(handle)
        return status.value, error_name(status.value).decode("ascii")

    source = (Path(__file__).resolve().parents[1] / "core/domain/src/main/java/com/rtbishop/"
              "look4sat/core/domain/repository/CwMessageTemplate.kt").read_text(encoding="utf-8")
    match = re.search(r'private val token = Regex\((".*")\)', source)
    if match is None:
        raise RuntimeError("Production token expression not found")
    pattern = json.loads(match.group(1))
    broken_code, broken_name = compile_pattern(r"\{([^{}]*)}")
    if broken_code <= 0:
        raise AssertionError("ICU must reproduce the old unescaped-brace failure")
    code, name = compile_pattern(pattern)
    if code > 0:
        raise AssertionError(f"Production token fails native ICU: {name}")
    print(f"Old pattern: {broken_name}; production pattern: {name} - PASS")


if __name__ == "__main__":
    main()
