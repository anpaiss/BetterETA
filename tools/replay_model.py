#!/usr/bin/env python3
"""
Replay dei campioni CSV di BetterETA attraverso il modello velocità, per
tarare le sue costanti sui giri reali invece che a occhio.

Input: uno o più better_eta_samples*.csv (anche .old), che vengono concatenati.
Ogni giro (marker "# ride ...") è valutato separatamente.

Metrica: a ogni minuto t del giro, l'ETA che il modello avrebbe dato se la
route fosse esattamente quello che poi è stato pedalato:
    T_pred(t) = sum_{s > t} d_s / v_pred_t(g_s)
con d_s = distanza del campione s (velocità × dt) e v_pred_t la previsione del
modello con la conoscenza al tempo t. T_act(t) = tempo in movimento reale
rimanente. Isola l'errore del modello da quello di posizione/route.
Errore = |T_pred - T_act| / T_act, mediato sui minuti con T_act >= 5 min.

Modelli:
  current   - sei bucket, EMA finestra 1200 (20 min), nessun fattore (fino al 05/09)
  factor    - base per bucket con finestra lunga + fattore di giornata
              (rapporto osservato/base, EMA finestra W, prior P campioni a 1.0);
              con "pb" anche un fattore per bucket, pesato n/(n+S) contro il globale.
              In produzione dal 05/09: base=21600, W=900, P=120, pb S=120.

Risultato del 05/09 (giro lento con la figlia, 71 min on-route): current 20.3%,
fattore globale 16-18%, fattore per bucket 9-11%; ottimo ampio (W 600-1200, S 60-120).

Uso:
  python tools/replay_model.py logs/better_eta_samples_old_2026-09-05.csv logs/better_eta_samples_2026-09-05.csv
  python tools/replay_model.py --grid ...   (griglia di W e P)
"""
import sys, csv, math, argparse
from collections import defaultdict

# Giri esclusi da ogni replay: non rappresentano come pedala Andrea e sporcano
# le tarature. 2026-09-27: giro breve in famiglia, lento (modello ripristinato
# a mano sul k24 allo stato di prima del giro). La notte finta del 04/09 si
# esclude già con --from 2026-09-05.
EXCLUDED_DAYS = {'2026-09-27'}

THRESHOLDS = [-6.0, -2.0, 2.0, 6.0, 10.0]
FACTORS = [1.15, 1.10, 1.0, 0.80, 0.65, 0.50]
FLAT = 2
MIN_TRUSTED = 5
ALPHA_GRADE = 0.10

# Stato del modello del k24 prima del giro del 05/09 (log "Ripristinato" 09:30),
# migrato a sei bucket: il vecchio piano sdoppiato.
SNAPSHOT_0905 = dict(
    overall=(7.63, 67661),
    buckets=[(7.7, 15785), (6.0, 18994), (6.0, 18994), (3.8, 8499), (3.4, 16288), (3.0, 8095)],
)


def bucket_index(g):
    for i, t in enumerate(THRESHOLDS):
        if g < t:
            return i
    return len(THRESHOLDS)


class Buckets:
    """EMA per bucket, media cumulativa fino a `window` campioni poi EMA."""
    def __init__(self, window, snapshot=None):
        self.window = window
        self.ema = [0.0] * 6
        self.cnt = [0] * 6
        self.overall = 0.0
        self.n = 0
        self.smoothed = 0.0
        if snapshot:
            self.overall, self.n = snapshot['overall']
            for i, (e, c) in enumerate(snapshot['buckets']):
                self.ema[i], self.cnt[i] = e, c

    def _step(self, ema, x, prev):
        a = 1.0 / min(prev + 1, self.window)
        return a * x + (1 - a) * ema

    def update(self, v, g):
        if v < 0.5:
            return None
        self.smoothed = g if self.n == 0 else ALPHA_GRADE * g + (1 - ALPHA_GRADE) * self.smoothed
        i = bucket_index(self.smoothed)
        self.ema[i] = self._step(self.ema[i], v, self.cnt[i]); self.cnt[i] += 1
        self.overall = self._step(self.overall, v, self.n); self.n += 1
        return i

    def flat(self):
        if self.cnt[FLAT] >= MIN_TRUSTED: return self.ema[FLAT]
        if self.n > 0: return self.overall
        return 5.0

    def predict(self, g):
        i = bucket_index(g)
        if self.cnt[i] >= MIN_TRUSTED:
            return self.ema[i]
        return max(0.5, self.flat() * FACTORS[i])


