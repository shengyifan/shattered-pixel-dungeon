"""Self-contained, lossless map-format experiments for protocol-6 responses.

These codecs are offline proposals.  Every packet carries every definition needed
to reconstruct its input response; there is no prior frame, baseline, cache, or
lookup.  Only live protocol-shaped ``map`` values are transformed.  Historical
and diagnostic payloads under opaque fields remain byte-value-equivalent objects.
"""

import copy
from collections import Counter


CODEC = "standalone-map-v1"
MAP_VERSION = 1
TILE_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
OPAQUE_FIELDS = {
    "raw", "reply", "before", "after", "history", "events", "schema",
    "late_responses", "original_response", "outcome",
    "source", "sources", "text_sources", "text_origins", "text_diagnostics",
    "pres", "preserved_cells", "original_payload", "raw_request", "raw_bytes",
    "request_json", "response_json",
}

# Stable visual choices for common public terrain IDs.  These symbols describe
# known row cells only.  An omitted row interval remains unknown, never a space.
PREFERRED_SYMBOLS = {
    0: "^",   # chasm
    1: ".",   # floor
    2: ",",   # grass
    3: "o",   # empty well
    4: "#",   # wall
    5: "+",   # closed door
    6: "/",   # open door
    7: "<",   # entrance
    8: ">",   # exit
    9: ":",   # embers
    10: "L",  # locked door
    11: "P",  # pedestal
    12: "%",  # decorated wall
    13: "B",  # barricade
    14: "_",  # special floor
    15: ";",  # high grass
    16: "?",  # secret door appearance
    17: "!",  # secret trap
    18: "T",  # visible trap
    19: "x",  # inactive trap
    20: "'",  # decorated floor
    21: "X",  # locked exit
    22: "v",  # unlocked exit
    24: "O",  # well
    27: "=",  # bookshelf
    28: "A",  # alchemy
    29: "~",  # water
    30: "g",  # furrowed grass
    31: "C",  # crystal door
    32: "d",  # custom empty decoration
    33: "c",  # region decoration
    34: "h",  # alternate region decoration
    35: "*",  # mine crystal
    36: "b",  # mine boulder
    37: "e",  # special entrance
    38: "D",  # hero-locked door
}

# Printable one-column symbols that need no JSON escape and cannot collide with
# the row delimiters.  There are more than the protocol's ordinary 64 type slots.
SAFE_SYMBOLS = "".join(
    chr(value) for value in range(33, 127)
    if chr(value) not in {'"', "\\", "|"}
)


class CodecError(ValueError):
    """Malformed input map or candidate packet."""


def _copy(value):
    return copy.deepcopy(value)


def _integer(value, path):
    if type(value) is not int:
        raise CodecError(f"{path} must be an integer")
    return value


def _base36(value):
    if type(value) is not int or value < 0:
        raise CodecError("base36 values must be non-negative integers")
    digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    if value == 0:
        return "0"
    result = ""
    while value:
        value, remainder = divmod(value, 36)
        result = digits[remainder] + result
    return result


def _from_base36(value, path):
    if (not isinstance(value, str) or not value
            or len(value) > 1 and value.startswith("0")
            or any(char not in "0123456789abcdefghijklmnopqrstuvwxyz" for char in value)):
        raise CodecError(f"{path} is not canonical lowercase base36")
    return int(value, 36)


def _protocol_map(value):
    return isinstance(value, dict) and any(key in value for key in ("w", "h", "types", "rows"))


