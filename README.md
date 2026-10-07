# RotaLume

App Android de apoio ao motorista, com interface em painel, histórico e ajustes; sem área ou recursos Premium.

## Funcionalidades

- Botão flutuante circular **R$**, arrastável para qualquer posição e com posição salva no aparelho.
- Toque na bolha para reler a última oferta capturada; a análise automática por Acessibilidade continua ativa.
- Apps monitorados: Uber Driver, 99 Motorista e inDrive.
- Classificação informativa: corrida boa, média ou ruim; cálculos de tarifa, distância, tempo e combustível.
- Histórico local com data, origem/destino quando a tela disponibiliza, tarifa, km, tempo e recomendação; filtros por hoje, mês e tudo.
- Painel resume as ofertas analisadas. Os valores são estimativas de ofertas, não comprovantes de corridas realizadas.
- Ajustes de gasolina, consumo e critérios; o custo por km de combustível é calculado a partir dos dados informados.
- O app não toca em botões, aceita ou recusa corridas.

## Critérios iniciais

- Ruim: líquido após combustível ≤ 0 ou abaixo de R$ 25/h.
- Boa: pelo menos R$ 35/h líquido e R$ 2,00 líquidos/km.
- Média: demais ofertas acima do mínimo.

O cálculo considera somente combustível, não manutenção, pneus, depreciação, impostos ou outros custos.

## Instalação e ativação

1. Instale e abra o APK.
2. Ajuste o combustível e o consumo do veículo.
3. Permita a janela flutuante.
4. Ative RotaLume em Serviços de Acessibilidade. Se o Android bloquear por ser APK instalado fora da loja, abra **Informações do app → ⋮ → Permitir configurações restritas** e tente novamente.
5. Abra Uber Driver, 99 Motorista ou inDrive. A bolha R$ aparece; arraste para mover ou toque para reler.

## Build

O workflow GitHub Actions compila APKs de teste ao atualizar o código. Abra o projeto no Android Studio com JDK 17 e Android SDK 35 ou use o APK publicado na pasta `downloads/`.

## Privacidade e limitações

Histórico e ajustes ficam neste aparelho. O app não envia texto de tela nem endereços a um servidor. A leitura depende do que cada versão dos apps oferece à Acessibilidade e deve ser validada em ofertas reais. Não é afiliado a Uber, 99, inDrive, GanhoPro ou outras plataformas.
