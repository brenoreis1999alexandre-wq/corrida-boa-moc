# Corrida Boa MOC (projeto Android)

App Android para estimar se uma oferta de corrida compensa, mostrando uma sobreposição e falando uma recomendação. O app não aceita nem recusa corridas e não toca nos controles do Uber/99.

## O que está implementado

- Preferências locais para gasolina, consumo, mínimo de R$/hora, meta de R$/hora e meta de R$/km líquido.
- Serviço de Acessibilidade limitado aos pacotes de apps de motorista configurados no XML.
- Leitura heurística de preço, distâncias em km e duração em minutos visíveis na oferta.
- Cálculo de custo aproximado de combustível, líquido estimado, R$/hora, R$/minuto e R$/km.
- Recomendação falada e painel flutuante temporário; dados ficam no aparelho.
- Se faltarem km ou minutos legíveis, avisa que a leitura está incompleta em vez de recomendar aceitar.

## Critério padrão

- **Corrida ruim — não aceitar:** líquido estimado menor ou igual a zero, ou abaixo de R$ 25/h.
- **Corrida boa — pegar:** pelo menos R$ 35/h e R$ 2,00 líquidos por km.
- **Compensa — avalie:** demais ofertas acima do mínimo.

Os valores podem ser alterados na tela inicial. As estimativas consideram combustível, não incluem manutenção, pneus, depreciação, impostos ou outros custos.

## Abrir e gerar o APK

1. Abra esta pasta no Android Studio (JDK 17 e Android SDK 35).
2. Aguarde a sincronização do Gradle e use **Build > Build APK(s)**.
3. O APK de teste ficará em `app/build/outputs/apk/debug/app-debug.apk`.

Também há um workflow em `.github/workflows/android-build.yml` que pode gerar um APK de teste em GitHub Actions; o arquivo aparece como artefato da execução.

## Ativar no telefone

1. Instale o APK e abra Corrida Boa MOC.
2. Ajuste gasolina e consumo do seu veículo.
3. Conceda **Permitir janela flutuante**.
4. Em Acessibilidade, ative o serviço **Corrida Boa MOC**.
5. Faça testes com ofertas reais paradas, comparando o que o app leu com a tela antes de confiar na recomendação.

## Limitações importantes

A extração depende de como cada versão do Uber Driver/99 Driver expõe texto à Acessibilidade. Layouts e versões podem mudar, e o serviço pode não reconhecer uma oferta ou associar uma distância/tempo incorretamente. O projeto usa uma heurística inicial e precisa ser testado no aparelho do motorista; nunca aceite ou recuse uma corrida só com base no app. O serviço de Acessibilidade lê o conteúdo visível das telas dos apps listados para fazer os cálculos localmente. Não envia esse conteúdo para servidor.

O projeto não é afiliado ao Uber ou à 99. O uso de Acessibilidade, sobreposição e distribuição do APK deve respeitar os termos das plataformas e as regras aplicáveis.
