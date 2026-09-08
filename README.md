# Kino GBA

Um emulador de Game Boy Advance para Android, com interface em Jetpack Compose
e núcleo de emulação baseado no [mGBA](https://mgba.io) (integrado como
submódulo Git, licença MPL 2.0).

## Aviso legal

Este aplicativo **não inclui, não distribui e não baixa ROMs de jogos nem o
BIOS do GBA**. Ele apenas executa arquivos `.gba`/`.zip` que você mesmo
fornece. Use somente ROMs de jogos que você possui legalmente (por exemplo,
extraídas dos seus próprios cartuchos). Distribuir ou obter ROMs de jogos
protegidos por direitos autorais sem autorização é ilegal na maioria dos
países.

O núcleo de emulação roda no modo de BIOS interno (HLE) do mGBA por padrão,
então **não é necessário** fornecer um arquivo de BIOS real para jogar.

## Funcionalidades

- Núcleo mGBA (ARM7TDMI, PPU, APU) via JNI/NDK, com áudio e vídeo em tempo real.
- Biblioteca de jogos: escolha uma pasta (Storage Access Framework), leitura
  automática de título/código a partir do cabeçalho da ROM, suporte a `.zip`.
- Controles na tela (D-pad, A/B, L/R, Start/Select) com temas, opacidade
  ajustável e vibração tátil.
- Suporte a controle físico Bluetooth/USB, com tela de remapeamento de botões.
- Save (SRAM/flash/EEPROM) automático por jogo.
- Save states numerados com miniatura da tela.
- Cheats (Game Genie / Action Replay) por jogo.
- Avanço rápido (fast-forward) e retrocesso (rewind) com histórico de ~60s.

## Estrutura do projeto

```
app/src/main/cpp/        Ponte JNI em C (mgba_jni.c) + CMake que integra o mGBA
app/src/main/java/...    App Android (Kotlin + Jetpack Compose)
external/mgba/           Submódulo Git do núcleo mGBA (não modificado)
.github/workflows/       CI que compila o APK automaticamente
```

A ponte nativa usa o `mCoreThread` do próprio mGBA para rodar a emulação em
tempo real (sincronizada a ~59.7 fps via `mCoreSync`), e expõe save
states/cheats como funções simples de JNI - a lógica de slots, miniaturas e
biblioteca de jogos fica inteiramente no lado Kotlin.

## Como gerar o APK

Este projeto foi desenvolvido neste ambiente sem acesso ao Android SDK/NDK
completo (rede restrita a poucos domínios), então a forma recomendada de
gerar o APK é pelo CI (GitHub Actions), que tem acesso total à internet:

1. Faça push deste repositório para o GitHub (o submódulo `external/mgba`
   já está configurado).
2. O workflow `.github/workflows/android-build.yml` roda automaticamente a
   cada push e publica o APK de debug como artefato do workflow
   ("kinogba-debug-apk").
3. Baixe o artefato e instale no celular (ative "Instalar apps de fontes
   desconhecidas" se necessário).

### Build local (Android Studio)

1. Clone o repositório com `git clone --recurse-submodules` (ou rode
   `git submodule update --init --recursive` depois de clonar).
2. Abra a pasta no Android Studio (Ladybug ou mais recente) com NDK e CMake
   instalados via SDK Manager.
3. Rode a configuração `app` num dispositivo/emulador Android 8.0+ (API 26).

## Estado do build

O código foi escrito e revisado manualmente contra os cabeçalhos e o
`CMakeLists.txt` reais do mGBA, mas **ainda não foi compilado** neste
ambiente (sem Android SDK/NDK disponível aqui). A primeira execução do CI é
o primeiro build real do projeto - é esperado que ajustes pontuais possam
ser necessários (nomes de flags do CMake, versões de dependências etc.).
