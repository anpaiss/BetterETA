#!/usr/bin/env python3
"""
Replay del fattore di giornata CON i fondi, per confrontare varianti del
fattore sui giri reali (17/09: fattore per fondo, globale meno reattivo).

Estende tools/replay_model.py (che resta il riferimento per la griglia a soli
bucket): stessa metrica — a ogni minuto, l'ETA che il modello darebbe se la
route fosse ciò che poi è stato pedalato — ma:
  - base per bucket (pendenza × fondo) come sul dispositivo, presa dal log del
    giorno (ultima riga "MODEL Salvato" prima del giro, con pav[/gravel[/...);
  - varianti del fattore: produzione (globale P=120 + per bucket S=120),
    globale lento, per fondo, gerarchia fondo→(fondo,bucket), asimmetrico;
  - metriche con segno: errore medio assoluto, medio con segno, estremo con
    segno nei primi 15 minuti (dove il fattore fa i danni), massimo assoluto;
  - "normalizzazione memoria": per ogni riga WAY del log, quanto il fattore
    usato al momento del passaggio differisce da quello di fine giro per lo
    stesso (fondo, bucket): media di |ln(f_pass / f_fine)|. È l'errore con cui
    la memoria tratti salva la velocità di base.

Uso:
  python tools/replay_factor.py --logs "logs/better_eta_2026-09-*.log" --from 2026-09-05 \
      logs/better_eta_samples_old_2026-09-05.csv logs/better_eta_samples_2026-09-17.csv
  --trace <nome variante>   stampa f globale / per fondo nel tempo
"""
import sys, re, glob, math, argparse
from datetime import datetime

# Come AdaptiveSpeedModel dal 19/09: sette bucket, muro diviso al 16 %.
# --legacy-six rifà i conti col layout precedente (confronto prima/dopo).
THRESHOLDS = [-6.0, -2.0, 2.0, 6.0, 10.0, 16.0]
FACTORS = [1.15, 1.10, 1.0, 0.80, 0.65, 0.50, 0.42]
NB = 7


def set_thresholds(extra, legacy_six=False):
    """--split 20: aggiunge soglie in salita; gli snapshot più corti vengono
    migrati duplicando l'ultimo bucket (come fa l'app)."""
    global THRESHOLDS, FACTORS, NB
    if legacy_six:
        THRESHOLDS, FACTORS = THRESHOLDS[:5], FACTORS[:6]
    for t in extra:
        THRESHOLDS.append(t); FACTORS.append(FACTORS[-1] * 0.85)
    NB = len(THRESHOLDS) + 1


def migrate(lst):
    lst = list(lst)
    while 0 < len(lst) < NB:
        lst.append(lst[-1])
    return lst
FLAT = 2
MIN_TRUSTED = 5
ALPHA_GRADE = 0.10
BASE_WINDOW = 21600
SURFACES = ('PAVED', 'GRAVEL', 'TRAIL', 'GNARLY')
RATIO_MIN, RATIO_MAX = 0.2, 3.0


def bucket_index(g):
    for i, t in enumerate(THRESHOLDS):
        if g < t:
            return i
    return len(THRESHOLDS)


def surface_class(s):
    """Classe del fattore: asfalto / non asfalto; ignoto → None (usa il ramo senza fondo)."""
    if s == 'PAVED':
        return 'PAVED'
    if s in ('GRAVEL', 'TRAIL', 'GNARLY'):
        return 'UNPAVED'
    return None


# ── base ────────────────────────────────────────────────────────────────