def _rows(value, path="map"):
    if not isinstance(value, dict):
        raise CodecError(f"{path} must be an object")
    width = _integer(value.get("w"), f"{path}.w")
    height = _integer(value.get("h"), f"{path}.h")
    if width <= 0 or height <= 0:
        raise CodecError(f"{path} dimensions must be positive")
    types = value.get("types")
    rows = value.get("rows")
    if not isinstance(types, list) or not all(isinstance(item, dict) for item in types):
        raise CodecError(f"{path}.types must be an array of descriptors")
    if not isinstance(rows, list):
        raise CodecError(f"{path}.rows must be an array")
    integer_tiles = len(types) > len(TILE_ALPHABET)
    decoded = []
    previous = -1
    for number, row in enumerate(rows):
        row_path = f"{path}.rows[{number}]"
        if not isinstance(row, list) or len(row) != 4:
            raise CodecError(f"{row_path} must be [y,x_start,tiles,visibility]")
        y = _integer(row[0], f"{row_path}[0]")
        x = _integer(row[1], f"{row_path}[1]")
        if not 0 <= y < height or not 0 <= x < width:
            raise CodecError(f"{row_path} begins outside the map")
        encoded = row[2]
        if integer_tiles:
            if not isinstance(encoded, list):
                raise CodecError(f"{row_path} must use integer tiles")
            indices = [_integer(item, f"{row_path}[2]") for item in encoded]
        else:
            if not isinstance(encoded, str):
                raise CodecError(f"{row_path} must use alphabet tiles")
            try:
                indices = [TILE_ALPHABET.index(char) for char in encoded]
            except ValueError as error:
                raise CodecError(f"{row_path} has an invalid tile symbol") from error
        if not indices:
            raise CodecError(f"{row_path} must not be empty")
        if x + len(indices) > width:
            raise CodecError(f"{row_path} crosses the map width")
        if any(index < 0 or index >= len(types) for index in indices):
            raise CodecError(f"{row_path} references a missing terrain type")
        visibility = row[3]
        if not isinstance(visibility, str) or len(visibility) not in (1, len(indices)):
            raise CodecError(f"{row_path} visibility length is invalid")
        if any(char not in "vsm" for char in visibility):
            raise CodecError(f"{row_path} visibility has an invalid value")
        first = y * width + x
        if first <= previous:
            raise CodecError(f"{row_path} overlaps or is not in cell order")
        previous = first + len(indices) - 1
        decoded.append((y, x, indices, visibility))
    return width, height, types, decoded


def _symbols(types):
    if len(types) <= len(SAFE_SYMBOLS):
        used = set()
        symbols = []
        for descriptor in types:
            terrain = descriptor.get("terrain")
            preferred = PREFERRED_SYMBOLS.get(terrain) if type(terrain) is int else None
            if preferred is not None and preferred not in used:
                symbol = preferred
            else:
                try:
                    # A symbol preferred by some other terrain remains available
                    # when that terrain is absent.  Reserving every preferred
                    # symbol globally can exhaust a nominally large enough table
                    # when many distinct descriptors share one terrain ID.
                    symbol = next(char for char in SAFE_SYMBOLS if char not in used)
                except StopIteration as error:
                    raise CodecError("not enough unique ASCII symbols") from error
            used.add(symbol)
            symbols.append(symbol)
        return "chars", "".join(symbols)

    # Extremely large legal dictionaries use explicit printable tokens instead of
    # pretending a multi-character token is one map cell.
    used = set()
    symbols = []
    for index, descriptor in enumerate(types):
        terrain = descriptor.get("terrain")
        preferred = PREFERRED_SYMBOLS.get(terrain) if type(terrain) is int else None
        token = preferred if preferred is not None and preferred not in used else "@" + _base36(index)
        while token in used:
            token = "@" + token
        used.add(token)
        symbols.append(token)
    return "tokens", symbols


def _runs(values):
    result = []
    for value in values:
        if result and result[-1][0] == value:
            result[-1][1] += 1
        else:
            result.append([value, 1])
    return result


def _encode_ascii(value):
    width, height, types, rows = _rows(value)
    mode, symbols = _symbols(types)
    by_index = list(symbols) if mode == "chars" else symbols
    lines = []
    for y, x, indices, visibility in rows:
        if mode == "chars":
            tiles = "".join(by_index[index] for index in indices)
        else:
            tiles = " ".join(by_index[index] for index in indices)
        lines.append(f"{y},{x}|{tiles}|{visibility}")
    extra = {key: _copy(child) for key, child in value.items()
             if key not in {"w", "h", "types", "rows"}}
    return {"v": MAP_VERSION, "style": "ascii", "w": width, "h": height,
            "types": _copy(types), "mode": mode, "symbols": _copy(symbols),
            "grid": "\n".join(lines), "extra": extra}


def _encode_rle(value):
    width, height, types, rows = _rows(value)
    lines = []
    for y, x, indices, visibility in rows:
        tile_runs = ".".join(f"{_base36(index)}*{_base36(count)}" for index, count in _runs(indices))
        if len(visibility) == 1:
            vis = "u" + visibility
        else:
            vis = "e" + ".".join(f"{char}*{_base36(count)}" for char, count in _runs(visibility))
        lines.append(f"{y},{x}|{tile_runs}|{vis}")
    extra = {key: _copy(child) for key, child in value.items() if key not in {"w", "h", "types", "rows"}}
    return {"v": MAP_VERSION, "style": "rle", "w": width, "h": height,
            "types": _copy(types), "grid": "\n".join(lines), "extra": extra}


