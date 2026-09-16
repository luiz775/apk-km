# KM Controller

App Android em **Kotlin** para digitalizar o controle de quilometragem, atendimentos e despesas de técnicos de campo — substituindo planilhas manuais por um fluxo com OCR, geolocalização e sincronização em nuvem.

Projeto desenvolvido e usado em operação real (ex.: em junho/2026 processou dezenas de viagens, centenas de atendimentos e milhares de km registrados).

## Funcionalidades

- Autenticação de usuários (Firebase Auth)
- Registro de quilometragem com **OCR** (Google ML Kit) a partir de foto do odômetro
- Digitalização de comprovantes de despesas
- Captura automática de localização (Fused Location / Geocoder)
- Geração de relatórios em PDF
- Sincronização offline com fila (WorkManager)
- Histórico de viagens, consumo e gestão de veículos
- Integração com Google Sheets / Drive via Apps Script (URLs e secret fora do repositório)
- Firebase Firestore, Crashlytics e Remote Config

## Stack

| Camada | Tecnologias |
|--------|-------------|
| Linguagem | Kotlin |
| UI | Android Views / Material Design |
| Backend / Auth | Firebase Auth, Firestore, Crashlytics, Remote Config |
| ML / Vision | Google ML Kit (Text Recognition, Document Scanner) |
| Localização | Play Services Location |
| Sync | WorkManager + Google Apps Script |
| Charts | MPAndroidChart |

## Estrutura

```
app/src/main/java/com/luiz/controlekm/
  LoginActivity.kt / CadastroActivity.kt
  MainActivity.kt          # fluxo principal de KM e despesas
  HistoricoActivity.kt
  ConsumoActivity.kt
  VeiculoActivity.kt
  SyncWorker.kt            # sincronização em background
```

## Como rodar

### Pré-requisitos

- Android Studio (Ladybug ou mais recente)
- JDK 11+
- Conta Firebase com projeto Android (`com.luiz.controlekm`)

### Setup

1. Clone o repositório
2. Baixe o `google-services.json` no Console do Firebase e coloque em `app/google-services.json`  
   *(este arquivo **não** é versionado — veja `.gitignore`)*
3. Crie `local.properties` na raiz (além do `sdk.dir` do Android SDK):

```properties
sdk.dir=C:\\Users\\SEU_USUARIO\\AppData\\Local\\Android\\Sdk
SCRIPT_URL_PRINCIPAL=https://script.google.com/macros/s/.../exec
SCRIPT_URL_BACKUP=https://script.google.com/macros/s/.../exec
SYNC_API_SECRET=seu_segredo
```

4. Abra o projeto no Android Studio, sync Gradle e rode no emulador/dispositivo

```bash
./gradlew :app:assembleDebug
```

## Segurança

- `google-services.json` e `local.properties` **não** entram no Git
- Secrets de sync (`SYNC_API_SECRET`, URLs do Apps Script) vêm só do `local.properties` → `BuildConfig`
- Restrinja a API key do Firebase/Google Cloud ao package `com.luiz.controlekm` e ao SHA do keystore

## Autor

**Luiz Gustavo Marques** — [GitHub](https://github.com/luiz775) · [LinkedIn](https://www.linkedin.com/in/luiz-gustavo140694/)