class Buckets:
    """Base come AdaptiveSpeedModel: bucket globali + per fondo, EMA lunga."""
    def __init__(self, snapshot, window=BASE_WINDOW):
        self.window = window
        self.ema = [0.0] * NB; self.cnt = [0] * NB
        self.surf = {s: ([0.0] * NB, [0] * NB) for s in SURFACES}
        self.overall, self.n = 0.0, 0
        self.smoothed = 0.0
        if snapshot:
            self.overall, self.n = snapshot['overall']
            for i, (e, c) in enumerate(migrate(snapshot['buckets'])):
                self.ema[i], self.cnt[i] = e, c
            for s in SURFACES:
                for i, (e, c) in enumerate(migrate(snapshot.get(s, []))):
                    self.surf[s][0][i], self.surf[s][1][i] = e, c

    def _step(self, ema, x, prev):
        a = 1.0 / min(prev + 1, self.window)
        return a * x + (1 - a) * ema

    def update(self, v, g, surface):
        self.smoothed = g if self.n == 0 else ALPHA_GRADE * g + (1 - ALPHA_GRADE) * self.smoothed
        i = bucket_index(self.smoothed)
        self.ema[i] = self._step(self.ema[i], v, self.cnt[i]); self.cnt[i] += 1
        if surface in self.surf:
            e, c = self.surf[surface]
            e[i] = self._step(e[i], v, c[i]); c[i] += 1
        self.overall = self._step(self.overall, v, self.n); self.n += 1
        return i

    def flat(self):
        if self.cnt[FLAT] >= MIN_TRUSTED: return self.ema[FLAT]
        if self.n > 0: return self.overall
        return 5.0

    def raw(self, i, surface):
        if surface in self.surf and self.surf[surface][1][i] >= MIN_TRUSTED:
            return self.surf[surface][0][i]
        if self.cnt[i] >= MIN_TRUSTED:
            return self.ema[i]
        return max(0.5, self.flat() * FACTORS[i])


# ── fattore di giornata ─────────────────────────────────────────────────

class Ema:
    __slots__ = ('f', 'n')
    def __init__(self): self.f, self.n = 1.0, 0
    def push(self, r, prior, window):
        a = 1.0 / min(self.n + prior + 1, window)
        self.f = a * r + (1 - a) * self.f
        self.n += 1


def shrink(leaf, parent_f, s):
    w = leaf.n / (leaf.n + s) if s > 0 else (1.0 if leaf.n > 0 else 0.0)
    return w * leaf.f + (1 - w) * parent_f


class FactorModel:
    """
    mode:
      'none'    solo globale
      'bucket'  globale + per bucket (produzione dal 05/09)
      'surface' globale + per fondo (asfalto / non asfalto); fondo ignoto → per bucket
      'hier'    globale → per fondo → per (fondo, bucket); fondo ignoto → per bucket
    pg = prior del globale, pl = prior delle foglie, s = shrink, w = finestra EMA,
    up = prior del globale quando il rapporto è > 1 (asimmetrico), None = pg.
    """
    def __init__(self, snapshot, mode='bucket', pg=120, pl=120, s=120, w=900, up=None, name=None, base_window=BASE_WINDOW, carry_f=0.0):
        self.b = Buckets(snapshot, base_window)
        self.mode, self.pg, self.pl, self.s, self.w, self.up = mode, pg, pl, s, w, up
        self.carry_f = carry_f   # quota del fattore globale finale riportata al giro dopo (0 = riparte da 1)
        self.g = Ema()
        self.bk = [Ema() for _ in range(NB)]
        self.cls = {'PAVED': Ema(), 'UNPAVED': Ema()}
        self.leaf = {'PAVED': [Ema() for _ in range(NB)], 'UNPAVED': [Ema() for _ in range(NB)]}
        self.name = name or f"{mode}(pg={pg},pl={pl},S={s}{',up=' + str(up) if up else ''})"

    def update(self, v, g, surface):
        if v < 0.5:
            return
        gi = bucket_index(self.b.smoothed if self.b.n else g)
        base = self.b.raw(gi, surface)
        i = self.b.update(v, g, surface)
        if base <= 0.0:
            return
        r = max(RATIO_MIN, min(RATIO_MAX, v / base))
        prior_g = self.up if (self.up and r > 1.0) else self.pg
        self.g.push(r, prior_g, self.w)
        self.bk[i].push(r, self.pl, self.w)
        c = surface_class(surface)
        if c and self.mode in ('surface', 'hier'):
            self.cls[c].push(r, self.pl, self.w)
            self.leaf[c][i].push(r, self.pl, self.w)

    def factor(self, g, surface):
        i = bucket_index(g)
        c = surface_class(surface)
        if self.mode == 'none':
            return self.g.f
        if self.mode == 'bucket' or c is None:
            return shrink(self.bk[i], self.g.f, self.s)
        parent = shrink(self.cls[c], self.g.f, self.s)
        if self.mode == 'surface':
            return parent
        return shrink(self.leaf[c][i], parent, self.s)

    def predict(self, g, surface):
        return max(0.5, self.b.raw(bucket_index(g), surface) * self.factor(g, surface))

    def new_ride(self):
        """Idle → Recording: fattori a 1, base conservata (come AdaptiveSpeedModel.newRide)."""
        start = 1.0 + self.carry_f * (self.g.f - 1.0)
        self.g = Ema(); self.g.f = start
        self.bk = [Ema() for _ in range(NB)]
        self.cls = {'PAVED': Ema(), 'UNPAVED': Ema()}
        self.leaf = {'PAVED': [Ema() for _ in range(NB)], 'UNPAVED': [Ema() for _ in range(NB)]}