def _maximal_spans(known):
    """Return sorted absolute (y, x, length) spans from known cell coordinates."""
    spans = []
    by_y = {}
    for y, x in known:
        by_y.setdefault(y, []).append(x)
    for y in sorted(by_y):
        xs = sorted(by_y[y])
        start = previous = xs[0]
        for x in xs[1:]:
            if x == previous + 1:
                previous = x
            else:
                spans.append([y, start, previous - start + 1])
                start = previous = x
        spans.append([y, start, previous - start + 1])
    return spans


def _encode_grid(value):
    width, height, types, rows = _rows(value)
    if len(types) > len(TILE_ALPHABET):
        # A space-separated integer grid is neither compact nor especially
        # readable.  Carry the legal original map explicitly instead.
        return {"v": MAP_VERSION, "style": "grid", "raw": _copy(value)}

    extra = {key: _copy(child) for key, child in value.items()
             if key not in {"w", "h", "types", "rows"}}
    if not rows:
        return {"v": MAP_VERSION, "style": "grid", "w": width, "h": height,
                "types": _copy(types), "bbox": [], "grid": "",
                "vis_default": None, "extra": extra}

    known = {}
    visibility = {}
    original_spans = []
    expanded_uniform = []
    for row_index, (y, x, indices, vis) in enumerate(rows):
        original_spans.append([y, x, len(indices)])
        effective = vis * len(indices) if len(vis) == 1 else vis
        if len(vis) > 1 and len(set(vis)) == 1:
            expanded_uniform.append(row_index)
        for offset, (index, flag) in enumerate(zip(indices, effective)):
            key = (y, x + offset)
            known[key] = index
            visibility[key] = flag

    xs = [x for _, x in known]
    ys = [y for y, _ in known]
    xmin, xmax, ymin, ymax = min(xs), max(xs), min(ys), max(ys)
    box_width, box_height = xmax - xmin + 1, ymax - ymin + 1
    lines = []
    for y in range(ymin, ymax + 1):
        line = [" "] * box_width
        for x in range(xmin, xmax + 1):
            if (y, x) in known:
                line[x - xmin] = TILE_ALPHABET[known[(y, x)]]
        lines.append("".join(line))

    counts = Counter(visibility.values())
    default = min("vsm", key=lambda flag: (-counts[flag], "vsm".index(flag)))
    intervals = {}
    for flag in "vsm":
        if flag == default:
            continue
        cells = {key for key, value in visibility.items() if value == flag}
        if cells:
            intervals[flag] = _maximal_spans(cells)

    result = {"v": MAP_VERSION, "style": "grid", "w": width, "h": height,
              "types": _copy(types), "bbox": [xmin, ymin, box_width, box_height],
              "grid": "\n".join(lines), "vis_default": default, "extra": extra}
    if intervals:
        result["vis"] = intervals
    maximal = _maximal_spans(known)
    if original_spans != maximal:
        result["spans"] = original_spans
    if expanded_uniform:
        result["expanded_rows"] = expanded_uniform
    return result


def _parse_line(line, path):
    parts = line.split("|")
    if len(parts) != 3:
        raise CodecError(f"{path} must contain two | delimiters")
    coordinates = parts[0].split(",")
    if len(coordinates) != 2 or not all(value.isdigit() for value in coordinates):
        raise CodecError(f"{path} has invalid decimal coordinates")
    return int(coordinates[0]), int(coordinates[1]), parts[1], parts[2]


def _original_map(body, rows):
    extra = body.get("extra")
    if not isinstance(extra, dict) or set(extra) & {"w", "h", "types", "rows"}:
        raise CodecError("encoded map extra fields conflict with map structure")
    result = {"w": body.get("w"), "h": body.get("h"),
              "types": _copy(body.get("types")), "rows": rows, **_copy(extra)}
    _rows(result, "decoded map")
    return result


