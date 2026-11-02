# PokéScan

App Android: escaneia cartas de Pokémon TCG com a câmera (OCR offline via ML Kit), valida na API pokemontcg.io e monta uma Pokédex de cartas (Room), com progresso por set, quantidades e valor estimado.

## Gerar o APK (sem Android Studio)
1. Crie um repositório no GitHub e suba todo este projeto (inclusive a pasta `.github`).
2. Aba **Actions** > **Build APK** > **Run workflow** (também roda sozinho a cada push na `main`).
3. Ao terminar (uns 5-8 min), baixe o artefato **PokeScan-apk**, extraia o zip e instale o `app-debug.apk` no celular.
