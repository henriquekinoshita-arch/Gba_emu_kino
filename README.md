# Kino Emu

Um emulador de Game Boy Advance **e Nintendo DS** para Android, com interface
em Jetpack Compose. Em vez de integrar diretamente as bibliotecas internas de
cada emulador, o app fala com os núcleos através da **API libretro**
(a mesma interface usada pelo RetroArch): cada núcleo é uma biblioteca `.so`
independente, carregada em tempo de execução via `dlopen`/`dlsym`, e a ponte
nativa deste projeto (`core_bridge.c`) só conhece essa API estável - nunca as
structs internas de cada emulador. Isso evita uma classe inteira de bugs de
incompatibilidade binária que apareceu numa versão anterior deste projeto
(quando a ponte JNI compilava contra os headers internos do mGBA e podia
ficar dessincronizada das flags de compilação reais da biblioteca).

- **GBA**: núcleo [mGBA](https://mgba.io) (submódulo Git, licença MPL 2.0),
  compilado no seu próprio modo libretro oficial (`BUILD_LIBRETRO=ON`).
- **Nintendo DS**: núcleo [melonDS DS](https://github.com/JesseTG/melonds-ds)
  (submódulo Git, licença GPLv3), que por sua vez empacota o emulador
  [melonDS](https://melonds.kuribo64.net/).

## Aviso legal

Este aplicativo **não inclui, não distribui e não baixa ROMs de jogos nem
arquivos de BIOS/firmware**. Ele apenas executa arquivos `.gba` (GBA) e
`.nds` (Nintendo DS) que você mesmo fornece. Use somente ROMs de jogos que
você possui legalmente. Distribuir ou obter ROMs de jogos protegidos por
direitos autorais sem autorização é ilegal na maioria dos países.

Ambos os núcleos rodam em modo de BIOS/firmware interno (HLE - "high level
emulation"): **não é necessário** fornecer nenhum arquivo de BIOS ou
firmware real, nem do GBA nem do Nintendo DS, para jogar.

## Funcionalidades

- Núcleos GBA e Nintendo DS via libretro, com áudio e vídeo em tempo real.
- Biblioteca de jogos unificada: escolha uma pasta (Storage Access
  Framework) e o app importa `.gba`/`.nds` reconhecidos pela extensão.
- Controles na tela (D-pad, A/B/X/Y, L/R, Start/Select).
- Tela sensível ao toque do Nintendo DS mapeada para toques/arrastos sobre a
  tela renderizada.
- Save (SRAM/flash/EEPROM) automático por jogo, com autosave periódico.
- Save states (um slot por enquanto).

## Estrutura do projeto

```
app/src/main/cpp/core_bridge.c   Ponte JNI genérica: dlopen/dlsym de um núcleo
                                  libretro por vez, sem depender de headers
                                  internos de nenhum emulador.
app/src/main/cpp/libretro/       libretro.h vendorizado (API pública, MIT).
app/src/main/cpp/CMakeLists.txt  Compila o núcleo GBA (mgba_libretro, via
                                  add_subdirectory) e a ponte (kino_bridge).
app/build.gradle.kts             Além do Gradle/AGP normal, compila o núcleo
                                  Nintendo DS (melondsds_libretro) como um
                                  projeto CMake totalmente separado por ABI
                                  (ver "Por que o núcleo NDS é separado?"
                                  abaixo) e copia o resultado para
                                  src/main/jniLibs/<abi>/.
app/src/main/java/...             App Android (Kotlin + Jetpack Compose).
external/mgba/                    Submódulo Git do núcleo mGBA (não modificado).
external/melonds-ds/               Submódulo Git do núcleo melonDS DS (não modificado).
.github/workflows/                 CI que compila o APK automaticamente.
```

### Por que o núcleo NDS é separado?

O `CMakeLists.txt` do melonds-ds referencia vários caminhos (arquivos de
licença, `.info`, includes) usando `CMAKE_SOURCE_DIR`, assumindo que ele
sempre será o projeto CMake de nível mais alto. Isso quebra se ele for
adicionado via `add_subdirectory()` a partir de outro projeto (nesse caso
`CMAKE_SOURCE_DIR` passa a apontar para a raiz do *outro* projeto). Por isso
o núcleo NDS é configurado e compilado como um projeto CMake independente
(uma chamada de `cmake`/`ninja` própria, por ABI) a partir de uma tarefa do
Gradle, e só o `.so` resultante é copiado para dentro do projeto Android -
sem essa dependência estrutural.

## Como gerar o APK

Este projeto foi desenvolvido neste ambiente sem acesso ao Android SDK/NDK
completo, então a forma recomendada de gerar o APK é pelo CI (GitHub
Actions), que tem acesso total à internet:

1. Faça push deste repositório para o GitHub (os submódulos `external/mgba`
   e `external/melonds-ds` já estão configurados).
2. O workflow `.github/workflows/android-build.yml` roda automaticamente a
   cada push e publica o APK de debug como artefato do workflow.
3. Baixe o artefato e instale no celular (ative "Instalar apps de fontes
   desconhecidas" se necessário).

### Build local (Android Studio)

1. Clone o repositório com `git clone --recurse-submodules` (ou rode
   `git submodule update --init --recursive` depois de clonar).
2. Abra a pasta no Android Studio com NDK 27 e CMake 3.22 instalados via SDK
   Manager.
3. Rode a configuração `app` num dispositivo/emulador Android 7.0+ (API 24).
   O primeiro build demora mais que o normal: a tarefa `buildMelonDsCores`
   baixa e compila as dependências do núcleo Nintendo DS (fmt, glm, libslirp
   etc.) por ABI antes do build nativo principal.

## Estado do build

A ponte JNI genérica (`core_bridge.c`) foi validada localmente rodando os
dois núcleos reais (mGBA e melonDS DS) fora do Android, incluindo um teste
de ponta a ponta com uma ROM de GBA real (boot, entrada, áudio, save state,
SRAM). O restante do app (Kotlin/Compose, `CMakeLists.txt`, a tarefa Gradle
do núcleo NDS) ainda depende do CI para a primeira verificação de build real
em Android - ajustes pontuais podem ser necessários.