def _decode_ascii(body):
    expected = {"v", "style", "w", "h", "types", "mode", "symbols", "grid", "extra"}
    if set(body) != expected:
        raise CodecError("ASCII map fields are invalid")
    types = body.get("types")
    if not isinstance(types, list):
        raise CodecError("ASCII map types must be an array")
    mode = body.get("mode")
    symbols = body.get("symbols")
    if mode == "chars":
        if (not isinstance(symbols, str) or len(symbols) != len(types)
                or len(set(symbols)) != len(symbols)):
            raise CodecError("ASCII character table is invalid")
        lookup = {symbol: index for index, symbol in enumerate(symbols)}
        split_tiles = list
    elif mode == "tokens":
        if (not isinstance(symbols, list) or len(symbols) != len(types)
                or not all(isinstance(item, str) and item for item in symbols)
                or len(set(symbols)) != len(symbols)):
            raise CodecError("ASCII token table is invalid")
        lookup = {symbol: index for index, symbol in enumerate(symbols)}
        split_tiles = lambda value: value.split(" ") if value else []
    else:
        raise CodecError("ASCII map mode is invalid")
    grid = body.get("grid")
    if not isinstance(grid, str):
        raise CodecError("ASCII grid must be a string")
    rows = []
    for number, line in enumerate(grid.splitlines() if grid else []):
        y, x, tiles, visibility = _parse_line(line, f"ASCII grid line {number}")
        tokens = split_tiles(tiles)
        if not tokens or any(token not in lookup for token in tokens):
            raise CodecError(f"ASCII grid line {number} has an unknown symbol")
        indices = [lookup[token] for token in tokens]
        encoded = ("".join(TILE_ALPHABET[index] for index in indices)
                   if len(types) <= len(TILE_ALPHABET) else indices)
        rows.append([y, x, encoded, visibility])
    return _original_map(body, rows)


def _decode_rle(body):
    expected = {"v", "style", "w", "h", "types", "grid", "extra"}
    if set(body) != expected:
        raise CodecError("RLE map fields are invalid")
    types = body.get("types")
    if not isinstance(types, list):
        raise CodecError("RLE map types must be an array")
    grid = body.get("grid")
    if not isinstance(grid, str):
        raise CodecError("RLE grid must be a string")
    width = _integer(body.get("w"), "RLE map width")
    if width <= 0:
        raise CodecError("RLE map width must be positive")
    rows = []
    for number, line in enumerate(grid.splitlines() if grid else []):
        y, x, tile_text, vis_text = _parse_line(line, f"RLE grid line {number}")
        indices = []
        for run_number, run in enumerate(tile_text.split(".")):
            fields = run.split("*")
            if len(fields) != 2:
                raise CodecError(f"RLE tile run {run_number} is invalid")
            index = _from_base36(fields[0], "RLE tile index")
            count = _from_base36(fields[1], "RLE tile count")
            if count <= 0 or index >= len(types):
                raise CodecError("RLE tile run is out of range")
            if len(indices) + count > width:
                raise CodecError("RLE tile runs exceed the map width")
            indices.extend([index] * count)
        if not indices:
            raise CodecError(f"RLE grid line {number} is empty")
        if len(vis_text) == 2 and vis_text[0] == "u" and vis_text[1] in "vsm":
            visibility = vis_text[1]
        elif vis_text.startswith("e"):
            visible = []
            for run_number, run in enumerate(vis_text[1:].split(".")):
                fields = run.split("*")
                if len(fields) != 2 or fields[0] not in {"v", "s", "m"}:
                    raise CodecError(f"RLE visibility run {run_number} is invalid")
                count = _from_base36(fields[1], "RLE visibility count")
                if count <= 0:
                    raise CodecError("RLE visibility count must be positive")
                if len(visible) + count > len(indices):
                    raise CodecError("RLE visibility runs exceed the tile row")
                visible.extend([fields[0]] * count)
            visibility = "".join(visible)
            if len(visibility) != len(indices):
                raise CodecError("RLE visibility does not cover the row")
        else:
            raise CodecError(f"RLE grid line {number} visibility is invalid")
        encoded = ("".join(TILE_ALPHABET[index] for index in indices)
                   if len(types) <= len(TILE_ALPHABET) else indices)
        rows.append([y, x, encoded, visibility])
    return _original_map(body, rows)