class CurrentModel:
    name = "current(1200)"
    def __init__(self, snapshot):
        self.b = Buckets(1200, snapshot)
    def update(self, v, g): self.b.update(v, g)
    def predict(self, g): return self.b.predict(g)


class FactorModel:
    """Base a finestra lunga + fattore di giornata (EMA del rapporto v/base)."""
    def __init__(self, snapshot, base_window, factor_window, prior, per_bucket=False, shrink=0):
        self.name = f"factor(base={base_window},W={factor_window},P={prior}{f',pb S={shrink}' if per_bucket else ''})"
        self.b = Buckets(base_window, snapshot)
        self.W = factor_window
        self.P = prior
        self.f = 1.0
        self.n = 0
        self.per_bucket = per_bucket
        self.shrink = shrink
        self.fb = [1.0] * 6
        self.nb = [0] * 6

    def update(self, v, g):
        # Rapporto contro la base PRIMA dell'aggiornamento (quello che vedrebbe in corsa)
        if v < 0.5:
            return
        base = self.b.predict(self.b.smoothed if self.b.n else g)
        i = self.b.update(v, g)
        if base <= 0.0 or i is None:
            return
        r = max(0.2, min(3.0, v / base))
        a = 1.0 / min(self.n + self.P + 1, self.W)
        self.f = a * r + (1 - a) * self.f
        self.n += 1
        if self.per_bucket:
            ab = 1.0 / min(self.nb[i] + self.P + 1, self.W)
            self.fb[i] = ab * r + (1 - ab) * self.fb[i]
            self.nb[i] += 1

    def predict(self, g):
        f = self.f
        if self.per_bucket:
            i = bucket_index(g)
            w = self.nb[i] / (self.nb[i] + self.shrink) if self.shrink > 0 else (1.0 if self.nb[i] > 0 else 0.0)
            f = w * self.fb[i] + (1 - w) * self.f
        return self.b.predict(g) * f


def load_rides(paths):
    """Un giro = tutti i marker "# ride" dello stesso giorno, concatenati: il
    Karoo riemette la route a ogni ricalcolo e il modello in corsa non riparte."""
    rides = []
    cur = None
    for p in paths:
        with open(p, encoding='utf-8') as fh:
            for line in fh:
                if line.startswith('# ride'):
                    day = line.split()[2]
                    if cur is None or cur['day'] != day:
                        cur = {'name': f"giorno {day}", 'day': day, 'rows': []}
                        rides.append(cur)
                    continue
                if line.startswith('#') or line.startswith('t_ms') or cur is None:
                    continue
                c = line.rstrip('\n').split(',')
                try:
                    t = int(c[0]); state = c[1]; v = float(c[3]); g = float(c[4])
                except (ValueError, IndexError):
                    continue
                on_route = c[8]
                cur['rows'].append((t, state, v, g, on_route))
    return rides


def prepare(rows, require_on_route):
    """Campioni in movimento con la distanza percorsa nel campione."""
    out = []
    prev_t = None
    for t, state, v, g, on_route in rows:
        dt = 1.0 if prev_t is None else min(2.0, max(0.0, (t - prev_t) / 1000.0))
        prev_t = t
        if state != 'Recording' or v < 0.5:
            continue
        if require_on_route and on_route != '1':
            continue
        out.append((t, v, g, v * dt))
    return out


