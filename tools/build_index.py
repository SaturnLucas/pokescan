"""
Gera o índice visual de cartas do PokéScan.

1. Lista as cartas físicas (TCGdex, inglês) que têm imagem.
2. Para cada uma calcula dois vetores (MobileNetV3 small, MediaPipe): a carta inteira e só a ARTE
   (recorte central, onde as cartas realmente se diferenciam).
3. Testa várias configurações (carta / arte / as duas; 128 ou 256 dimensões) com fotos simuladas
   (rotação, brilho, desfoque, fundo, carta menor que a moldura) e fica com a melhor.
4. Grava cardindex.bin e report.md.

Formato do cardindex.bin (little-endian), versão 2:
  "PSIX" | version u32 | N u32 | DIN u32 | D u32 | thr f32 | sha256 do modelo (32 bytes) | mode u32
  mean f32[DIN] | proj f32[DIN*D] (linhas = DIN) | vecs i8[N*D]
  meta_len u32 | meta utf-8: N linhas "id \t nome \t número \t imagemBase"
mode: bit0 = vetor da carta inteira, bit1 = vetor da arte. DIN = 1024 x (nº de vistas).
Cada vista é normalizada (L2) e as vistas são concatenadas antes do PCA.
Recorte da arte (frações da carta): x 0.08..0.92, y 0.12..0.56  (o app usa os mesmos valores).
"""
import hashlib, io, os, random, struct, sys, time
import concurrent.futures as cf

import numpy as np
import requests
from PIL import Image, ImageEnhance, ImageFilter

import mediapipe as mp
from mediapipe.tasks import python as mpp
from mediapipe.tasks.python import vision

API = "https://api.tcgdex.net/v2/en/cards"
MODEL = "model.tflite"
WORKERS = 8
ART = (0.08, 0.12, 0.92, 0.56)
t0 = time.time()

sess = requests.Session()
sess.headers["User-Agent"] = "PokeScan-index-builder (github.com/SaturnLucas/pokescan)"


def log(*a):
    print(f"[{time.time() - t0:6.0f}s]", *a, flush=True)


def get(url, tries=5, as_json=False):
    for i in range(tries):
        try:
            r = sess.get(url, timeout=40)
            if r.status_code == 404:
                return None
            r.raise_for_status()
            return r.json() if as_json else r.content
        except Exception:
            if i == tries - 1:
                return None
            time.sleep(1.5 * (i + 1))


cards = get(API, as_json=True)
if not cards:
    sys.exit("Não consegui listar as cartas do TCGdex")
items = [c for c in cards if c.get("image") and c.get("id") and not c["id"][:1].isupper()]
log(f"{len(cards)} cartas listadas, {len(items)} físicas com imagem")

opts = vision.ImageEmbedderOptions(
    base_options=mpp.BaseOptions(model_asset_path=MODEL),
    running_mode=vision.RunningMode.IMAGE,
    l2_normalize=False,
    quantize=False,
)
embedder = vision.ImageEmbedder.create_from_options(opts)


def embed_pil(im):
    arr = np.ascontiguousarray(np.asarray(im.convert("RGB"), dtype=np.uint8))
    res = embedder.embed(mp.Image(image_format=mp.ImageFormat.SRGB, data=arr))
    v = np.asarray(res.embeddings[0].embedding, dtype=np.float32)
    n = np.linalg.norm(v)
    return v / n if n > 0 else v


def two_views(im):
    w, h = im.size
    art = im.crop((int(w * ART[0]), int(h * ART[1]), int(w * ART[2]), int(h * ART[3])))
    return embed_pil(im), embed_pil(art)


def fetch(card):
    data = get(card["image"] + "/low.webp")
    if not data:
        return card, None
    try:
        return card, Image.open(io.BytesIO(data)).convert("RGB")
    except Exception:
        return card, None


kept, F, A, tests = [], [], [], []
random.seed(7)
with cf.ThreadPoolExecutor(WORKERS) as ex:
    for start in range(0, len(items), 400):
        chunk = items[start:start + 400]
        for card, im in ex.map(fetch, chunk):
            if im is None:
                continue
            f, a = two_views(im)
            F.append(f)
            A.append(a)
            kept.append(card)
            if len(tests) < 500 and random.random() < 0.06:
                tests.append((len(kept) - 1, im))
        log(f"{len(kept)} cartas ({start + len(chunk)}/{len(items)})")

N = len(kept)
if N < 1000:
    sys.exit(f"Poucas cartas processadas ({N}); abortando")
F = np.vstack(F).astype(np.float32)
A = np.vstack(A).astype(np.float32)
DV = F.shape[1]