# ── dati ────────────────────────────────────────────────────────────────

def load_rides(paths):
    rides, cur = [], None
    for p in paths:
        with open(p, encoding='utf-8') as fh:
            for line in fh:
                if line.startswith('# ride'):
                    day = line.split()[2]
                    if cur is None or cur['day'] != day:
                        cur = {'day': day, 'rows': []}
                        rides.append(cur)
                    continue
                if line.startswith('#') or line.startswith('t_ms') or cur is None:
                    continue
                c = line.rstrip('\n').split(',')
                try:
                    t = int(c[0]); state = c[1]; v = float(c[3]); g = float(c[4])
                except (ValueError, IndexError):
                    continue
                cur['rows'].append((t, state, v, g, c[5], c[8]))
    return rides


SMOOTH_PRED = False   # --smooth-pred: pendenza smussata (come impara il modello) anche in previsione


def prepare(rows):
    out, prev_t, sg = [], None, None
    for t, state, v, g, surf, on_route in sorted(rows, key=lambda r: r[0]):
        dt = 1.0 if prev_t is None else min(2.0, max(0.0, (t - prev_t) / 1000.0))
        prev_t = t
        if state != 'Recording' or v < 0.5 or on_route != '1':
            continue
        sg = g if sg is None else ALPHA_GRADE * g + (1 - ALPHA_GRADE) * sg
        out.append((t, v, sg if SMOOTH_PRED else g, surf, v * dt, dt))
    return out


SNAP_RE = re.compile(r'^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)\.\d+ \[MODEL\] Salvato(?P<unit> \(km/h\))?: overall=(?P<ov>[\d.]+)(?:m/s)? n=(?P<on>\d+) \| (?P<rest>.*)')
BUCKET_RE = re.compile(r'b(\d)=([\d.]+)(?:m/s)?\(n=(\d+)\)')
SURF_RE = re.compile(r'(pav|gravel|trail|gnarly)\[([^\]]*)\]')
WAY_RE = re.compile(r'^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)\.\d+ \[WAY  \] way \S+ .: ([\d.]+)km/h su (\d+)m in (\d+)s → base ([\d.]+)km/h \(f=([\d.]+)\)')
SURF_KEY = {'pav': 'PAVED', 'gravel': 'GRAVEL', 'trail': 'TRAIL', 'gnarly': 'GNARLY'}


def parse_ts(s):
    return datetime.strptime(s, '%Y-%m-%d %H:%M:%S').timestamp() * 1000


def load_logs(pattern):
    """Snapshot (ts, dict) e passaggi way (ts, durata_s, f_usato) da tutti i log."""
    snaps, ways = [], []
    for p in sorted(set(glob.glob(pattern))):
        with open(p, encoding='utf-8', errors='replace') as fh:
            for line in fh:
                m = SNAP_RE.match(line)
                if m and 'pav[' in line:
                    k = 1 / 3.6 if m.group('unit') else 1.0
                    snap = {'overall': (float(m.group('ov')) * k, int(m.group('on')))}
                    b = {int(i): (float(e) * k, int(n)) for i, e, n in BUCKET_RE.findall(m.group('rest'))}
                    snap['buckets'] = [b.get(i, (0.0, 0)) for i in range(NB)]
                    for name, body in SURF_RE.findall(m.group('rest')):
                        vals = re.findall(r'([\d.]+)\(n=(\d+)\)', body)
                        snap[SURF_KEY[name]] = [(float(e) * k, int(n)) for e, n in vals]
                    snaps.append((parse_ts(m.group(1)), snap, p))
                    continue
                m = WAY_RE.match(line)
                if m:
                    ways.append((parse_ts(m.group(1)), int(m.group(4)), float(m.group(6))))
    snaps.sort(key=lambda x: x[0]); ways.sort(key=lambda x: x[0])
    # dedup: gli stessi log vengono scaricati più volte (le copie datate si sovrappongono)
    seen, uniq = set(), []
    for ts, snap, p in snaps:
        if ts not in seen:
            seen.add(ts); uniq.append((ts, snap, p))
    return uniq, sorted(set(ways))


