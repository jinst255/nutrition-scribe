#!/usr/bin/env python3
"""
Portion-from-a-photo: can a picture tell how MUCH of a known food was on the plate?

The app never needs a photo to say WHAT the food is — the user says that. The
question is only portion size, so every method here is told the food names and
asked for grams. The answer key is Nutrition5k (Google Research): real cafeteria
plates, photographed from above, with every ingredient weighed.

    python3 tools/bench_portion_photo.py METHOD [METHOD ...] [--n 60]

Methods:
    default                  no photo: a typical portion of each food (the app today)
    openai:<model>[:low]     OpenAI vision over the user's own key (":low" = low-detail image)
    local:<name>             a vision model on the device, via llama.cpp (llama-mtmd-cli)
                             <name> is a key of LOCAL_MODELS below

Scored on calories: each method's grams times the dish's own measured calories per
gram, so the only error measured is the portion. Personal-ish data: none (public
dataset); results go to tools/.cache/portion_<method>.json.
"""
import argparse, base64, json, os, re, statistics, subprocess, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
N5K = os.path.expanduser("~/.cache/ns-vision/n5k")
VISION = os.path.expanduser("~/.cache/ns-vision")
CACHE = os.path.join(HERE, ".cache")

LOCAL_MODELS = {
    # name: (model, mmproj, size on a phone, what it needs)
    "smolvlm2-500m": ("SmolVLM2-500M-Video-Instruct-Q8_0.gguf", "mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf"),
    "qwen2.5-vl-3b": ("Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf", "mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf"),
    "gemma3-4b": ("gemma-3-4b-it-Q4_K_M.gguf", "mmproj-model-f16.gguf"),
}

PROMPT = """This photo shows one plate of food, from directly above. The plate holds: {foods}.
Estimate the weight in grams of each food as it is on the plate. A standard dinner plate is about 26 cm across.
Answer with JSON only, exactly this shape: {{"items": [{{"name": "<food>", "grams": <number>}}]}} with one entry per food listed, in the same order."""


def load(n):
    dishes = json.load(open(os.path.join(N5K, "sample.json")))
    return dishes[:n]


def truth(d):
    return sum(x["kcal"] for x in d["main"]), sum(x["grams"] for x in d["main"])


def kcal_of(d, grams):
    """Calories for the estimated grams, at each ingredient's own measured density."""
    total = 0.0
    for x, g in zip(d["main"], grams):
        total += (x["kcal"] / x["grams"]) * g if x["grams"] > 0 else 0
    return total


def parse_grams(text, d):
    """Pull the grams out of a model's answer, by order, tolerating chatter around the JSON."""
    m = re.search(r"\{.*\}", text, re.S)
    if m:
        try:
            items = json.loads(m.group(0)).get("items", [])
            g = [float(i.get("grams", 0) or 0) for i in items]
            if len(g) >= len(d["main"]):
                return g[:len(d["main"])]
        except Exception:
            pass
    nums = [float(x) for x in re.findall(r"(\d+(?:\.\d+)?)\s*(?:g|grams)\b", text)]
    return nums[:len(d["main"])] if len(nums) >= len(d["main"]) else None


# ---- methods -----------------------------------------------------------------

DEFAULTS = None


def method_default(d):
    """What the app does with no photo: one ordinary serving of each named food."""
    global DEFAULTS
    if DEFAULTS is None:
        path = os.path.join(CACHE, "portion_defaults.json")
        DEFAULTS = json.load(open(path)) if os.path.exists(path) else {}
    g = [DEFAULTS.get(x["name"]) for x in d["main"]]
    if any(v is None for v in g):
        return None, 0.0
    return g, 0.0


def method_openai(d, model, detail):
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        sys.exit("OPENAI_API_KEY not set")
    img = base64.b64encode(open(os.path.join(N5K, "img", d["id"] + ".png"), "rb").read()).decode()
    body = {
        "model": model,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": PROMPT.format(foods=", ".join(x["name"] for x in d["main"]))},
            {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{img}", "detail": detail}},
        ]}],
        "max_completion_tokens": 800,
    }
    if model.startswith("gpt-5"):
        body["reasoning_effort"] = "none"
        body["temperature"] = 0
    req = urllib.request.Request("https://api.openai.com/v1/chat/completions",
                                 data=json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    t0 = time.time()
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                out = json.load(r)
            break
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503) and attempt < 3:
                time.sleep(2 + attempt * 3)
                continue
            return None, time.time() - t0, {"error": e.code}
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            if attempt < 3:
                time.sleep(3 + attempt * 3)
                continue
            return None, time.time() - t0, {"error": str(e)}
    text = out["choices"][0]["message"]["content"]
    usage = out.get("usage", {})
    return parse_grams(text, d), time.time() - t0, usage


def method_local(d, name):
    model, mmproj = LOCAL_MODELS[name]
    cli = os.path.join(VISION, "llama.cpp", "build", "bin", "llama-mtmd-cli")
    prompt = PROMPT.format(foods=", ".join(x["name"] for x in d["main"]))
    t0 = time.time()
    r = subprocess.run(
        [cli, "-m", os.path.join(VISION, model), "--mmproj", os.path.join(VISION, mmproj),
         "--image", os.path.join(N5K, "img", d["id"] + ".png"), "-p", prompt,
         "-n", "200", "--temp", "0", "-t", "4", "-c", "4096", "--no-warmup"],
        capture_output=True, text=True, timeout=900,
    )
    return parse_grams(r.stdout, d), time.time() - t0, {}


def run(method, dishes):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, "portion_" + method.replace(":", "_").replace("/", "_") + ".json")
    cache = json.load(open(path)) if os.path.exists(path) else {}
    for d in dishes:
        if d["id"] in cache:
            continue
        if method == "default":
            g, secs = method_default(d); usage = {}
        elif method.startswith("openai:"):
            parts = method.split(":")
            g, secs, usage = method_openai(d, parts[1], parts[2] if len(parts) > 2 else "high")
        elif method.startswith("local:"):
            g, secs, usage = method_local(d, method.split(":", 1)[1])
        else:
            sys.exit("unknown method " + method)
        cache[d["id"]] = {"grams": g, "seconds": round(secs, 2), "usage": usage}
        json.dump(cache, open(path, "w"), indent=1)
        print(f"  {method} {d['id']} -> {g} ({secs:.1f}s)", flush=True)
    return cache


def score(method, dishes, cache):
    errs, mass_errs, secs, failed = [], [], [], 0
    for d in dishes:
        r = cache.get(d["id"])
        if not r or not r["grams"]:
            failed += 1
            continue
        t_kcal, t_mass = truth(d)
        errs.append(abs(kcal_of(d, r["grams"]) - t_kcal) / t_kcal)
        mass_errs.append(abs(sum(r["grams"]) - t_mass) / t_mass)
        secs.append(r["seconds"])
    n = len(dishes)
    w20 = sum(e <= 0.20 for e in errs)
    w30 = sum(e <= 0.30 for e in errs)
    return (f"{method:34s} kcal MdAPE {statistics.median(errs):5.0%}  within 20%: {w20:2d}/{n}  "
            f"within 30%: {w30:2d}/{n}  mass MdAPE {statistics.median(mass_errs):5.0%}  "
            f"failed {failed}  median {statistics.median(secs) if secs else 0:.1f}s")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("methods", nargs="+")
    ap.add_argument("--n", type=int, default=60)
    a = ap.parse_args()
    dishes = load(a.n)
    lines = []
    for m in a.methods:
        lines.append(score(m, dishes, run(m, dishes)))
    print("\n".join(lines))