def perturb(img):
    w0, h0 = img.size
    s = random.uniform(0.85, 1.0)
    im = img.resize((max(1, int(w0 * s)), max(1, int(h0 * s))))
    bg = random.randint(20, 90)
    canvas = Image.new("RGB", (w0, h0), (bg, bg, bg))
    canvas.paste(im, ((w0 - im.width) // 2 + random.randint(-6, 6), (h0 - im.height) // 2 + random.randint(-6, 6)))
    im = canvas.rotate(random.uniform(-6, 6), resample=Image.BILINEAR, fillcolor=(bg, bg, bg))
    im = ImageEnhance.Brightness(im).enhance(random.uniform(0.7, 1.3))
    im = ImageEnhance.Contrast(im).enhance(random.uniform(0.8, 1.2))
    im = ImageEnhance.Color(im).enhance(random.uniform(0.7, 1.2))
    if random.random() < 0.6:
        im = im.filter(ImageFilter.GaussianBlur(random.uniform(0.5, 1.6)))
    return im


log("calculando as fotos simuladas")
random.seed(11)
tq = []
for idx, img in tests:
    qf, qa = two_views(perturb(img))
    tq.append((idx, qf, qa))

results = []
models = {}
for mode in (1, 2, 3):
    parts = ([F] if mode & 1 else []) + ([A] if mode & 2 else [])
    X = np.hstack(parts)
    mean = X.mean(axis=0)
    Xc = X - mean
    w, V = np.linalg.eigh(Xc.T @ Xc)
    V = np.ascontiguousarray(V[:, ::-1])
    for D in (128, 256):
        proj = np.ascontiguousarray(V[:, :D]).astype(np.float32)
        Z = Xc @ proj
        Z /= np.maximum(np.linalg.norm(Z, axis=1, keepdims=True), 1e-9)
        Q = np.clip(np.rint(Z * 127), -127, 127).astype(np.int8)
        Qf = Q.astype(np.float32) / 127.0
        top1 = top5 = name1 = name5 = 0
        good = []
        for idx, qf, qa in tq:
            qparts = ([qf] if mode & 1 else []) + ([qa] if mode & 2 else [])
            x = np.concatenate(qparts)
            z = (x - mean) @ proj
            z /= max(np.linalg.norm(z), 1e-9)
            sc = Qf @ z
            order = np.argsort(-sc)[:5]
            tn = kept[idx]["name"]
            top1 += order[0] == idx
            top5 += idx in order
            n1 = kept[order[0]]["name"] == tn
            name1 += n1
            name5 += any(kept[o]["name"] == tn for o in order)
            if n1:
                good.append(float(sc[order[0]]))
        T = len(tq)
        res = dict(mode=mode, D=D, top1=top1 / T, top5=top5 / T, name1=name1 / T, name5=name5 / T, good=good)
        results.append(res)
        models[(mode, D)] = (mean, proj, Q)
        log(f"modo={mode} D={D}: top1={res['top1']:.0%} top5={res['top5']:.0%} nome1={res['name1']:.0%} nome5={res['name5']:.0%}")

best = max(results, key=lambda r: (r["name1"] + r["name5"] + r["top1"] * 0.5 - (0.002 if r["D"] == 256 else 0)))
mode, D = best["mode"], best["D"]
mean, proj, Q = models[(mode, D)]
DIN = mean.shape[0]
thr = float(max(0.4, np.percentile(best["good"], 10))) if best["good"] else 0.6
log(f"escolhido: modo={mode} D={D} thr={thr:.3f}")

meta = "\n".join(
    "\t".join([c["id"], (c.get("name") or "").replace("\t", " ").replace("\n", " "), str(c.get("localId", "")), c["image"]])
    for c in kept
).encode("utf-8")

with open("cardindex.bin", "wb") as f:
    f.write(b"PSIX")
    f.write(struct.pack("<IIIIf", 2, N, DIN, D, thr))
    f.write(hashlib.sha256(open(MODEL, "rb").read()).digest())
    f.write(struct.pack("<I", mode))
    f.write(mean.astype("<f4").tobytes())
    f.write(np.ascontiguousarray(proj).astype("<f4").tobytes())
    f.write(Q.tobytes())
    f.write(struct.pack("<I", len(meta)))
    f.write(meta)

size = os.path.getsize("cardindex.bin")
names = {1: "carta inteira", 2: "só a arte", 3: "carta + arte"}
with open("report.md", "w", encoding="utf-8") as r:
    r.write(f"Índice visual de cartas (v2)\n\n- cartas: {N}\n- tamanho: {size / 1e6:.1f} MB\n")
    r.write(f"- escolhido: {names[mode]}, {D} dimensões (limiar {thr:.3f})\n")
    r.write(f"- teste simulado ({len(tq)} fotos com rotação/brilho/desfoque/fundo), mesma carta top-1 / top-5 | mesmo nome top-1 / top-5:\n")
    for x in results:
        mark = " <- escolhido" if (x["mode"], x["D"]) == (mode, D) else ""
        r.write(f"  - {names[x['mode']]}, {x['D']}d: {x['top1']:.0%} / {x['top5']:.0%} | {x['name1']:.0%} / {x['name5']:.0%}{mark}\n")
    r.write(f"- gerado em {time.time() - t0:.0f}s\n")
log("pronto:", size, "bytes")