def _decode_grid(body):
    if "raw" in body:
        if set(body) != {"v", "style", "raw"}:
            raise CodecError("raw grid fallback fields are invalid")
        raw = _copy(body["raw"])
        _, _, types, _ = _rows(raw, "raw grid fallback")
        if len(types) <= len(TILE_ALPHABET):
            raise CodecError("raw grid fallback is only valid above 64 types")
        return raw

    required = {"v", "style", "w", "h", "types", "bbox", "grid",
                "vis_default", "extra"}
    optional = {"vis", "spans", "expanded_rows"}
    if not required <= set(body) or set(body) - required - optional:
        raise CodecError("grid map fields are invalid")
    width = _integer(body.get("w"), "grid map width")
    height = _integer(body.get("h"), "grid map height")
    types = body.get("types")
    if width <= 0 or height <= 0 or not isinstance(types, list):
        raise CodecError("grid map dimensions or types are invalid")
    if len(types) > len(TILE_ALPHABET):
        raise CodecError("grid maps above 64 types require raw fallback")
    bbox = body.get("bbox")
    grid = body.get("grid")
    if not isinstance(bbox, list) or not isinstance(grid, str):
        raise CodecError("grid bbox or text is invalid")

    if not bbox:
        if (grid or body.get("vis_default") is not None or body.get("vis")
                or body.get("spans") or body.get("expanded_rows")):
            raise CodecError("empty grid map contains cell metadata")
        return _original_map(body, [])
    if len(bbox) != 4:
        raise CodecError("grid bbox must be [x,y,width,height]")
    xmin, ymin, box_width, box_height = (
        _integer(value, f"grid bbox[{index}]") for index, value in enumerate(bbox)
    )
    if (box_width <= 0 or box_height <= 0 or xmin < 0 or ymin < 0
            or xmin + box_width > width or ymin + box_height > height):
        raise CodecError("grid bbox is outside the map")
    lines = grid.split("\n")
    if len(lines) != box_height or any(len(line) != box_width for line in lines):
        raise CodecError("grid text does not match its bbox")

    known = {}
    for row_offset, line in enumerate(lines):
        for column, char in enumerate(line):
            if char == " ":
                continue
            try:
                index = TILE_ALPHABET.index(char)
            except ValueError as error:
                raise CodecError("grid contains an invalid tile character") from error
            if index >= len(types):
                raise CodecError("grid references a missing terrain type")
            known[(ymin + row_offset, xmin + column)] = index
    if not known:
        raise CodecError("non-empty bbox contains no known cells")

    default = body.get("vis_default")
    if default not in {"v", "s", "m"}:
        raise CodecError("grid visibility default is invalid")
    visibility = {key: default for key in known}
    assigned = set()
    intervals = body.get("vis", {})
    if not isinstance(intervals, dict) or set(intervals) - {"v", "s", "m"}:
        raise CodecError("grid visibility intervals are invalid")
    if default in intervals:
        raise CodecError("grid visibility intervals repeat the default")
    for flag, spans in intervals.items():
        if not isinstance(spans, list):
            raise CodecError("grid visibility intervals must be arrays")
        for span in spans:
            if not isinstance(span, list) or len(span) != 3:
                raise CodecError("grid visibility span must be [y,x,length]")
            y, x, length = (_integer(value, "grid visibility span") for value in span)
            if length <= 0:
                raise CodecError("grid visibility span length must be positive")
            for offset in range(length):
                key = (y, x + offset)
                if key not in known or key in assigned:
                    raise CodecError("grid visibility span covers unknown or duplicate cells")
                assigned.add(key)
                visibility[key] = flag

    maximal = _maximal_spans(known)
    spans = body.get("spans", maximal)
    if not isinstance(spans, list):
        raise CodecError("grid spans must be an array")
    covered = set()
    normalized_spans = []
    previous = -1
    for span in spans:
        if not isinstance(span, list) or len(span) != 3:
            raise CodecError("grid span must be [y,x,length]")
        y, x, length = (_integer(value, "grid span") for value in span)
        if length <= 0 or not 0 <= y < height or not 0 <= x < width or x + length > width:
            raise CodecError("grid span is outside the map")
        first = y * width + x
        if first <= previous:
            raise CodecError("grid spans overlap or are out of order")
        previous = first + length - 1
        cells = {(y, x + offset) for offset in range(length)}
        if not cells <= set(known) or covered & cells:
            raise CodecError("grid spans cover unknown or duplicate cells")
        covered.update(cells)
        normalized_spans.append([y, x, length])
    if covered != set(known):
        raise CodecError("grid spans do not cover every known cell")

    expanded_rows = body.get("expanded_rows", [])
    if (not isinstance(expanded_rows, list)
            or any(type(index) is not int for index in expanded_rows)
            or len(set(expanded_rows)) != len(expanded_rows)):
        raise CodecError("expanded grid row indexes are invalid")
    expanded_rows = set(expanded_rows)
    rows = []
    for row_index, (y, x, length) in enumerate(normalized_spans):
        indices = [known[(y, x + offset)] for offset in range(length)]
        flags = "".join(visibility[(y, x + offset)] for offset in range(length))
        if row_index in expanded_rows:
            if length <= 1 or len(set(flags)) != 1:
                raise CodecError("expanded grid row marker is not a uniform expanded row")
            visible = flags
        else:
            visible = flags[0] if len(set(flags)) == 1 else flags
        rows.append([y, x, "".join(TILE_ALPHABET[index] for index in indices), visible])
    if expanded_rows - set(range(len(rows))):
        raise CodecError("expanded grid row index is out of range")
    return _original_map(body, rows)


