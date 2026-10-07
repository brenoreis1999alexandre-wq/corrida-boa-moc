# RotaLume

App Android de apoio ao motorista, com interface em painel, histórico e ajustes; sem área ou recursos Premium.

## Funcionalidades

- Botão flutuante circular **R$**, arrastável para qualquer posição e com posição salva no aparelho.
- Toque na bolha para fazer OCR da tela atual; segure para ligar/desligar o OCR contínuo, que examina a tela aproximadamente a cada 1,4 s.
- A leitura automática por Acessibilidade dos apps de motorista continua ativa.
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
5. Abra Uber Driver, 99 Motorista ou inDrive. A bolha R$ aparece; arraste para mover, toque para OCR da tela atual, ou segure para iniciar/parar a leitura visual contínua.
6. Para uma oferta que aparece dentro de um vídeo ou como imagem, deixe o preço e os dados visíveis e use OCR contínuo (Android 11+).

## Build

O workflow GitHub Actions compila APKs de teste e publica o arquivo em GitHub Releases ao atualizar o código. Abra o projeto no Android Studio com JDK 17 e Android SDK 35.

## Privacidade e limitações

Histórico e ajustes ficam neste aparelho. As imagens capturadas pelo OCR são processadas localmente e não são salvas nem enviadas a servidor. A captura visual requer Android 11 ou superior. O OCR contínuo examina a tela a cada ~1,4 s enquanto ativado e pode gastar bateria; telas protegidas por alguns apps podem ficar pretas, e textos que aparecem por pouco tempo podem escapar. Confira os valores antes de decidir. Não é afiliado a Uber, 99, inDrive, GanhoPro ou outras plataformas.
