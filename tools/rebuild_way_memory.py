#!/usr/bin/env python3
"""
Ricostruisce dai log i passaggi dal vivo della memoria tratti (way_speeds.txt).

Perché esiste (20/09): la memoria salva la velocità di base = osservata / fattore
di giornata del momento. Fino al 17/09 la base del modello era ~20 % lenta, il
fattore stava a 1,1-1,35 e i passaggi venivano salvati divisi per quel numero.
Il 17/09 la base è stata corretta (bucket per fondo reimparati dai giri dal
vivo) SENZA riscalare la memoria: col fattore tornato a ~1,0 la memoria è
rimasta lenta del 17 %. Giro 20/09 (Sopra Susà, 90 % della route in memoria):
tempo reale sulle way note 4982 s, previsto dalla memoria salvata 5861 s
(+17,6 %), previsto dalle velocità OSSERVATE degli stessi passaggi 5013 s (+0,6 %).

Cosa fa: per ogni (way, verso) che compare nelle righe `WAY` dei log
  - passaggi PRIMA del cambio di base (--base-fixed-at): usa la velocità osservata
    (come i passaggi importati dallo storico, fattore 1);
  - passaggi DOPO: usa la base loggata (fattore calcolato contro la base sana);
  - fonde gli spezzoni dello stesso passaggio (stessa chiave entro 30 min), come
    fa WaySpeedMemory.record dal 19/09: distanza totale / tempo totale;
  - tiene gli ultimi 3, passes = numero di passaggi fusi.
Le chiavi mai viste dal vivo (solo storico FIT, già a fattore 1) restano come sono.

Uso:
  python tools/rebuild_way_memory.py --logs "logs/better_eta_2026-09-*.log" \
      --base-fixed-at "2026-09-17 20:48:00" way_speeds_in.txt way_speeds_out.txt
  --check YYYY-MM-DD   non scrive: confronta il tempo reale di quel giorno con la
                       memoria ricostruita dai soli giorni precedenti
"""
import re, glob, argparse
from datetime import datetime

KEEP = 3
MERGE_S = 30 * 60
WAY_RE = re.compile(r'^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)\.\d+ \[WAY  \] way (\d+) (.): ([\d.]+)km/h su (\d+)m in (\d+)s . base ([\d.]+)km/h')


def load_passes(pattern):
    seen, out = set(), []
    for p in sorted(glob.glob(pattern)):
        with open(p, encoding='utf-8', errors='replace') as fh:
            for line in fh:
                m = WAY_RE.match(line)
                if not m or (m.group(1), m.group(2), m.group(3)) in seen:
                    continue
                seen.add((m.group(1), m.group(2), m.group(3)))
                key = int(m.group(2)) * 2 + (0 if m.group(3) == '\u2192' else 1)
                out.append(dict(t=datetime.strptime(m.group(1), '%Y-%m-%d %H:%M:%S'), key=key,
                                obs=float(m.group(4)) / 3.6, d=float(m.group(5)), sec=float(m.group(6)),
                                base=float(m.group(7)) / 3.6))
    out.sort(key=lambda e: e['t'])
    return out


def merged(passes, fixed_at):
    """{key: [(t, d, speed)]} con gli spezzoni fusi, in ordine di tempo."""
    by = {}
    for e in passes:
        v = e['obs'] if e['t'] < fixed_at else e['base']
        lst = by.setdefault(e['key'], [])
        if lst and (e['t'] - lst[-1][0]).total_seconds() <= MERGE_S:
            t0, d0, v0 = lst[-1]
            d = d0 + e['d']
            lst[-1] = (e['t'], d, d / (d0 / v0 + e['d'] / v))
        else:
            lst.append((e['t'], e['d'], v))
    return by


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('src', nargs='?'); ap.add_argument('dst', nargs='?')
    ap.add_argument('--logs', required=True)
    ap.add_argument('--base-fixed-at', required=True)
    ap.add_argument('--check', default='')
    a = ap.parse_args()
    fixed_at = datetime.strptime(a.base_fixed_at, '%Y-%m-%d %H:%M:%S')
    passes = load_passes(a.logs)

    if a.check:
        day = datetime.strptime(a.check, '%Y-%m-%d').date()
        mem = merged([e for e in passes if e['t'].date() < day], fixed_at)
        today = merged([e for e in passes if e['t'].date() == day], datetime.max)   # oggi: osservato
        real = pred = dist = 0.0; n = 0
        for key, lst in today.items():
            if key not in mem:
                continue
            m = sum(v for _, _, v in mem[key][-KEEP:]) / len(mem[key][-KEEP:])
            for _, d, v in lst:
                real += d / v; pred += d / m; dist += d; n += 1
        print(f"{a.check}: {n} passaggi su way note, {dist/1000:.1f} km, reale {real:.0f} s, "
              f"memoria ricostruita {pred:.0f} s ({100*(pred-real)/real:+.1f} %)")
        return

    by = merged(passes, fixed_at)
    lines = open(a.src, encoding='utf-8').read().splitlines()
    assert lines[0].strip() == 'WAYS1', lines[0]
    out, touched, slower = [lines[0]], 0, []
    for line in lines[1:]:
        p = line.split(' ', 4)
        if len(p) < 5 or p[0] != 'K' or int(p[1]) not in by:
            out.append(line); continue
        allp = by.pop(int(p[1]))
        lst = allp[-KEEP:]
        old = [float(x) for x in p[4].split(';') if x]
        new = [v for _, _, v in lst]
        slower.append((sum(new) / len(new)) / (sum(old) / len(old)))
        out.append('K %s %d %d %s' % (p[1], len(allp), int(lst[-1][0].timestamp() * 1000),
                                      ';'.join('%.3f' % v for v in new)))
        touched += 1
    open(a.dst, 'w', encoding='utf-8', newline='\n').write('\n'.join(out) + '\n')
    slower.sort()
    print(f"chiavi nel file {len(lines)-1}, riscritte {touched}, viste nei log ma assenti dal file {len(by)}")
    print(f"rapporto nuova/vecchia media per chiave: mediana {slower[len(slower)//2]:.2f}, "
          f"p10 {slower[len(slower)//10]:.2f}, p90 {slower[len(slower)*9//10]:.2f}")


if __name__ == '__main__':
    main()
