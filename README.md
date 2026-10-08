# RotaLume

App Android de apoio ao motorista, com interface em painel, histórico e ajustes; sem área ou recursos Premium.

## Funcionalidades

- Botão flutuante circular **R$**, arrastável para qualquer posição e com posição salva no aparelho.
- Botão no painel liga/desliga o monitoramento; ao desligar, a bolha some, e ao ligar, volta.
- OCR automático só examina Uber Driver, 99 Motorista e inDrive; lê a tarifa escrita na oferta e usa apenas os dois trechos de rota que aparecem entre esse valor e os botões da oferta. Não estima tarifa pelo km; se não conseguir separar os dados, não calcula uma rota possivelmente errada.
- Prioriza a leitura imediata do texto de Acessibilidade e usa OCR visual como alternativa quando faltam dados.
- Ao detectar oferta com dados suficientes, mostra cartão silencioso por 12 segundos, mesmo que a oferta suma da tela: recomendação PEGAR, AVALIAR ou NÃO PEGAR e métricas separadas. A bolha mantém a duração estimada e a cor da última recomendação.
- Toque na bolha para fazer OCR da tela atual; segure para ligar/desligar o OCR contínuo, que examina a tela aproximadamente a cada 1,4 s.
- A leitura automática por Acessibilidade dos apps de motorista continua ativa.
- Apps monitorados: Uber Driver, 99 Motorista e inDrive.
- A recomendação é informativa e silenciosa; o app não aceita nem recusa corridas e não fala por voz.
- Histórico local com data, origem/destino quando a tela disponibiliza, tarifa, km, tempo e recomendação; filtros por hoje, mês e tudo.
- Painel resume as ofertas analisadas. Os valores são estimativas de ofertas, não comprovantes de corridas realizadas.
- Calculadora de ganhos: estima combustível, aluguel/financiamento e outros custos mensais informados por km, e calcula uma meta bruta ideal; os custos mensais são distribuídos pelos km planejados para o mês.
- Ajustes de gasolina, consumo e critérios; o custo por km de combustível é calculado a partir dos dados informados.
- O app não toca em botões, aceita ou recusa corridas.

## Metas e cores dos indicadores

- A pessoa define, para cada valor bruto da oferta por km, hora e minuto, uma meta boa (verde) e um piso mínimo aceitável.
- Cada indicador fica verde ao alcançar a meta boa, amarelo entre o piso e a meta, e vermelho abaixo do piso. Padrões: R$ 2,00/R$ 1,50 por km; R$ 35/R$ 31 por hora; R$ 0,58/R$ 0,49 por minuto; todos podem ser ajustados.
- PEGAR: lucro positivo, pelo menos dois indicadores verdes e nenhum vermelho. AVALIAR: lucro positivo e pelo menos dois indicadores verdes/amarelos. NÃO PEGAR: lucro não positivo ou menos de dois indicadores aceitáveis.

Os indicadores brutos por km, hora e minuto são valores diferentes e não se somam. O lucro estimado da corrida desconta combustível e custos mensais informados, rateados pelos km previstos no mês. Custos não cadastrados — como manutenção, pneus, depreciação ou impostos — não entram no cálculo; o resultado é uma estimativa, não lucro contábil garantido.

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
