"""Packs Natural Earth GeoJSON into openchat's globe.bin (format in GlobeData.kt)."""
import json, struct, sys
src, out = sys.argv[1], sys.argv[2]
SCALE = 32767 / 180.0
def q(v): return max(-32767, min(32767, round(v * SCALE)))
def lines(path):
    rings = []
    for f in json.load(open(f"{src}/{path}.geojson", encoding="utf-8"))["features"]:
        g = f["geometry"]
        parts = [g["coordinates"]] if g["type"] == "LineString" else g["coordinates"]
        for p in parts:
            pts, last = [], None
            for lon, lat, *_ in p:
                k = (q(lon), q(lat))
                if k != last: pts.append(k); last = k
            if len(pts) >= 2: rings.append(pts)
    return rings
def layer(rings):
    b = struct.pack(">I", len(rings))
    for r in rings:
        b += struct.pack(">H", len(r)) + b"".join(struct.pack(">hh", *p) for p in r)
    return b
cities = []
for f in json.load(open(f"{src}/ne_110m_populated_places_simple.geojson", encoding="utf-8"))["features"]:
    lon, lat = f["geometry"]["coordinates"][:2]
    name = (f["properties"].get("name") or "").encode("utf-8")[:255]
    if name: cities.append((q(lon), q(lat), int(f["properties"].get("scalerank") or 10), name))
cities.sort(key=lambda c: c[2])
cb = struct.pack(">H", len(cities)) + b"".join(struct.pack(">hhBB", c[0], c[1], c[2], len(c[3])) + c[3] for c in cities)
data = b"OCG1" + layer(lines("ne_110m_coastline")) + layer(lines("ne_50m_coastline")) + layer(lines("ne_110m_admin_0_boundary_lines_land")) + cb
open(out, "wb").write(data)
print(len(data), "bytes;", len(cities), "cities")