def pick_snapshot(snaps, t0):
    before = [s for s in snaps if s[0] <= t0]
    if before:
        return before[-1], 'prima del giro'
    after = [s for s in snaps if s[0] > t0]
    return (after[0], 'PRIMO DISPONIBILE (dopo il giro)') if after else (None, 'nessuno')


# ── valutazione ─────────────────────────────────────────────────────────

def evaluate(factory, samples, ways, step_s=60, min_remaining_s=300, early_s=900, model=None):
    n = len(samples)
    if n < 2 * min_remaining_s:
        return None
    model = model or factory()
    errs, early = [], []
    t_start = samples[0][0]
    # tempo in movimento reale rimanente = somma dei dt (i campioni non sono
    # esattamente uno al secondo: contarli sottostima il reale del 3-4 %)
    rem_after = [0.0] * (n + 1)
    for k in range(n - 1, -1, -1):
        rem_after[k] = rem_after[k + 1] + samples[k][5]
    next_eval = t_start
    # passaggi way dentro il giro: (ts, durata) → f al momento, calcolato sui campioni del passaggio
    ride_ways = [(ts, dur) for ts, dur, _ in ways if t_start <= ts <= samples[-1][0]]
    wi, pass_f = 0, []   # (fondo, bucket, f_pass)
    for k, (t, v, g, surf, d, _dt) in enumerate(samples):
        while wi < len(ride_ways) and ride_ways[wi][0] <= t:
            ts, dur = ride_ways[wi]; wi += 1
            win = [s for s in samples[max(0, k - dur):k + 1] if s[0] >= ts - dur * 1000]
            if win:
                gm = sum(s[2] for s in win) / len(win)
                sm = max(set(s[3] for s in win), key=[s[3] for s in win].count)
                pass_f.append((sm, gm, model.factor(gm, sm)))
        if t >= next_eval:
            next_eval = t + step_s * 1000
            remaining = rem_after[k]
            if remaining >= min_remaining_s:
                pred = sum(dd / model.predict(gg, ss) for (_, _, gg, ss, dd, _) in samples[k:])
                e = (pred - remaining) / remaining
                errs.append(e)
                if t - t_start <= early_s * 1000:
                    early.append(e)
        model.update(v, g, surf)
    if not errs:
        return None
    mem = [abs(math.log(fp / model.factor(gm, sm))) for sm, gm, fp in pass_f if fp > 0]
    return dict(
        mean_abs=sum(abs(e) for e in errs) / len(errs),
        mean_signed=sum(errs) / len(errs),
        max_abs=max(abs(e) for e in errs),
        early_min=min(early) if early else 0.0,
        early_max=max(early) if early else 0.0,
        minutes=len(errs),
        mem=(sum(mem) / len(mem)) if mem else None,
        passes=len(mem),
    )


