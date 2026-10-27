"""ctypes adapter for the production retina.dat loader and world handles.

The Rust loader supplies every generation request. This module transports
execution options and metadata; it does not decode NBT or compile profiles.
Keep the context open until every caller has finished using its requests.
"""
from __future__ import annotations

import ctypes as c
import json
import os
import operator
from pathlib import Path


class Request(c.Structure):
    _fields_ = [("seed", c.c_uint64), ("x", c.c_int32), ("z", c.c_int32),
                ("min_y", c.c_int32), ("height", c.c_uint32), ("base", c.c_float),
                ("amplitude", c.c_float), ("frequency", c.c_float), ("profile", c.c_uint32)]


def check(library, status):
    if status:
        error = c.create_string_buffer(8192)
        library.retina_last_error.argtypes = [c.c_void_p, c.c_uint64]
        library.retina_last_error(error, len(error))
        raise RuntimeError(error.value.decode("utf-8", errors="replace"))


class SavedDefinition:
    def __init__(self, library, path: Path, dimension: str, compression="zlib", execution=None):
        self.library, self.path, self.key = library, Path(path), dimension.encode("utf-8")
        self.handle = c.c_uint64()
        library.retina_open_world_definition.argtypes = [c.c_char_p, c.c_uint64, c.POINTER(c.c_uint64)]
        library.retina_close_world_definition.argtypes = [c.c_uint64]
        library.retina_world_definition_metadata.argtypes = [c.c_uint64, c.POINTER(c.c_void_p), c.POINTER(c.c_uint64)]
        library.retina_free_bytes.argtypes = [c.c_void_p, c.c_uint64]
        library.retina_free_bytes.restype = None
        library.retina_world_dimension_request.argtypes = [c.c_uint64, c.c_char_p, c.c_uint64, c.c_int32, c.c_int32, c.POINTER(Request)]
        encoded = os.fsencode(self.path.resolve())
        check(library, library.retina_open_world_definition(encoded, len(encoded), c.byref(self.handle)))
        if not self.handle.value:
            raise FileNotFoundError(self.path)
        try:
            pointer, length = c.c_void_p(), c.c_uint64()
            check(library, library.retina_world_definition_metadata(self.handle, c.byref(pointer), c.byref(length)))
            try:
                self.metadata = json.loads(c.string_at(pointer, length.value))
            finally:
                library.retina_free_bytes(pointer, length)
            codec = {"zlib": 2, "lz4": 4}[compression]
            profile = c.c_uint32()
            if execution is None:
                library.retina_bind_world_dimension.argtypes = [c.c_uint64, c.c_char_p, c.c_uint64, c.c_uint32, c.POINTER(c.c_uint32)]
                status = library.retina_bind_world_dimension(self.handle, self.key, len(self.key), codec, c.byref(profile))
            else:
                mode = {"auto": 0, "interpreter": 1, "specialized": 2}[execution]
                library.retina_bind_world_dimension_execution.argtypes = [c.c_uint64, c.c_char_p, c.c_uint64, c.c_uint32, c.c_uint32, c.POINTER(c.c_uint32)]
                status = library.retina_bind_world_dimension_execution(self.handle, self.key, len(self.key), codec, mode, c.byref(profile))
            check(library, status)
            self.profile = profile.value
            self.dimension = self.metadata["dimensions"][dimension]
            self.fallback_biome = self.dimension["profile_metadata"]["biomes"][0]["id"]
        except BaseException as error:
            try:
                self.close()
            except Exception as cleanup:
                if hasattr(error, "add_note"):
                    error.add_note(f"Closing the world definition also failed: {cleanup}")
            raise

    def request(self, x, z):
        x, z = operator.index(x), operator.index(z)
        if not (-(1 << 31) <= x < (1 << 31) and -(1 << 31) <= z < (1 << 31)):
            raise OverflowError("chunk coordinates cannot be represented by the signed 32-bit native ABI")
        result = Request()
        check(self.library, self.library.retina_world_dimension_request(
            self.handle, self.key, len(self.key), x, z, c.byref(result)))
        return result

    def close(self):
        if self.handle.value:
            handle, self.handle = self.handle, c.c_uint64()
            check(self.library, self.library.retina_close_world_definition(handle))

    def __enter__(self):
        return self

    def __exit__(self, kind, error, traceback):
        try:
            self.close()
        except Exception as cleanup:
            if error is None:
                raise
            if hasattr(error, "add_note"):
                error.add_note(f"Closing the world definition also failed: {cleanup}")