def evaluate(model_factory, samples, step_s=60, min_remaining_s=300):
    """Errore medio % dell'ETA (T_pred vs T_act) valutato ogni step_s."""
    n = len(samples)
    if n < 2 * min_remaining_s:
        return None
    # tempo in movimento cumulato (1 campione = 1 s in movimento)
    errs = []
    model = model_factory()
    next_eval = samples[0][0]
    for k, (t, v, g, d) in enumerate(samples):
        if t >= next_eval:
            next_eval = t + step_s * 1000
            remaining = n - k
            if remaining >= min_remaining_s:
                pred = sum(dd / model.predict(gg) for (_, _, gg, dd) in samples[k:])
                errs.append(abs(pred - remaining) / remaining)
        model.update(v, g)
    if not errs:
        return None
    return sum(errs) / len(errs), max(errs), len(errs)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('csv', nargs='+')
    ap.add_argument('--grid', action='store_true', help='griglia di finestre/prior del fattore')
    ap.add_argument('--all-rows', action='store_true', help='usa anche i campioni fuori route')
    ap.add_argument('--from', dest='from_day', default='', help='ignora i giorni precedenti (YYYY-MM-DD)')
    ap.add_argument('--trace', default='', help='stampa il fattore nel tempo per la config con questo nome')
    ap.add_argument('--snapshot', default='', help='stato del modello a inizio giro: "overall_kmh,n;b0_kmh,n;...;b5_kmh,n" (dalla riga MODEL Ripristinato del log)')
    args = ap.parse_args()
    global SNAPSHOT_0905
    if args.snapshot:
        parts = [tuple(x.split(',')) for x in args.snapshot.split(';')]
        SNAPSHOT_0905 = dict(overall=(float(parts[0][0]) / 3.6, int(parts[0][1])),
                             buckets=[(float(v) / 3.6, int(n)) for v, n in parts[1:]])
        print('snapshot di partenza:', args.snapshot)

    rides = load_rides(args.csv)
    for ride in rides:
        if ride['day'] < args.from_day or ride['day'] in EXCLUDED_DAYS:
            continue
        ride['rows'].sort(key=lambda r: r[0])
        samples = prepare(ride['rows'], require_on_route=not args.all_rows)
        if len(samples) < 900:
            continue
        print(f"\n== {ride['name']}  campioni in movimento: {len(samples)}  ({len(samples)/60:.0f} min)")
        snap = SNAPSHOT_0905
        configs = [lambda: CurrentModel(snap)]
        if args.grid:
            for W in (120, 300, 600, 900, 1200):
                for P in (30, 120, 300):
                    configs.append(lambda W=W, P=P: FactorModel(snap, 21600, W, P))
            for W in (300, 600, 900, 1200):
                for S in (30, 60, 120):
                    for P in (60, 120):
                        configs.append(lambda W=W, S=S, P=P: FactorModel(snap, 21600, W, P, per_bucket=True, shrink=S))
        else:
            # Configurazione scelta il 05/09 (vedi README, "Adaptive speed model")
            configs.append(lambda: FactorModel(snap, 21600, 900, 120, per_bucket=True, shrink=120))
        results = []
        for cf in configs:
            m = cf()
            r = evaluate(cf, samples)
            if r:
                results.append((r[0], m.name, r[1], r[2]))
        for mean, name, mx, k in sorted(results):
            print(f"  {name:46s} errore medio {mean*100:5.1f}%  max {mx*100:5.1f}%  su {k} minuti")
        if args.trace:
            for cf in configs:
                m = cf()
                if m.name != args.trace:
                    continue
                marks = {60, 120, 180, 300, 600, 900, 1200, 1800, 2400, 3600}
                out = []
                for k, (t, v, g, d) in enumerate(samples):
                    m.update(v, g)
                    if k + 1 in marks:
                        pb = ' '.join(f"{x:.2f}" for x in m.fb) if m.per_bucket else ''
                        out.append(f"    {(k+1)//60:3d} min  f={m.f:.2f} {pb}")
                print(f"  traccia fattore {m.name}:"); print(chr(10).join(out))


if __name__ == '__main__':
    main()