def variants(snap):
    return [
        lambda: FactorModel(snap, 'bucket', 120, 120, 120, name='PROD bucket pg120'),
        lambda: FactorModel(snap, 'bucket', 400, 120, 120, name='B    bucket pg400'),
        lambda: FactorModel(snap, 'surface', 120, 120, 120, name='A    surface pg120'),
        lambda: FactorModel(snap, 'surface', 400, 120, 120, name='A+B  surface pg400'),
        lambda: FactorModel(snap, 'hier', 120, 120, 120, name='H    hier pg120'),
        lambda: FactorModel(snap, 'hier', 400, 120, 120, name='H+B  hier pg400'),
        lambda: FactorModel(snap, 'hier', 400, 120, 60, name='H+B  hier pg400 S60'),
        lambda: FactorModel(snap, 'hier', 600, 120, 120, name='H+B  hier pg600'),
        lambda: FactorModel(snap, 'hier', 120, 120, 120, up=400, name='H+C  hier up400'),
        lambda: FactorModel(snap, 'bucket', 120, 120, 120, up=400, name='C    bucket up400'),
        lambda: FactorModel(snap, 'none', 120, 120, 120, name='none pg120'),
        lambda: FactorModel(snap, 'none', 100000, 120, 120, name='no factor'),
    ]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('csv', nargs='+')
    ap.add_argument('--logs', required=True, help='glob dei log (snapshot per giorno e righe WAY)')
    ap.add_argument('--from', dest='from_day', default='')
    ap.add_argument('--trace', default='', help='traccia f nel tempo per la variante con questo nome')
    ap.add_argument('--legacy-six', action='store_true', help='layout a sei bucket (fino al 19/09)')
    ap.add_argument('--split', type=float, nargs='*', default=[], help='soglie di pendenza in più in salita, es. --split 20')
    ap.add_argument('--smooth-pred', action='store_true', help='pendenza smussata anche in previsione')
    ap.add_argument('--start', default='device', choices=['device', 'empty', 'nosurf'],
                    help='--carry: base di partenza = snapshot del dispositivo, vuota, o snapshot senza i bucket per fondo')
    ap.add_argument('--carry', action='store_true', help='in sequenza: parte dal primo snapshot e porta il modello di giro in giro, con finestre di base diverse')
    args = ap.parse_args()
    global SMOOTH_PRED
    SMOOTH_PRED = args.smooth_pred
    set_thresholds(args.split, args.legacy_six)

    snaps, ways = load_logs(args.logs)
    print(f"snapshot trovati: {len(snaps)}, passaggi way: {len(ways)}")
    if args.carry:
        return carry(args, snaps, ways)
    totals = {}
    for ride in load_rides(args.csv):
        if ride['day'] < args.from_day:
            continue
        samples = prepare(ride['rows'])
        if len(samples) < 900:
            continue
        picked, how = pick_snapshot(snaps, samples[0][0])
        if not picked:
            print(f"\n== {ride['day']}: nessuno snapshot, salto"); continue
        snap = picked[1]
        print(f"\n== giorno {ride['day']}  {len(samples)/60:.0f} min in movimento  snapshot {how}: "
              f"{datetime.fromtimestamp(picked[0]/1000):%Y-%m-%d %H:%M} (piano asfalto {snap.get('PAVED', [(0,0)]*3)[2][0]*3.6:.1f} km/h)")
        print(f"  {'variante':24s} {'|err|':>6s} {'segno':>6s} {'max':>6s} {'15min':>13s} {'mem':>6s}")
        for cf in variants(snap):
            r = evaluate(cf, samples, ways)
            if not r:
                continue
            name = cf().name
            mem = f"{r['mem']*100:5.1f}%" if r['mem'] is not None else '   -  '
            print(f"  {name:24s} {r['mean_abs']*100:5.1f}% {r['mean_signed']*100:+5.1f}% {r['max_abs']*100:5.1f}% "
                  f"{r['early_min']*100:+5.1f}..{r['early_max']*100:+5.1f}% {mem} ({r['passes']})")
            t = totals.setdefault(name, {'abs': [], 'signed': [], 'early': [], 'mem': []})
            t['abs'].append(r['mean_abs']); t['signed'].append(r['mean_signed'])
            t['early'].append(max(abs(r['early_min']), abs(r['early_max'])))
            if r['mem'] is not None: t['mem'].append(r['mem'])
        if args.trace:
            for cf in variants(snap):
                m = cf()
                if m.name.strip() != args.trace.strip():
                    continue
                print(f"  traccia {m.name}: min  f_glob  f_pav  f_unp  | foglie asfalto | foglie non asfalto")
                for k, (t, v, g, surf, d, _dt) in enumerate(samples):
                    m.update(v, g, surf)
                    if (k + 1) % 300 == 0:
                        pav = ' '.join(f"{shrink(m.leaf['PAVED'][i], shrink(m.cls['PAVED'], m.g.f, m.s), m.s):.2f}" for i in range(NB))
                        unp = ' '.join(f"{shrink(m.leaf['UNPAVED'][i], shrink(m.cls['UNPAVED'], m.g.f, m.s), m.s):.2f}" for i in range(NB))
                        print(f"    {(k+1)//60:3d}  {m.g.f:.2f}   {shrink(m.cls['PAVED'], m.g.f, m.s):.2f}   {shrink(m.cls['UNPAVED'], m.g.f, m.s):.2f}  | {pav} | {unp}")
    print("\n== media sui giorni")
    print(f"  {'variante':24s} {'|err|':>6s} {'segno':>6s} {'|15min|':>7s} {'mem':>6s}")
    for name, t in sorted(totals.items(), key=lambda kv: sum(kv[1]['abs']) / len(kv[1]['abs'])):
        mem = f"{sum(t['mem'])/len(t['mem'])*100:5.1f}%" if t['mem'] else '   -  '
        print(f"  {name:24s} {sum(t['abs'])/len(t['abs'])*100:5.1f}% {sum(t['signed'])/len(t['signed'])*100:+5.1f}% "
              f"{sum(t['early'])/len(t['early'])*100:6.1f}% {mem}")


