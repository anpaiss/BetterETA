#!/usr/bin/env python3
"""
Estrae la traccia GPS da un file FIT del Karoo: una riga per record con
timestamp, lat, lon, velocità (m/s), distanza cumulativa (m), quota (m).

Uso:
  python tools/fit_to_track.py ride.fit track.csv

Serve `pip install fitdecode`. Le posizioni FIT sono in semicerchi: gradi = v × 180 / 2^31.
"""
import sys, csv
import fitdecode

SEMI = 180.0 / 2 ** 31


def main():
    src, dst = sys.argv[1], sys.argv[2]
    n = 0
    with fitdecode.FitReader(src) as fit, open(dst, 'w', newline='', encoding='utf-8') as out:
        w = csv.writer(out)
        w.writerow(['t', 'lat', 'lon', 'speed_ms', 'dist_m', 'alt_m'])
        for frame in fit:
            if not isinstance(frame, fitdecode.FitDataMessage) or frame.name != 'record':
                continue
            f = {x.name: x.value for x in frame.fields}
            lat = f.get('position_lat'); lon = f.get('position_long')
            if lat is None or lon is None:
                continue
            speed = f.get('enhanced_speed', f.get('speed'))
            w.writerow([f.get('timestamp'), '%.6f' % (lat * SEMI), '%.6f' % (lon * SEMI),
                        '' if speed is None else '%.2f' % speed,
                        '' if f.get('distance') is None else '%.1f' % f['distance'],
                        '' if f.get('enhanced_altitude', f.get('altitude')) is None else '%.1f' % f.get('enhanced_altitude', f.get('altitude'))])
            n += 1
    print(f'{n} punti scritti in {dst}')


if __name__ == '__main__':
    main()
