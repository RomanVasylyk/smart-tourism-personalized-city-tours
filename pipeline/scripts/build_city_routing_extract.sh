#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC_PBF="${SRC_PBF:-$ROOT/routing-data/slovakia-latest.osm.pbf}"
OUT_DIR="${OUT_DIR:-$ROOT/routing-data-cities}"
BUFFER_DEG="${BUFFER_DEG:-0.03}"
OSRM_IMAGE="${OSRM_IMAGE:-osrm/osrm-backend@sha256:af5d4a83fb90086a43b1ae2ca22872e6768766ad5fcbb07a29ff90ec644ee409}"
OSMIUM_IMAGE="${OSMIUM_IMAGE:-stefda/osmium-tool}"

mkdir -p "$OUT_DIR"

python3 - "$ROOT/pipeline/config/cities.yaml" "$OUT_DIR/cities.geojson" "$BUFFER_DEG" <<'PY'
import json, sys, yaml

config_path, out_path, buffer_deg = sys.argv[1], sys.argv[2], float(sys.argv[3])
data = yaml.safe_load(open(config_path, encoding="utf-8"))
cities = data["cities"] if isinstance(data, dict) else data

polygons = []
for city in cities:
    box = city["bbox"]
    south, north = box["south"] - buffer_deg, box["north"] + buffer_deg
    west, east = box["west"] - buffer_deg, box["east"] + buffer_deg
    polygons.append([[[west, south], [east, south], [east, north], [west, north], [west, south]]])

json.dump(
    {"type": "Feature", "properties": {}, "geometry": {"type": "MultiPolygon", "coordinates": polygons}},
    open(out_path, "w", encoding="utf-8"),
)
print(f"{len(polygons)} miest, buffer {buffer_deg} deg -> {out_path}")
PY

cp "$SRC_PBF" "$OUT_DIR/source.osm.pbf"

docker run --rm -v "$OUT_DIR":/d "$OSMIUM_IMAGE" \
  osmium extract -p /d/cities.geojson /d/source.osm.pbf -o /d/cities.osm.pbf --overwrite

docker run --rm -v "$OUT_DIR":/data "$OSRM_IMAGE" osrm-extract -p /opt/foot.lua /data/cities.osm.pbf
docker run --rm -v "$OUT_DIR":/data "$OSRM_IMAGE" osrm-partition /data/cities.osrm
docker run --rm -v "$OUT_DIR":/data "$OSRM_IMAGE" osrm-customize /data/cities.osrm

rm -f "$OUT_DIR/source.osm.pbf" "$OUT_DIR/cities.osm.pbf"
du -ch "$OUT_DIR"/cities.osrm* | tail -1
