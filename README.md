# RotaLume

Seu copiloto de corridas para motorista de aplicativo. Lê ofertas visíveis nos apps de motorista configurados, calcula valores, mostra uma recomendação falada/flutuante e guarda um histórico local.

## O que faz

- Botão flutuante **LER CORRIDA**: reanalisa a última oferta visível; a leitura automática continua ativa com Acessibilidade.
- Recomendações: **CORRIDA BOA — ACEITAR**, **MÉDIA — AVALIAR** e **CORRIDA RUIM — NÃO ACEITAR**.
- Mostra métricas brutas e líquidas, separando a estimativa de combustível.
- Guarda ofertas no histórico local, incluindo data, origem/destino se acessíveis, preço, km, tempo e recomendação.
- Permite configurar gasolina, consumo e limites.
- Não clica, aceita nem recusa corridas.

## Critérios iniciais

- Ruim: líquido após combustível ≤ 0 ou abaixo de R$ 25/h.
- Boa: pelo menos R$ 35/h líquido e R$ 2,00 líquidos/km.
- Média: demais ofertas acima do mínimo.

O custo considera apenas combustível; não contempla manutenção, pneus, depreciação, impostos ou outros gastos.

## Criar APK

Abra no Android Studio com JDK 17 e Android SDK 35; use **Build > Build APK(s)**. Ou acompanhe o workflow GitHub Actions, que compila um APK de teste por atualização no código.

## Ativar

1. Instale e abra RotaLume.
2. Ajuste o preço da gasolina e consumo do veículo.
3. Conceda permissão de sobreposição.
4. Ative RotaLume nos Serviços de Acessibilidade.
5. Abra Uber Driver ou 99 Driver. O botão flutuante aparece e as ofertas são analisadas.

## Privacidade e limitações

Histórico e configurações ficam no aparelho. O app não tem permissão de internet nem envia tela/endereço a servidor. A extração depende do texto que cada versão do app de motorista oferece à Acessibilidade e deve ser conferida em ofertas reais. A localização pode não ficar disponível. Não é afiliado ao Uber, 99, GigU ou GanhoPro.