def carry(args, snaps, ways):
    rides = [r for r in load_rides(args.csv) if r['day'] >= args.from_day]
    rides = [(r['day'], prepare(r['rows'])) for r in rides]
    rides = [(d, s) for d, s in rides if len(s) >= 900]
    first, how = pick_snapshot(snaps, rides[0][1][0][0])
    print(f"partenza dallo snapshot {datetime.fromtimestamp(first[0]/1000):%Y-%m-%d %H:%M} ({how}), {len(rides)} giri in sequenza, base di partenza: {args.start}")
    if args.start == 'empty':
        first = (first[0], None)
    elif args.start == 'nosurf':
        first = (first[0], {k: v for k, v in first[1].items() if k in ('overall', 'buckets')})
    configs = [
        ('PROD base 6h',      dict(mode='bucket', pg=120, base_window=21600)),
        ('B    base 6h pg400', dict(mode='bucket', pg=400, base_window=21600)),
        ('PROD base 3h',      dict(mode='bucket', pg=120, base_window=10800)),
        ('PROD base 2h',      dict(mode='bucket', pg=120, base_window=7200)),
        ('PROD base 1h',      dict(mode='bucket', pg=120, base_window=3600)),
        ('PROD base 30min',   dict(mode='bucket', pg=120, base_window=1800)),
        ('B    base 2h pg400', dict(mode='bucket', pg=400, base_window=7200)),
        ('H+B  base 2h pg400', dict(mode='hier',   pg=400, base_window=7200)),
        ('no factor base 2h', dict(mode='none',   pg=100000, base_window=7200)),
        ('no factor base 6h', dict(mode='none',   pg=100000, base_window=21600)),
        ('solo per bucket',   dict(mode='bucket', pg=100000, base_window=21600)),
        ('solo globale pg400', dict(mode='none',  pg=400, base_window=21600)),
        ('PROD f-carry 0.5',  dict(mode='bucket', pg=120, base_window=21600, carry_f=0.5)),
        ('PROD f-carry 1.0',  dict(mode='bucket', pg=120, base_window=21600, carry_f=1.0)),
        ('B    f-carry 0.5',  dict(mode='bucket', pg=400, base_window=21600, carry_f=0.5)),
        ('B    f-carry 1.0',  dict(mode='bucket', pg=400, base_window=21600, carry_f=1.0)),
    ]
    print(f"  {'variante':20s} " + ' '.join(f"{d[5:]:>12s}" for d, _ in rides) + "   media |err|  segno")
    for name, kw in configs:
        m = FactorModel(first[1], name=name, **kw)
        cells, abs_, sg = [], [], []
        for day, samples in rides:
            m.new_ride()
            r = evaluate(None, samples, ways, model=m)
            if r:
                cells.append(f"{r['mean_abs']*100:4.1f}%{r['mean_signed']*100:+5.1f}"); abs_.append(r['mean_abs']); sg.append(r['mean_signed'])
            else:
                cells.append('      -     ')
        print(f"  {name:20s} " + ' '.join(f"{c:>12s}" for c in cells) + f"   {sum(abs_)/len(abs_)*100:5.1f}% {sum(sg)/len(sg)*100:+5.1f}%")


if __name__ == '__main__':
    main()
