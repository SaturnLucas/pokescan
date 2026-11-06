"""
Gera o índice visual de cartas do PokéScan.

1. Lista as cartas físicas (TCGdex, inglês) que têm imagem.
2. Baixa a imagem pequena de cada uma e calcula o "embedding" (MobileNetV3 small, MediaPipe).
3. Reduz de 1024 para D dimensões (PCA), normaliza e quantiza em int8.
4. Testa a busca com fotos simuladas (rotação, brilho, desfoque, fundo) e mede o acerto.
5. Grava cardindex.bin (para o app baixar) e report.md.

Formato do cardindex.bin (little-endian):
  "PSIX" | version u32 | N u32 | DIN u32 | D u32 | thr f32
  mean f32[DIN] | proj f32[DIN*D] (linhas = DIN) | vecs i8[N*D]
  meta_len u32 | meta utf-8: N linhas "id \t nome \t número \t imagemBase"
"""
import io, os, random, struct, sys, time
import concurrent.futures as cf

import numpy as np
import requests
from PIL import Image, ImageEnhance, ImageFilter

import mediapipe as mp
from mediapipe.tasks import python as mpp
from mediapipe.tasks.python import vision

API = "https://api.tcgdex.net/v2/en/cards"
MODEL = "model.tflite"
D = 128
WORKERS = 8
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
        except Exception as e:
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


def fetch(card):
    data = get(card["image"] + "/low.webp")
    if not data:
        return card, None
    try:
        return card, Image.open(io.BytesIO(data)).convert("RGB")
    except Exception:
        return card, None


kept, vecs, tests = [], [], []
random.seed(7)
with cf.ThreadPoolExecutor(WORKERS) as ex:
    for start in range(0, len(items), 400):
        chunk = items[start:start + 400]
        for card, im in ex.map(fetch, chunk):
            if im is None:
                continue
            vecs.append(embed_pil(im))
            kept.append(card)
            if len(tests) < 500 and random.random() < 0.06:
                tests.append((len(kept) - 1, im))
        log(f"{len(kept)} embeddings ({start + len(chunk)}/{len(items)})")

N = len(kept)
if N < 1000:
    sys.exit(f"Poucas cartas processadas ({N}); abortando")
X = np.vstack(vecs).astype(np.float32)
DIN = X.shape[1]
mean = X.mean(axis=0)
Xc = X - mean
w, V = np.linalg.eigh(Xc.T @ Xc)
proj = np.ascontiguousarray(V[:, ::-1][:, :D]).astype(np.float32)


def project(v):
    z = (v - mean) @ proj
    n = np.linalg.norm(z)
    return z / n if n > 0 else z


Z = Xc @ proj
Z /= np.maximum(np.linalg.norm(Z, axis=1, keepdims=True), 1e-9)
Q = np.clip(np.rint(Z * 127), -127, 127).astype(np.int8)
Qf = Q.astype(np.float32) / 127.0
log(f"PCA {DIN}->{D} ok, índice com {N} cartas")


def perturb(img):
    w0, h0 = img.size
    s = random.uniform(0.85, 1.0)
    im = img.resize((max(1, int(w0 * s)), max(1, int(h0 * s))))
    bg = random.randint(20, 90)
    canvas = Image.new("RGB", (w0, h0), (bg, bg, bg))
    canvas.paste(im, ((w0 - im.width) // 2, (h0 - im.height) // 2))
    im = canvas.rotate(random.uniform(-6, 6), resample=Image.BILINEAR, fillcolor=(bg, bg, bg))
    im = ImageEnhance.Brightness(im).enhance(random.uniform(0.7, 1.3))
    im = ImageEnhance.Contrast(im).enhance(random.uniform(0.8, 1.2))
    im = ImageEnhance.Color(im).enhance(random.uniform(0.7, 1.2))
    if random.random() < 0.6:
        im = im.filter(ImageFilter.GaussianBlur(random.uniform(0.5, 1.6)))
    return im


top1 = top5 = name1 = name5 = 0
good_scores, bad_scores = [], []
for idx, img in tests:
    q = project(embed_pil(perturb(img)))
    sc = Qf @ q
    order = np.argsort(-sc)[:5]
    true_name = kept[idx]["name"]
    top1 += order[0] == idx
    top5 += idx in order
    n1 = kept[order[0]]["name"] == true_name
    name1 += n1
    name5 += any(kept[o]["name"] == true_name for o in order)
    (good_scores if n1 else bad_scores).append(float(sc[order[0]]))
T = len(tests)
thr = float(max(0.5, np.percentile(good_scores, 10))) if good_scores else 0.7
log(f"teste simulado ({T} fotos): top1={top1/T:.0%} top5={top5/T:.0%} mesma carta-nome top1={name1/T:.0%} top5={name5/T:.0%}")

meta = "\n".join(
    "\t".join([c["id"], (c.get("name") or "").replace("\t", " ").replace("\n", " "), str(c.get("localId", "")), c["image"]])
    for c in kept
).encode("utf-8")

with open("cardindex.bin", "wb") as f:
    f.write(b"PSIX")
    f.write(struct.pack("<IIIIf", 1, N, DIN, D, thr))
    f.write(mean.astype("<f4").tobytes())
    f.write(proj.astype("<f4").tobytes())
    f.write(Q.tobytes())
    f.write(struct.pack("<I", len(meta)))
    f.write(meta)

size = os.path.getsize("cardindex.bin")
with open("report.md", "w", encoding="utf-8") as r:
    r.write(f"Índice visual de cartas\n\n- cartas: {N}\n- tamanho: {size/1e6:.1f} MB\n- dimensões: {DIN} -> {D}\n")
    r.write(f"- limiar sugerido: {thr:.3f}\n")
    r.write(f"- teste simulado ({T} fotos com rotação/brilho/desfoque): mesma carta {top1/T:.0%} (top-1), {top5/T:.0%} (top-5); ")
    r.write(f"mesmo nome {name1/T:.0%} (top-1), {name5/T:.0%} (top-5)\n")
    if good_scores:
        r.write(f"- similaridade média dos acertos: {np.mean(good_scores):.3f}; dos erros: {np.mean(bad_scores) if bad_scores else float('nan'):.3f}\n")
    r.write(f"- gerado em {time.time() - t0:.0f}s\n")
log("pronto:", size, "bytes")
