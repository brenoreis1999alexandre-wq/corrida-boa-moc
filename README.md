# Corrida Boa MOC

App Android para ajudar motorista a analisar ofertas de corrida em Montes Claros. Lê o texto visível dos apps de motorista configurados, calcula indicadores, fala uma recomendação e registra ofertas em histórico local.

## Funcionalidades

- Permissões guiadas para janela flutuante e Serviço de Acessibilidade.
- Leitura heurística de valor, km, tempo e, quando exposto pela tela, origem/destino.
- Cálculo de custo estimado de combustível, lucro após combustível e métricas brutas e líquidas por hora, minuto e km.
- Painel flutuante e anúncio falado: **BOA — ACEITAR**, **MÉDIA — AVALIAR** ou **RUIM — NÃO ACEITAR**.
- Histórico local das ofertas com data, origem, destino, preço, distâncias, duração, custos, lucro e recomendação.
- Configurações locais de combustível, consumo e limites de decisão.

## Critérios padrão

- **RUIM — NÃO ACEITAR:** lucro após combustível ≤ 0 ou ganho líquido abaixo de R$ 25/h.
- **BOA — ACEITAR:** ganho líquido de pelo menos R$ 35/h e pelo menos R$ 2,00 líquidos/km.
- **MÉDIA — AVALIAR:** demais ofertas acima do mínimo.

Os limites podem ser personalizados. O cálculo de custos inclui combustível estimado; não inclui manutenção, pneus, depreciação, impostos ou outros gastos do veículo. O painel exibe medidas brutas e líquidas separadamente.

## Abrir e gerar o APK

Abra esta pasta no Android Studio com JDK 17 e Android SDK 35. Aguarde a sincronização e use **Build > Build APK(s)**. O APK de teste aparece em `app/build/outputs/apk/debug/app-debug.apk`. O workflow do GitHub Actions também compila o APK e o disponibiliza como artefato da execução.

## Ativar no telefone

1. Instale o APK e abra Corrida Boa MOC.
2. Configure o preço da gasolina e o consumo do veículo.
3. Conceda a permissão de sobrepor outros apps.
4. Em Acessibilidade, ative o serviço Corrida Boa MOC.
5. Teste com cuidado em ofertas reais e confirme cada número na tela antes de decidir.

## Privacidade e limitações

Histórico e configurações ficam no banco de dados local do aparelho; o app não tem permissão de internet e não envia conteúdo para servidor. O serviço lê conteúdo de tela dos pacotes de motorista configurados exclusivamente para calcular e registrar localmente.

A extração depende de como cada versão do Uber Driver/99 Driver disponibiliza os textos para Acessibilidade. Endereços, tempos e distâncias podem não estar acessíveis ou podem ser associados incorretamente; por isso, o app precisa ser testado no telefone e a leitura conferida antes de usar. Ele não clica, aceita ou recusa corridas. Não é afiliado ao Uber, 99 ou GanhoPro.
