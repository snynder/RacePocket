# RacePocket

App Android (Kotlin + Jetpack Compose) de Fórmula 1. Não depende do site: fala direto com as APIs.

| Fonte | Para quê |
|---|---|
| Jolpica-F1 (`api.jolpi.ca`) | Calendário, classificação, resultados, histórico, recordes |
| OpenF1 (`api.openf1.org`) | Replay, rádios, stints, pit stops, direção de prova (bloqueia o acesso grátis de 30 min antes a 30 min depois de cada sessão) |
| Feed oficial da F1 (`livetiming.formula1.com`) | Tela Ao vivo, direto do celular |
| TracingInsights (GitHub) | Mapas das pistas e telemetria |
| Open-Meteo | Previsão do tempo |

## Compilar pelo GitHub (sem instalar nada)

1. Crie um repositório novo no GitHub (pode ser privado).
2. Envie **todo** o conteúdo desta pasta, inclusive a pasta oculta `.github`:
   ```
   git init
   git add .
   git commit -m "RacePocket"
   git branch -M main
   git remote add origin https://github.com/SEU_USUARIO/racepocket.git
   git push -u origin main
   ```
   No upload pelo navegador a pasta `.github` costuma ficar de fora. Nesse caso, crie o arquivo `.github/workflows/build.yml` pelo botão "Add file" e cole o conteúdo.
3. Abra a aba **Actions**. O build "Build APK" começa sozinho e leva de 5 a 10 minutos.
4. Quando ficar verde, abra a execução e baixe o artefato **RacePocket-N** (N é o número do build) em "Artifacts". Ele vem em .zip, com o `.apk` dentro.
5. Passe o `.apk` para o celular e instale. O Android vai pedir para permitir a instalação de fontes desconhecidas.

Se o build falhar, abra o passo "Build" na execução, copie as linhas com `e:` (erros do Kotlin) e me mande.

## Atualizar pelo próprio app

Cada build publica um **Release** no GitHub (`v<versão>`, por exemplo `v2.1`) com o `RacePocket.apk`. Ao abrir, o app consulta o último release e, se houver versão nova, mostra um aviso no Início. Também dá para verificar em Ajustes › Atualizações. "Atualizar agora" baixa o APK e abre o instalador do Android. Na primeira vez, o Android pede para permitir instalações do RacePocket.

- O repositório precisa ser **público**, porque o GitHub não mostra releases de repositório privado sem login.
- O repositório é preenchido sozinho no build. Se mudar de lugar, ajuste em Ajustes › Atualizações.
- A primeira versão com o atualizador precisa ser instalada à mão, uma vez.

## Offline

Tudo o que o app baixa fica salvo no celular e é usado quando não há internet: resultados, classificações, calendário, histórico, mapas e fotos. Em Ajustes › Offline, "Baixar temporada para usar offline" já deixa a temporada inteira salva, inclusive telas que você ainda não abriu.

## Observações

- **Assinatura:** a chave que assina o app não fica no repositório. Ela está nos segredos do GitHub (`KEYSTORE_BASE64` e `KEYSTORE_PASSWORD`, em Settings › Secrets and variables › Actions). Sem eles, o build para com um aviso. Guarde o arquivo de segredos em lugar seguro: se a chave se perder, o app precisa ser desinstalado uma vez para instalar uma versão com chave nova.
- **Onde assistir:** a tabela de emissoras por país fica em `app/src/main/java/com/racepocket/app/data/Static.kt` (`BROADCAST`). Revise a cada temporada.
- **Transcrição dos rádios:** em Ajustes › "URL das transcrições", aponte para a pasta de JSONs que o workflow do seu site já gera, por exemplo
  `https://raw.githubusercontent.com/SEU_USUARIO/SEU_REPO_DO_SITE/main/data/radios/`
  Rádios que não estiverem lá podem ser transcritos na hora com uma chave da OpenAI (Whisper). Isso é opcional e pago por uso, e a chave fica só no celular.
- **Mapas:** cada pista é montada a partir da telemetria da classificação e fica salva no aparelho. Em Circuitos há um botão para baixar todas de uma vez.
- **Versão:** a versão que aparece no app e no release (2.0, 2.1…) fica em `version.txt` e precisa subir a cada build; se a versão já existir, o build para com um aviso. O `versionCode` continua sendo o número da execução do Actions (escondido), para cada build novo instalar por cima do anterior. Push que só altera o `RacePocket.apk` não dispara build.
- **Idiomas:** português, inglês e espanhol estão traduzidos. Os outros idiomas da lista aparecem em inglês.

## Estrutura

```
app/src/main/java/com/racepocket/app/
  data/        APIs, cache, modelos, tabelas fixas, mapas, feed ao vivo
  notify/      alarmes das sessões e notificações
  ui/          tema, componentes, mapa da pista, navegação
  ui/screens/  Home, Ao vivo/Replay, Classificação/Temporada/Circuitos/Corrida, Mais/Ajustes/Recordes, Ferramentas
```