def _encode_value(value, style, maps, path=()):
    if isinstance(value, list):
        return [_encode_value(child, style, maps, (*path, index)) for index, child in enumerate(value)]
    if not isinstance(value, dict):
        return _copy(value)
    result = {}
    for key, child in value.items():
        child_path = (*path, key)
        if key in OPAQUE_FIELDS:
            result[key] = _copy(child)
        elif key == "map" and _protocol_map(child):
            if style == "ascii":
                body = _encode_ascii(child)
            elif style == "rle":
                body = _encode_rle(child)
            else:
                body = _encode_grid(child)
            number = len(maps)
            maps.append({"path": list(child_path), "body": body})
            result[key] = {"$standalone_map": number}
        else:
            result[key] = _encode_value(child, style, maps, child_path)
    return result


def _target(root, path):
    value = root
    for part in path[:-1]:
        if isinstance(part, int):
            if not isinstance(value, list) or part < 0 or part >= len(value):
                raise CodecError("encoded map path has an invalid array index")
        elif not isinstance(part, str) or not isinstance(value, dict) or part not in value:
            raise CodecError("encoded map path has an invalid object key")
        value = value[part]
    return value, path[-1]


def encode(frame, style="ascii"):
    """Encode every live map in one complete response without external state."""
    if style not in {"ascii", "rle", "grid"}:
        raise CodecError("style must be 'ascii', 'rle', or 'grid'")
    if not isinstance(frame, dict):
        raise CodecError("frame must be a complete JSON response object")
    maps = []
    packet = {"codec": CODEC, "style": style,
              "frame": _encode_value(frame, style, maps), "maps": maps}
    if decode(packet) != frame:
        raise CodecError("internal round-trip failure")
    return packet


def decode(packet):
    """Restore the exact JSON value represented by one standalone packet."""
    if not isinstance(packet, dict) or packet.get("codec") != CODEC:
        raise CodecError("not a standalone map packet")
    style = packet.get("style")
    if style not in {"ascii", "rle", "grid"}:
        raise CodecError("packet style is invalid")
    if (set(packet) != {"codec", "style", "frame", "maps"}
            or not isinstance(packet.get("maps"), list)):
        raise CodecError("standalone map packet fields are invalid")
    frame = _copy(packet.get("frame"))
    if not isinstance(frame, dict):
        raise CodecError("packet frame must be an object")
    seen_paths = set()
    for number, entry in enumerate(packet["maps"]):
        if (not isinstance(entry, dict) or set(entry) != {"path", "body"}
                or not isinstance(entry["path"], list) or not entry["path"]):
            raise CodecError(f"map entry {number} is invalid")
        path_key = tuple(entry["path"])
        try:
            duplicate = path_key in seen_paths
        except TypeError as error:
            raise CodecError(f"map entry {number} path has an invalid component") from error
        if duplicate:
            raise CodecError("duplicate encoded map path")
        seen_paths.add(path_key)
        if entry["path"][-1] != "map":
            raise CodecError("encoded map path must end at a map field")
        parent, key = _target(frame, entry["path"])
        try:
            placeholder = parent[key]
        except (KeyError, IndexError, TypeError) as error:
            raise CodecError("encoded map placeholder is missing") from error
        if placeholder != {"$standalone_map": number}:
            raise CodecError("encoded map placeholder does not match its entry")
        body = entry["body"]
        if not isinstance(body, dict) or body.get("v") != MAP_VERSION or body.get("style") != style:
            raise CodecError("encoded map version or style is invalid")
        if style == "ascii":
            parent[key] = _decode_ascii(body)
        elif style == "rle":
            parent[key] = _decode_rle(body)
        else:
            parent[key] = _decode_grid(body)
    return frame
