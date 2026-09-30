#!/usr/bin/env python3
"""
Replay a real exported log through the EXACT request the app sends today, and
count every way an entry fails to come back as usable numbers.

The user's report was "the app seems finicky about accepting an entry" with no
more detail than that, so this looks for all of the candidates at once:

  http        -- the API refused (4xx/5xx), which lands in Needs attention
  empty       -- content came back empty. GPT-5 reasoning models spend
                 max_completion_tokens on hidden reasoning first, and the app
                 allows 2000; when reasoning eats the budget the reply is ""
  parse       -- content the app's Moshi adapter would reject: not JSON, a
                 missing or non-string name, a number sent as a string
  needs_detail-- parsed, but flagged. ReviewScreen then BLANKS every number,
                 even ones the model did return
  ok          -- usable

Usage:
    OPENAI_API_KEY=... python3 tools/replay_real_log.py protein-scribe-export.json [-n 3]
"""
import argparse, json, os, sys, time, urllib.error, urllib.request
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import compare_ai_offline as V1

NUMBER_FIELDS = ["protein_grams", "calories", "carbs_grams", "fat_grams", "fiber_grams",
                 "sugar_grams", "saturated_fat_grams", "sodium_mg", "caffeine_mg"]


def app_request(key, text):
    """Byte-for-byte the body OpenAiBackend.kt builds."""
    body = json.dumps({
        "model": V1.MODEL,
        "max_completion_tokens": 2000,
        "response_format": {"type": "json_object"},
        "messages": [{"role": "system", "content": V1.SYSTEM},
                     {"role": "user", "content": text}],
    }).encode()
    req = urllib.request.Request("https://api.openai.com/v1/chat/completions", data=body,
                                 headers={"Authorization": f"Bearer {key}",
                                          "Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return json.load(r), None, time.time() - t0
    except urllib.error.HTTPError as e:
        return None, f"HTTP {e.code}", time.time() - t0
    except Exception as e:
        return None, type(e).__name__, time.time() - t0


def classify(payload, err):
    if err:
        return "http", err, None
    choice = payload["choices"][0]
    content = choice["message"].get("content") or ""
    usage = payload.get("usage", {})
    reasoning = usage.get("completion_tokens_details", {}).get("reasoning_tokens")
    if not content.strip():
        return "empty", f"finish={choice.get('finish_reason')} reasoning={reasoning}", None
    try:
        out = json.loads(content)
    except ValueError:
        return "parse", "not JSON", None
    if not isinstance(out.get("name"), str):
        return "parse", "name missing / not a string", out
    for f in NUMBER_FIELDS:
        v = out.get(f)
        if v is not None and not isinstance(v, (int, float)):
            return "parse", f"{f}={v!r}", out
    if out.get("needs_detail"):
        return "needs_detail", "", out
    return "ok", "", out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("export")
    ap.add_argument("-n", "--repeat", type=int, default=1,
                    help="send each entry this many times; flakiness only shows on repeats")
    ap.add_argument("-o", "--out")
    a = ap.parse_args()
    key = os.environ.get("OPENAI_API_KEY", "").strip() or sys.exit("set OPENAI_API_KEY")

    texts = [e["rawText"].strip() for e in json.load(open(a.export)) if (e.get("rawText") or "").strip()]
    counts, rows, secs = Counter(), [], []
    for text in texts:
        for _ in range(a.repeat):
            payload, err, s = app_request(key, text)
            kind, why, out = classify(payload, err)
            counts[kind] += 1
            secs.append(s)
            rows.append({"text": text, "kind": kind, "why": why, "out": out, "seconds": round(s, 1)})
            if kind != "ok":
                print(f"[{kind}] {text[:70]!r} {why}")
    n = sum(counts.values())
    print(f"\n{n} requests: " + ", ".join(f"{k} {v} ({v / n * 100:.0f}%)" for k, v in counts.most_common()))
    secs.sort()
    print(f"latency p50 {secs[len(secs) // 2]:.1f}s  p90 {secs[int(len(secs) * 0.9)]:.1f}s")
    if a.out:
        json.dump(rows, open(a.out, "w"), indent=1)


if __name__ == "__main__":
    main()
